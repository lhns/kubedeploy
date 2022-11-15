package de.lhns.kubedeploy.deploy

import cats.data.EitherT
import cats.effect.{Async, IO, Resource}
import cats.syntax.all._
import de.lhns.kubedeploy.JsonOf
import de.lhns.kubedeploy.model.DeployAction.{EnvAction, ImageAction, JsonAction, RegexAction, YamlAction}
import de.lhns.kubedeploy.model.DeployResult.{DeployFailure, DeploySuccess}
import de.lhns.kubedeploy.model.DeployTarget.PortainerDeployTarget
import de.lhns.kubedeploy.model.{Deploy, DeployTarget}
import io.circe.generic.semiauto._
import io.circe.syntax._
import io.circe.{Codec, Json}
import org.http4s.circe._
import org.http4s.client.{Client, UnexpectedStatus}
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Headers, Method, Request, Status}
import org.log4s.getLogger

import scala.concurrent.duration._

class PortainerDeployBackend(
                              val target: DeployTarget,
                              portainer: PortainerDeployTarget,
                              client: Client[IO],
                            ) extends DeployBackend with ConsolidateActions {
  private val logger = getLogger

  case class AuthResponse(jwt: String)

  object AuthResponse {
    implicit val codec: Codec[AuthResponse] = deriveCodec
  }

  case class StackEntry(Name: String, Id: Long)

  object StackEntry {
    implicit val codec: Codec[StackEntry] = deriveCodec
  }

  case class Stack(
                    EndpointId: Long,
                    Env: Seq[EnvVar],
                  )

  object Stack {
    implicit val codec: Codec[Stack] = deriveCodec
  }

  case class StackFile(StackFileContent: String)

  object StackFile {
    implicit val codec: Codec[StackFile] = deriveCodec
  }

  case class StackFileUpdate(
                              StackFileContent: String,
                              Env: Seq[EnvVar],
                              Prune: Boolean,
                              PullImage: Boolean,
                            )

  object StackFileUpdate {
    implicit val codec: Codec[StackFileUpdate] = deriveCodec
  }

  case class EnvVar(name: String, value: String)

  object EnvVar {
    implicit val codec: Codec[EnvVar] = deriveCodec
  }

  private def retry[F[_] : Async, A](
                                      io: F[A],
                                      retries: Int,
                                      interval: FiniteDuration,
                                      onRetry: Throwable => Unit = _ => ()
                                    ): F[A] =
    io.attempt.flatMap {
      case Right(a) => Async[F].pure(a)
      case Left(error) if retries > 0 =>
        Async[F].delay(onRetry(error)) *>
          Async[F].delayBy(retry(io, retries - 1, interval), interval)
      case Left(error) =>
        Async[F].raiseError(error)
    }

  private val retryClient: Client[IO] = Client { req =>
    retry(
      client.run(req).flatMap {
        case internalServerError if internalServerError.status == Status.InternalServerError =>
          Resource.eval(IO.raiseError(UnexpectedStatus(internalServerError.status, req.method, req.uri)))

        case resp => Resource.pure(resp)
      },
      retries = 2,
      interval = 1.seconds,
      onRetry = logger.warn(_)(s"retrying $req")
    )
  }

  override def deploy(request: Deploy): EitherT[IO, DeployFailure, DeploySuccess] = {
    for {
      authResponse <- EitherT.right(retryClient.expect[JsonOf[AuthResponse]](Request[IO](
        method = Method.POST,
        uri = portainer.url / "api" / "auth",
      ).withEntity(Json.obj(
        "username" -> portainer.username.asJson,
        "password" -> portainer.password.value.asJson
      ))))
      authHeader = Authorization(Credentials.Token(AuthScheme.Bearer, authResponse.value.jwt))
      stackIdOption <- EitherT.right(retryClient.expect[JsonOf[Seq[StackEntry]]](Request[IO](
        uri = portainer.url / "api" / "stacks",
        headers = Headers(authHeader),
      )).map(_.value.find(_.Name == request.resource).map(_.Id)))
      stackId <- EitherT.fromOption[IO](stackIdOption, DeployFailure(s"stack not found: ${request.resource}"))
      stack <- EitherT.right(retryClient.expect[JsonOf[Stack]](Request[IO](
        uri = portainer.url / "api" / "stacks" / stackId,
        headers = Headers(authHeader),
      )))
      stackFile <- EitherT.right(retryClient.expect[JsonOf[StackFile]](Request[IO](
        uri = portainer.url / "api" / "stacks" / stackId / "file",
        headers = Headers(authHeader),
      )).map(_.value.StackFileContent))
      stackFileUpdate <- {
        val stackFileUpdateIO = request.actions.foldLeft(IO {
          StackFileUpdate(
            StackFileContent = stackFile,
            Env = stack.value.Env,
            Prune = false,
            PullImage = false,
          )
        })((acc, action) => acc.map { stackFileUpdate =>
          action match {
            case ImageAction(image) =>
              stackFileUpdate.copy(StackFileContent = stackFileUpdate.StackFileContent.replace(
                "(?<=image: ).*?(?=\\r?\\n|$)",
                image
              ))
            case regexAction: RegexAction =>
              stackFileUpdate.copy(StackFileContent = regexAction.transform(stackFileUpdate.StackFileContent))
            case yamlAction: YamlAction =>
              stackFileUpdate.copy(StackFileContent = yamlAction.transform(stackFileUpdate.StackFileContent))
            case jsonAction: JsonAction =>
              stackFileUpdate.copy(StackFileContent = jsonAction.transform(stackFileUpdate.StackFileContent))
            case envAction: EnvAction =>
              stackFileUpdate.copy(Env = envAction.transform(
                stackFileUpdate.Env.map(e => e.name -> e.value).toMap
              ).map(e => EnvVar(e._1, e._2)).toSeq)
            case e =>
              logger.warn("unsupported action: " + e)
              stackFileUpdate
          }
        })
        EitherT.right[DeployFailure](stackFileUpdateIO)
      }
      _ <- EitherT.right(retryClient.expect[Unit](Request[IO](
        method = Method.PUT,
        uri = portainer.url / "api" / "stacks" / stackId +? ("endpointId" -> stack.value.EndpointId),
        headers = Headers(authHeader),
      ).withEntity(JsonOf(stackFileUpdate))))
    } yield
      DeploySuccess(awaitedStatus = false)
  }
}
