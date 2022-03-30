package de.lolhens.kubedeploy.deploy

import cats.data.EitherT
import cats.effect.IO
import de.lolhens.kubedeploy.JsonOf
import de.lolhens.kubedeploy.model.DeployRequest
import de.lolhens.kubedeploy.model.DeployResult.{DeployFailure, DeploySuccess}
import de.lolhens.kubedeploy.model.DeployTarget.PortainerDeployTarget
import io.circe.generic.semiauto._
import io.circe.syntax._
import io.circe.{Codec, Json}
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Headers, Method, Request}

class PortainerDeploy(client: Client[IO], deployTarget: PortainerDeployTarget) extends Deploy {
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

  override def deploy(request: DeployRequest): EitherT[IO, DeployFailure, DeploySuccess] = {
    for {
      _ <- EitherT.cond[IO](!request.validate, (), DeployFailure("validate not supported"))
      authResponse <- EitherT.right(client.expect[JsonOf[AuthResponse]](Request[IO](
        method = Method.POST,
        uri = deployTarget.url / "api" / "auth",
      ).withEntity(Json.obj(
        "username" -> deployTarget.username.asJson,
        "password" -> deployTarget.password.value.asJson
      ))))
      authHeader = Authorization(Credentials.Token(AuthScheme.Bearer, authResponse.value.jwt))
      stackIdOption <- EitherT.right(client.expect[JsonOf[Seq[StackEntry]]](Request[IO](
        uri = deployTarget.url / "api" / "stacks",
        headers = Headers(authHeader),
      )).map(_.value.find(_.Name == request.resource).map(_.Id)))
      stackId <- EitherT.fromOption[IO](stackIdOption, DeployFailure(s"stack not found: ${request.resource}"))
      stack <- EitherT.right(client.expect[JsonOf[Stack]](Request[IO](
        uri = deployTarget.url / "api" / "stacks" / stackId,
        headers = Headers(authHeader),
      )))
      stackFile <- EitherT.right(client.expect[JsonOf[StackFile]](Request[IO](
        uri = deployTarget.url / "api" / "stacks" / stackId / "file",
        headers = Headers(authHeader),
      )).map(_.value.StackFileContent))
      regex = request.locator.getOrElse("(?<=image: ).*?(?=\\r?\\n|$)")
      newStackFile = stackFile.replaceFirst(regex, request.value)
      _ <- EitherT.right(client.expect[Unit](Request[IO](
        method = Method.PUT,
        uri = deployTarget.url / "api" / "stacks" / stackId +? ("endpointId" -> stack.value.EndpointId),
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
