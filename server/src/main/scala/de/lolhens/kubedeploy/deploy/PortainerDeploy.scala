package de.lolhens.kubedeploy.deploy

import cats.data.EitherT
import cats.effect.{Async, IO}
import cats.syntax.all._
import de.lolhens.kubedeploy.JsonOf
import de.lolhens.kubedeploy.model.DeployRequest
import de.lolhens.kubedeploy.model.DeployRequest.Locator
import de.lolhens.kubedeploy.model.DeployResult.{DeployFailure, DeploySuccess}
import de.lolhens.kubedeploy.model.DeployTarget.PortainerDeployTarget
import io.circe.generic.semiauto._
import io.circe.syntax._
import io.circe.{Codec, Json}
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Headers, Method, Request}
import org.log4s.getLogger

import scala.concurrent.duration._

class PortainerDeploy(client: Client[IO], deployTarget: PortainerDeployTarget) extends Deploy {
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
                    Env: Json,
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
                              Env: Json,
                              Prune: Boolean,
                            )

  object StackFileUpdate {
    implicit val codec: Codec[StackFileUpdate] = deriveCodec
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
      client.run(req),
      retries = 2,
      interval = 1.seconds,
      onRetry = logger.warn(_)(s"retrying $req")
    )
  }

  override def deploy(request: DeployRequest): EitherT[IO, DeployFailure, DeploySuccess] = {
    for {
      _ <- EitherT.cond[IO](!request.awaitStatusOrDefault, (), DeployFailure("validate not supported"))
      authResponse <- EitherT.right(retryClient.expect[JsonOf[AuthResponse]](Request[IO](
        method = Method.POST,
        uri = deployTarget.url / "api" / "auth",
      ).withEntity(Json.obj(
        "username" -> deployTarget.username.asJson,
        "password" -> deployTarget.password.value.asJson
      ))))
      authHeader = Authorization(Credentials.Token(AuthScheme.Bearer, authResponse.value.jwt))
      stackIdOption <- EitherT.right(retryClient.expect[JsonOf[Seq[StackEntry]]](Request[IO](
        uri = deployTarget.url / "api" / "stacks",
        headers = Headers(authHeader),
      )).map(_.value.find(_.Name == request.resource).map(_.Id)))
      stackId <- EitherT.fromOption[IO](stackIdOption, DeployFailure(s"stack not found: ${request.resource}"))
      stack <- EitherT.right(retryClient.expect[JsonOf[Stack]](Request[IO](
        uri = deployTarget.url / "api" / "stacks" / stackId,
        headers = Headers(authHeader),
      )))
      stackFile <- EitherT.right(retryClient.expect[JsonOf[StackFile]](Request[IO](
        uri = deployTarget.url / "api" / "stacks" / stackId / "file",
        headers = Headers(authHeader),
      )).map(_.value.StackFileContent))
      regex = request.locatorOrDefault match {
        case Locator.Version => "(?<=image: ).*?(?=\\r?\\n|$)"
        case Locator.Regex(regex) => regex
      }
      newStackFile = stackFile.replaceFirst(regex, request.value)
      _ <- EitherT.right(retryClient.expect[Unit](Request[IO](
        method = Method.PUT,
        uri = deployTarget.url / "api" / "stacks" / stackId +? ("endpointId" -> stack.value.EndpointId),
        headers = Headers(authHeader),
      ).withEntity(JsonOf(StackFileUpdate(
        StackFileContent = newStackFile,
        Env = stack.value.Env,
        Prune = true
      )))))
    } yield
      DeploySuccess(awaitedStatus = false)
  }
}
