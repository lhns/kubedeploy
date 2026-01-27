package de.lhns.kubedeploy.route

import cats.data.EitherT
import cats.effect.IO
import de.lhns.kubedeploy.JsonOf
import de.lhns.kubedeploy.deploy.DeployBackend
import de.lhns.kubedeploy.model.Deploy.Deploys
import de.lhns.kubedeploy.model.DeployResult
import de.lhns.kubedeploy.model.DeployResult.DeployFailure
import de.lhns.kubedeploy.model.DeployTarget.DeployTargetId
import de.lolhens.http4s.errors.syntax._
import de.lolhens.http4s.errors.{ErrorResponseEncoder, ErrorResponseLogger}
import org.http4s.dsl.io._
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, HttpRoutes}
import org.log4s.getLogger

class KubedeployRoutes(backends: Map[DeployTargetId, DeployBackend]) {
  private val logger = getLogger
  private implicit val deployFailureErrorResponseEncoder: ErrorResponseEncoder[DeployFailure] =
    ErrorResponseEncoder.instance((_, deployFailure) => JsonOf(deployFailure: DeployResult))
  private implicit val throwableErrorResponseEncoder: ErrorResponseEncoder[Throwable] =
    deployFailureErrorResponseEncoder.contramap(e => DeployFailure(e.stackTraceString))
  private implicit val stringErrorResponseEncoder: ErrorResponseEncoder[String] =
    deployFailureErrorResponseEncoder.contramap(e => DeployFailure(e))
  private implicit val deployFailureErrorResponseLogger: ErrorResponseLogger[DeployFailure] = ErrorResponseLogger.stringLogger(logger.logger).contramap(_.message)
  private implicit val throwableErrorResponseLogger: ErrorResponseLogger[Throwable] = ErrorResponseLogger.throwableLogger(logger.logger)
  private implicit val stringErrorResponseLogger: ErrorResponseLogger[String] = ErrorResponseLogger.stringLogger(logger.logger)

  def toRoutes: HttpRoutes[IO] = HttpRoutes.of {
    case GET -> Root / "health" => Ok()
    case request@POST -> Root / "deploy" / target =>
      (for {
        backend <- backends.get(DeployTargetId(target))
          .toRight(DeployFailure("target not found"))
          .toErrorResponse[IO](NotFound)
        _ <- EitherT.fromOption[IO](request.headers.get[Authorization].collect {
          case Authorization(Credentials.Token(AuthScheme.Bearer, secret)) if secret == backend.target.secret.value => ()
        }, "not authorized").toErrorResponse(Unauthorized)
        deploys <- request.as[JsonOf[Deploys]].map(_.value).orErrorResponse(BadRequest)
        deployResult <- backend.deploy(deploys).leftSemiflatMap { failure =>
          if (failure.conflict) Conflict(JsonOf(failure: DeployResult))
          else InternalServerError(JsonOf(failure: DeployResult))
        }
        response <- Ok(JsonOf(deployResult: DeployResult)).orErrorResponse(InternalServerError)
      } yield
        response)
        .merge
  }
}
