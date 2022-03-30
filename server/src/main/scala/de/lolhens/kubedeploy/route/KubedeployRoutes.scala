package de.lolhens.kubedeploy.route

import cats.data.OptionT
import cats.effect.IO
import de.lolhens.http4s.errors.ErrorResponseEncoder.stacktrace._
import de.lolhens.http4s.errors.ErrorResponseEncoder.string._
import de.lolhens.http4s.errors.ErrorResponseLogger
import de.lolhens.http4s.errors.syntax._
import de.lolhens.kubedeploy.JsonOf
import de.lolhens.kubedeploy.deploy.PortainerDeploy
import de.lolhens.kubedeploy.model.{DeployRequest, DeployTarget}
import de.lolhens.kubedeploy.repo.DeployTargetRepo
import org.http4s.HttpRoutes
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.log4s.getLogger

class KubedeployRoutes(client: Client[IO], deployTargetRepo: DeployTargetRepo[IO]) {
  private val logger = getLogger
  private implicit val throwableErrorResponseLogger: ErrorResponseLogger[Throwable] = ErrorResponseLogger.throwableLogger(logger.logger)
  private implicit val stringErrorResponseLogger: ErrorResponseLogger[String] = ErrorResponseLogger.stringLogger(logger.logger)

  def toRoutes: HttpRoutes[IO] = HttpRoutes.of {
    case request@POST -> Root =>
      (for {
        deployRequest <- request.as[JsonOf[DeployRequest]].map(_.value).orErrorResponse(BadRequest)
        deployTarget <- OptionT(deployTargetRepo.get(deployRequest.target)).toRight("target not found").toErrorResponse(NotFound)
        deploy <- IO(deployTarget match {
          case DeployTarget(_, Some(portainerDeployTarget)) =>
            new PortainerDeploy(client, portainerDeployTarget)
        }).orErrorResponse(InternalServerError)
        deployResult <- deploy.tryDeploy(deployRequest).orErrorResponse(InternalServerError)
        response <- Ok(JsonOf(deployResult)).orErrorResponse(InternalServerError)
      } yield
        response)
        .merge
  }
}
