package de.lhns.kubedeploy.deploy

import cats.data.EitherT
import cats.effect.IO
import de.lhns.kubedeploy.model.Deploy.Deploys
import de.lhns.kubedeploy.model.DeployResult.{DeployFailure, DeploySuccess}
import de.lhns.kubedeploy.model.DeployTarget

trait DeployBackend {
  def target: DeployTarget

  def deploy(requests: Deploys): EitherT[IO, DeployFailure, DeploySuccess]
}
