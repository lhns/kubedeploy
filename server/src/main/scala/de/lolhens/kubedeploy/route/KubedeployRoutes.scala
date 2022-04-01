package de.lolhens.kubedeploy.route

import cats.data.{EitherT, OptionT}
import cats.effect.IO
import cats.syntax.semigroup._
import de.lolhens.http4s.errors.syntax._
import de.lolhens.http4s.errors.{ErrorResponseEncoder, ErrorResponseLogger}
import de.lolhens.kubedeploy.JsonOf
import de.lolhens.kubedeploy.deploy.PortainerDeploy
import de.lolhens.kubedeploy.model.DeployRequest.DeployRequests
import de.lolhens.kubedeploy.model.DeployResult.DeployFailure
import de.lolhens.kubedeploy.model.DeployTarget.DeployTargetId
import de.lolhens.kubedeploy.model.{DeployResult, DeployTarget}
import de.lolhens.kubedeploy.repo.DeployTargetRepo
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, HttpRoutes}
import org.log4s.getLogger

class KubedeployRoutes(client: Client[IO], deployTargetRepo: DeployTargetRepo[IO]) {
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
        deployTarget <- OptionT(deployTargetRepo.get(DeployTargetId(target)))
          .toRight(DeployFailure("target not found")).toErrorResponse(NotFound)
        _ <- EitherT.fromOption[IO](request.headers.get[Authorization].collect {
          case Authorization(Credentials.Token(AuthScheme.Bearer, secret)) if secret == deployTarget.secret.value =>
            ()
        }, "not authorized").toErrorResponse(Unauthorized)
        deployRequests <- request.as[JsonOf[DeployRequests]].map(_.value).orErrorResponse(BadRequest)
        deploy <- IO(deployTarget match {
          case DeployTarget(_, _, Some(portainerDeployTarget)) =>
            new PortainerDeploy(client, portainerDeployTarget)
        }).orErrorResponse(InternalServerError)
        deployResult <- EitherT {
          IO.parTraverseN(8)(deployRequests.deployRequests) { deployRequest =>
            deploy.deploy(deployRequest).value
          }.map(_
            .map[DeployResult](_.merge)
            .reduce(_ |+| _)
            .toEither
          )
        }.toErrorResponse(InternalServerError)
        response <- Ok(JsonOf(deployResult: DeployResult)).orErrorResponse(InternalServerError)
      } yield
        response)
        .merge
  }
}
