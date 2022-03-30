package de.lolhens.kubedeploy.repo

import cats.effect.IO
import de.lolhens.kubedeploy.model.DeployTarget
import de.lolhens.kubedeploy.model.DeployTarget.DeployTargetId

trait DeployTargetRepo[F[_]] {
  def get(id: DeployTargetId): F[Option[DeployTarget]]

  def list: F[Seq[DeployTarget]]
}

object DeployTargetRepo {
  def fromSeq(deployTargets: Seq[DeployTarget]): DeployTargetRepo[IO] = new DeployTargetRepo[IO] {
    private val map = deployTargets.map(e => e.id -> e).toMap

    override def get(id: DeployTargetId): IO[Option[DeployTarget]] = IO(map.get(id))

    override def list: IO[Seq[DeployTarget]] = IO(deployTargets)
  }
}
