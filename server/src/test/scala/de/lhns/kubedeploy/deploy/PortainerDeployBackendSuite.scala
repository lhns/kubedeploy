package de.lhns.kubedeploy.deploy

import cats.effect.{IO, Ref}
import de.lhns.kubedeploy.model.DeployAction.{AwaitStatusAction, ImageAction}
import de.lhns.kubedeploy.model.DeployResult.{DeployFailure, DeploySuccess}
import de.lhns.kubedeploy.model.DeployTarget.{DeployTargetId, PortainerDeployTarget}
import de.lhns.kubedeploy.model.{Deploy, DeployTarget}
import de.lhns.kubedeploy.{CatsEffectSuite, Config, Secret}
import io.circe.Json
import io.circe.syntax._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.{HttpApp, Method, Request, Response, Status, Uri}
import org.http4s.client.Client

class PortainerDeployBackendSuite extends CatsEffectSuite {
  private def nextStatus(ref: Ref[IO, List[Int]]): IO[Int] =
    ref.modify {
      case head :: next :: tail => (next :: tail, head)
      case head :: Nil => (head :: Nil, head)
      case Nil => (Nil, 1)
    }

  private def backendFor(
                          app: HttpApp[IO],
                          awaitStatus: Option[Config.AwaitStatus] = None,
                        ): PortainerDeployBackend = {
    val target = DeployTarget(
      id = DeployTargetId("portainer"),
      secret = Secret("secret"),
      git = None,
      portainer = Some(PortainerDeployTarget(
        url = Uri.unsafeFromString("http://portainer.local"),
        username = "admin",
        password = Secret("password"),
      )),
      kubernetes = None,
    )

    new PortainerDeployBackend(
      target = target,
      portainer = target.portainer.get,
      client = Client.fromHttpApp(app),
      awaitStatus = awaitStatus,
    )
  }

  private def app(
                   stackStatuses: Ref[IO, List[Int]],
                   updateBody: Ref[IO, Option[String]],
                 ): HttpApp[IO] = HttpApp[IO] { request =>
    (request.method, request.uri.path.renderString) match {
      case (Method.POST, "/api/auth") =>
        Ok(Json.obj("jwt" -> "jwt-token".asJson))

      case (Method.GET, "/api/stacks") =>
        Ok(Json.arr(
          Json.obj(
            "Id" -> 1.asJson,
            "Name" -> "my-app".asJson,
            "EndpointId" -> 1.asJson,
            "Status" -> 1.asJson,
          )
        ))

      case (Method.GET, "/api/stacks/1") =>
        nextStatus(stackStatuses).flatMap { status =>
          Ok(Json.obj(
            "EndpointId" -> 1.asJson,
            "Env" -> Json.arr(),
            "Status" -> status.asJson,
          ))
        }

      case (Method.GET, "/api/stacks/1/file") =>
        Ok(Json.obj(
          "StackFileContent" -> "services:\n  app:\n    image: old:1.0.0\n".asJson
        ))

      case (Method.PUT, "/api/stacks/1") =>
        request.as[String].flatMap { body =>
          updateBody.set(Some(body)) *> Ok()
        }

      case _ =>
        IO.pure(Response[IO](Status.NotFound))
    }
  }

  testIO("deploy updates stack without awaitStatus") {
    for {
      stackStatuses <- Ref.of[IO, List[Int]](List(1))
      updateBody <- Ref.of[IO, Option[String]](None)
      backend = backendFor(app(stackStatuses, updateBody))
      result <- backend.deploy(Deploy(
        resource = "my-app",
        namespace = None,
        actions = Seq(ImageAction("new:2.0.0")),
      )).value
      body <- updateBody.get
    } yield {
      assertEquals(result, Right(DeploySuccess(awaitedStatus = false)))
      assert(body.exists(_.contains("new:2.0.0")))
    }
  }

  testIO("deploy awaits stack active status when awaitStatus action is present") {
    for {
      stackStatuses <- Ref.of[IO, List[Int]](List(1, 0, 1))
      updateBody <- Ref.of[IO, Option[String]](None)
      backend = backendFor(app(stackStatuses, updateBody))
      result <- backend.deploy(Deploy(
        resource = "my-app",
        namespace = None,
        actions = Seq(
          ImageAction("new:2.0.0"),
          AwaitStatusAction(timeoutSeconds = Some(2L), pollIntervalMillis = Some(0L)),
        ),
      )).value
    } yield {
      assertEquals(result, Right(DeploySuccess(awaitedStatus = true)))
    }
  }

  testIO("deploy returns failure when awaitStatus times out") {
    for {
      stackStatuses <- Ref.of[IO, List[Int]](List(1, 0))
      updateBody <- Ref.of[IO, Option[String]](None)
      backend = backendFor(app(stackStatuses, updateBody))
      result <- backend.deploy(Deploy(
        resource = "my-app",
        namespace = None,
        actions = Seq(
          ImageAction("new:2.0.0"),
          AwaitStatusAction(timeoutSeconds = Some(0L), pollIntervalMillis = Some(0L)),
        ),
      )).value
    } yield {
      assert(result.isLeft)
      assert(result.swap.exists(_.message.contains("timed out waiting for stack")))
    }
  }
}
