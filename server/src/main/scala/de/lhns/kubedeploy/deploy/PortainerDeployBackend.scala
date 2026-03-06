package de.lhns.kubedeploy.deploy

import cats.data.EitherT
import cats.effect.{Async, IO, Resource}
import cats.syntax.all._
import de.lhns.kubedeploy.Config
import de.lhns.kubedeploy.JsonOf
import de.lhns.kubedeploy.model.DeployAction.{AwaitStatusAction, EnvAction, ImageAction, JsonAction, RegexAction, YamlAction}
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
                              awaitStatus: Option[Config.AwaitStatus],
                            ) extends DeployBackend with ConsolidateActions {
  private val logger = getLogger

  case class AuthResponse(jwt: String)

  object AuthResponse {
    implicit val codec: Codec[AuthResponse] = deriveCodec
  }

  case class StackEntry(Id: Long, Name: String, EndpointId: Long, Status: Int)

  object StackEntry {
    implicit val codec: Codec[StackEntry] = deriveCodec
  }

  case class Stack(
                    EndpointId: Long,
                    Env: Seq[EnvVar],
                    Status: Int,
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

  private def awaitStackStatus(
                                stackId: Long,
                                authHeader: Authorization,
                                awaitAction: AwaitStatusAction,
                              ): EitherT[IO, DeployFailure, Unit] = {
    val resolvedAwait = Config.resolveAwaitStatus(Some(awaitAction), awaitStatus)

    EitherT.right(IO.monotonic).flatMap { start =>
      def loop: EitherT[IO, DeployFailure, Unit] =
        EitherT.right(retryClient.expect[JsonOf[Stack]](Request[IO](
          uri = portainer.url / "api" / "stacks" / stackId,
          headers = Headers(authHeader),
        ))).flatMap { stack =>
          if (stack.value.Status == 1)
            EitherT.rightT[IO, DeployFailure](())
          else
            EitherT.right(IO.monotonic).flatMap { now =>
              if (now - start >= resolvedAwait.timeout)
                EitherT.leftT[IO, Unit](DeployFailure(s"timed out waiting for stack '${stackId}' to become active"))
              else
                EitherT.right(IO.sleep(resolvedAwait.pollInterval)) *> loop
            }
        }

      loop
    }
  }

  override def deploy(request: Deploy): EitherT[IO, DeployFailure, DeploySuccess] = {
    val (resourceEndpoint, resourceName): (Long, String) = request.resource match {
      case s"$endpoint/$name" => (endpoint.toLong, name)
      case name => (1L, name)
    }
    val awaitAction = request.actions.collectFirst { case action: AwaitStatusAction => action }

    for {
      authResponse <- EitherT.right(retryClient.expect[JsonOf[AuthResponse]](Request[IO](
        method = Method.POST,
        uri = portainer.url / "api" / "auth",
      ).withEntity(Json.obj(
        "username" -> portainer.username.asJson,
        "password" -> portainer.password.value.asJson
      ))))
      authHeader = Authorization(Credentials.Token(AuthScheme.Bearer, authResponse.value.jwt))
      stackEntries <- EitherT.right(retryClient.expect[JsonOf[Seq[StackEntry]]](Request[IO](
        uri = portainer.url / "api" / "stacks",
        headers = Headers(authHeader),
      )).map(_.value.filter(e => e.Name == resourceName && e.EndpointId == resourceEndpoint)))
      _ <- EitherT.cond[IO](
        stackEntries.size <= 1,
        (),
        DeployFailure(s"multiple matching stacks found: ${request.resource}")
      )
      stackEntry <- EitherT.fromOption[IO](
        stackEntries.headOption,
        DeployFailure(s"stack not found: ${request.resource}")
      )
      _ <- EitherT.cond[IO](
        stackEntry.Status == 1,
        (),
        DeployFailure(s"stack '${request.resource}' is inactive (status: ${stackEntry.Status})", conflict = true)
      )
      stackId = stackEntry.Id
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
              stackFileUpdate.copy(StackFileContent = stackFileUpdate.StackFileContent.replaceAll(
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
            case _: AwaitStatusAction =>
              stackFileUpdate
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
      _ <- awaitAction match {
        case Some(action) =>
          awaitStackStatus(stackId, authHeader, action)
        case None =>
          EitherT.rightT[IO, DeployFailure](())
      }
    } yield
      DeploySuccess(awaitedStatus = awaitAction.nonEmpty)
  }
}
