package de.lhns.kubedeploy.deploy

import cats.effect.{IO, Ref}
import de.lhns.kubedeploy.model.DeployAction.{ApplyYamlAction, AwaitStatusAction, EnvAction, ImageAction}
import de.lhns.kubedeploy.model.DeployResult.DeploySuccess
import de.lhns.kubedeploy.model.DeployTarget.{DeployTargetId, KubernetesDeployTarget}
import de.lhns.kubedeploy.model.{Deploy, DeployTarget}
import de.lhns.kubedeploy.{CatsEffectSuite, Config, Secret}
import io.circe.Json
import io.circe.syntax._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.{HttpApp, Method, Request, Response, Status, Uri}
import org.http4s.client.Client

class KubernetesDeployBackendSuite extends CatsEffectSuite {
  private val target = DeployTarget(
    id = DeployTargetId("k8s"),
    secret = Secret("secret"),
    git = None,
    portainer = None,
    kubernetes = Some(KubernetesDeployTarget(
      url = Some(Uri.unsafeFromString("https://k8s.local")),
      token = Some(Secret("k8s-token")),
      serviceAccountTokenFile = None,
      defaultNamespace = Some("default"),
    )),
  )

  private def backendFor(
                          app: HttpApp[IO],
                          awaitStatus: Option[Config.AwaitStatus] = None,
                        ): KubernetesDeployBackend =
    new KubernetesDeployBackend(
      target = target,
      kubernetes = target.kubernetes.get,
      client = Client.fromHttpApp(app),
      awaitStatus = awaitStatus,
    )

  private def deploymentJson(image: String, ready: Long): Json = Json.obj(
    "metadata" -> Json.obj(
      "name" -> "my-app".asJson,
      "namespace" -> "default".asJson,
    ),
    "spec" -> Json.obj(
      "replicas" -> 1.asJson,
      "template" -> Json.obj(
        "spec" -> Json.obj(
          "containers" -> Json.arr(
            Json.obj(
              "name" -> "my-app".asJson,
              "image" -> image.asJson,
              "env" -> Json.arr(),
            )
          )
        )
      )
    ),
    "status" -> Json.obj(
      "readyReplicas" -> ready.asJson,
      "availableReplicas" -> ready.asJson,
      "updatedReplicas" -> ready.asJson,
    )
  )

  testIO("patch deployment and await readiness") {
    for {
      getCounter <- Ref.of[IO, Int](0)
      patchBody <- Ref.of[IO, Option[String]](None)
      app = HttpApp[IO] { request =>
        (request.method, request.uri.path.renderString) match {
          case (Method.GET, "/apis/apps/v1/namespaces/default/deployments/my-app") =>
            getCounter.modify { count =>
              val body = count match {
                case 0 => deploymentJson("old:1.0.0", ready = 0)
                case 1 => deploymentJson("new:2.0.0", ready = 0)
                case _ => deploymentJson("new:2.0.0", ready = 1)
              }
              (count + 1, body)
            }.flatMap(Ok(_))

          case (Method.PATCH, "/apis/apps/v1/namespaces/default/deployments/my-app") =>
            request.as[String].flatMap { body =>
              patchBody.set(Some(body)) *> Ok()
            }

          case _ =>
            IO.pure(Response[IO](Status.NotFound))
        }
      }
      backend = backendFor(app)
      result <- backend.deploy(Deploy(
        resource = "deployment/my-app",
        namespace = Some("default"),
        actions = Seq(
          ImageAction("new:2.0.0"),
          EnvAction(Map("ENV_A" -> "1")),
          AwaitStatusAction(timeoutSeconds = Some(2L), pollIntervalMillis = Some(0L)),
        ),
      )).value
      body <- patchBody.get
    } yield {
      assertEquals(result, Right(DeploySuccess(awaitedStatus = true)))
      assert(body.exists(_.contains("new:2.0.0")))
      assert(body.exists(_.contains("\"ENV_A\"")))
    }
  }

  testIO("server-side apply yaml action") {
    for {
      applyRequest <- Ref.of[IO, Option[Request[IO]]](None)
      app = HttpApp[IO] { request =>
        (request.method, request.uri.path.renderString) match {
          case (Method.PATCH, "/apis/apps/v1/namespaces/apps/deployments/my-app") =>
            applyRequest.set(Some(request)) *> Ok()
          case _ =>
            IO.pure(Response[IO](Status.NotFound))
        }
      }
      backend = backendFor(app)
      result <- backend.deploy(Deploy(
        resource = "ignored",
        namespace = None,
        actions = Seq(
          ApplyYamlAction(
            """apiVersion: apps/v1
              |kind: Deployment
              |metadata:
              |  name: my-app
              |  namespace: apps
              |spec:
              |  replicas: 1
              |""".stripMargin
          )
        ),
      )).value
      request <- applyRequest.get
    } yield {
      assertEquals(result, Right(DeploySuccess(awaitedStatus = false)))
      assert(request.exists(_.uri.query.renderString.contains("fieldManager=kubedeploy")))
      assert(request.exists(_.uri.query.renderString.contains("force=true")))
    }
  }

  testIO("awaitStatus timeout produces deploy failure") {
    for {
      getCounter <- Ref.of[IO, Int](0)
      app = HttpApp[IO] { request =>
        (request.method, request.uri.path.renderString) match {
          case (Method.GET, "/apis/apps/v1/namespaces/default/deployments/my-app") =>
            getCounter.update(_ + 1) *> Ok(deploymentJson("old:1.0.0", ready = 0))
          case (Method.PATCH, "/apis/apps/v1/namespaces/default/deployments/my-app") =>
            Ok()
          case _ =>
            IO.pure(Response[IO](Status.NotFound))
        }
      }
      backend = backendFor(app)
      result <- backend.deploy(Deploy(
        resource = "deployment/my-app",
        namespace = Some("default"),
        actions = Seq(
          ImageAction("new:2.0.0"),
          AwaitStatusAction(timeoutSeconds = Some(0L), pollIntervalMillis = Some(0L)),
        ),
      )).value
    } yield {
      assert(result.isLeft)
      assert(result.swap.exists(_.message.contains("timed out waiting for deployment/my-app")))
    }
  }
}
