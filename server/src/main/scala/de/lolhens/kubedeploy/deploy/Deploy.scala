package de.lolhens.kubedeploy.deploy

import cats.data.EitherT
import cats.effect.IO
import de.lolhens.kubedeploy.model.DeployResult.{DeployFailure, DeploySuccess}
import de.lolhens.kubedeploy.model.{DeployRequest, DeployResult}

trait Deploy {
  def deploy(request: DeployRequest): EitherT[IO, DeployFailure, DeploySuccess]
}
