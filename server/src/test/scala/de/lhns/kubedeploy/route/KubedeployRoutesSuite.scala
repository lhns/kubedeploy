package de.lhns.kubedeploy.route

import cats.data.EitherT
import cats.effect.IO
import de.lhns.kubedeploy.CatsEffectSuite
import de.lhns.kubedeploy.deploy.DeployBackend
import de.lhns.kubedeploy.model.Deploy.Deploys
import de.lhns.kubedeploy.model.DeployResult.{DeployFailure, DeploySuccess}
import de.lhns.kubedeploy.model.DeployTarget.DeployTargetId
import de.lhns.kubedeploy.model.{DeployResult, DeployTarget}
import de.lhns.kubedeploy.Secret
import io.circe.Json
import io.circe.parser.decode
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Headers, Method, Request, Uri}

class KubedeployRoutesSuite extends CatsEffectSuite {
  private case class StubBackend(
                                  target: DeployTarget,
                                  result: Either[DeployFailure, DeploySuccess],
                                ) extends DeployBackend {
    override def deploy(requests: Deploys): EitherT[IO, DeployFailure, DeploySuccess] =
      EitherT.fromEither[IO](result)
  }

  private val baseTarget = DeployTarget(
    id = DeployTargetId("test"),
    secret = Secret("secret"),
    git = None,
    portainer = None,
    kubernetes = None,
  )

  private val deployPayload: Json = Json.obj(
    "resource" -> Json.fromString("my-app"),
    "actions" -> Json.arr(),
  )

  private def routesFor(result: Either[DeployFailure, DeploySuccess]) =
    new KubedeployRoutes(Map(DeployTargetId("test") -> StubBackend(baseTarget, result))).toRoutes.orNotFound

  private val authHeader = Headers(
    Authorization(Credentials.Token(AuthScheme.Bearer, "secret"))
  )

  testIO("health endpoint") {
    val app = routesFor(Right(DeploySuccess(awaitedStatus = false)))

    app.run(Request[IO](Method.GET, Uri.unsafeFromString("/health"))).map { response =>
      assertEquals(response.status.code, 200)
    }
  }

  testIO("deploy returns unauthorized without bearer token") {
    val app = routesFor(Right(DeploySuccess(awaitedStatus = false)))

    app.run(Request[IO](Method.POST, Uri.unsafeFromString("/deploy/test")).withEntity(deployPayload)).map { response =>
      assertEquals(response.status.code, 401)
    }
  }

  testIO("deploy maps backend not found to 404") {
    val app = routesFor(Left(DeployFailure("missing", notFound = true)))

    app.run(
      Request[IO](Method.POST, Uri.unsafeFromString("/deploy/test"), headers = authHeader)
        .withEntity(deployPayload)
    ).map { response =>
      assertEquals(response.status.code, 404)
    }
  }

  testIO("deploy maps backend conflict to 409") {
    val app = routesFor(Left(DeployFailure("conflict", conflict = true)))

    app.run(
      Request[IO](Method.POST, Uri.unsafeFromString("/deploy/test"), headers = authHeader)
        .withEntity(deployPayload)
    ).map { response =>
      assertEquals(response.status.code, 409)
    }
  }

  testIO("deploy success returns encoded DeployResult") {
    val app = routesFor(Right(DeploySuccess(awaitedStatus = true)))

    app.run(
      Request[IO](Method.POST, Uri.unsafeFromString("/deploy/test"), headers = authHeader)
        .withEntity(deployPayload)
    ).flatMap { response =>
      response.as[String].map { body =>
        assertEquals(response.status.code, 200)
        assertEquals(
          decode[DeployResult](body),
          Right(DeploySuccess(awaitedStatus = true)),
        )
      }
    }
  }
}
