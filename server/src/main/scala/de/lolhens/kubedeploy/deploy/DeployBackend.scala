package de.lolhens.kubedeploy.deploy

import cats.data.EitherT
import cats.effect.IO
import de.lolhens.kubedeploy.model.Deploy.Deploys
import de.lolhens.kubedeploy.model.DeployResult.{DeployFailure, DeploySuccess}
import de.lolhens.kubedeploy.model.DeployTarget

trait DeployBackend {
  def target: DeployTarget

  def deploy(requests: Deploys): EitherT[IO, DeployFailure, DeploySuccess]
}
