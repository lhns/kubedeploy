package de.lolhens.kubedeploy.deploy

import cats.data.EitherT
import cats.effect.IO
import de.lolhens.kubedeploy.model.DeployResult.{DeployFailure, DeploySuccess}
import de.lolhens.kubedeploy.model.{DeployRequest, DeployResult}

trait Deploy {
  protected def deploy(request: DeployRequest): EitherT[IO, DeployFailure, DeploySuccess]

  final def tryDeploy(request: DeployRequest): IO[DeployResult] =
    deploy(request).value.attempt.map[DeployResult](_.fold(
      e => DeployFailure(e.getMessage),
      _.merge
    ))
}
