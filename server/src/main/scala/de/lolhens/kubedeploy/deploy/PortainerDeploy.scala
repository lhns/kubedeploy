package de.lolhens.kubedeploy.deploy

import cats.data.EitherT
import cats.effect.IO
import de.lolhens.kubedeploy.JsonOf
import de.lolhens.kubedeploy.model.DeployRequest
import de.lolhens.kubedeploy.model.DeployResult.{DeployFailure, DeploySuccess}
import io.circe.generic.semiauto._
import io.circe.syntax._
import io.circe.{Codec, Json}
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, BasicCredentials, Credentials, Headers, Method, Request, Uri}

class PortainerDeploy(client: Client[IO]) extends Deploy {
  val host: Uri = ???
  val credentials: BasicCredentials = ???

  case class AuthResponse(jwt: String)

  object AuthResponse {
    implicit val codec: Codec[AuthResponse] = deriveCodec
  }

  case class StackEntry(Name: String, Id: String)

  object StackEntry {
    implicit val codec: Codec[StackEntry] = deriveCodec
  }

  case class Stack(
                    EndpointId: String,
                    Env: String,
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
                              Env: String,
                              Prune: Boolean,
                            )

  object StackFileUpdate {
    implicit val codec: Codec[StackFileUpdate] = deriveCodec
  }

  override protected def deploy(request: DeployRequest): EitherT[IO, DeployFailure, DeploySuccess] = {
    for {
      authResponse <- EitherT.right(client.expect[JsonOf[AuthResponse]](Request[IO](
        method = Method.POST,
        uri = host / "api" / "auth",
      ).withEntity(Json.obj(
        "username" -> credentials.username.asJson,
        "password" -> credentials.password.asJson
      ))))
      authHeader = Authorization(Credentials.Token(AuthScheme.Bearer, authResponse.value.jwt))
      stackIdOption <- EitherT.right(client.expect[JsonOf[Seq[StackEntry]]](Request[IO](
        uri = host / "api" / "stacks",
        headers = Headers(authHeader),
      )).map(_.value.find(_.Name == request.resource).map(_.Id)))
      stackId <- EitherT.fromOption[IO](stackIdOption, DeployFailure(s"stack not found: ${request.resource}"))
      stack <- EitherT.right(client.expect[JsonOf[Stack]](Request[IO](
        uri = host / "api" / "stacks" / stackId,
        headers = Headers(authHeader),
      )))
      stackFile <- EitherT.right(client.expect[JsonOf[StackFile]](Request[IO](
        uri = host / "api" / "stacks" / stackId / "file",
        headers = Headers(authHeader),
      )).map(_.value.StackFileContent))
      regex = request.locator.getOrElse("(?<=image: ).*?(?=\\r?\\n|$)")
      newStackFile = stackFile.replaceFirst(regex, request.value)
      _ <- EitherT.right(client.expect[Unit](Request[IO](
        method = Method.PUT,
        uri = host / "api" / "stacks" / stackId +? ("endpointId" -> stack.value.EndpointId),
        headers = Headers(authHeader),
      ).withEntity(JsonOf(StackFileUpdate(
        StackFileContent = newStackFile,
        Env = stack.value.Env,
        Prune = true
      )))))
    } yield
      DeploySuccess(validated = false)
  }
}
