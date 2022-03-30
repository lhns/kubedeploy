package de.lolhens.kubedeploy.route

import cats.effect.IO
import de.lolhens.kubedeploy.JsonOf
import de.lolhens.kubedeploy.deploy.Deploy
import de.lolhens.kubedeploy.model.DeployResult.DeployFailure
import de.lolhens.kubedeploy.model.{DeployRequest, DeployResult}
import org.http4s.HttpRoutes
import org.http4s.dsl.io._

class KubedeployRoutes(deploy: Deploy) {
  def toRoutes: HttpRoutes[IO] = HttpRoutes.of {
    case request@POST -> Root =>
      for {
        deployRequest <- request.as[JsonOf[DeployRequest]].map(_.value)
        deployResult <- deploy.tryDeploy(deployRequest)
        response <- Ok(JsonOf(deployResult))
      } yield
        response
  }
}
