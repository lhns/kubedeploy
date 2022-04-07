package de.lolhens.kubedeploy.deploy

import cats.data.EitherT
import cats.effect.IO
import cats.effect.std.Queue
import cats.effect.unsafe.IORuntime
import cats.syntax.all._
import de.lolhens.kubedeploy.model.Deploy.Deploys
import de.lolhens.kubedeploy.model.DeployResult.{DeployFailure, DeploySuccess}
import de.lolhens.kubedeploy.model.{Deploy, DeployResult}

trait ConsolidateActions extends DeployBackend {
  private val locksQ: Queue[IO, Set[(Option[String], String)]] =
    Queue.bounded[IO, Set[(Option[String], String)]](1)
      .flatTap(_.offer(Set.empty))
      .unsafeRunSync()(IORuntime.global)

  private def awaitLock[A](namespace: Option[String], resource: String, io: IO[A]): IO[A] =
    locksQ.take.flatMap { locks =>
      if (locks.contains((namespace, resource)))
        locksQ.offer(locks) *>
          awaitLock(namespace, resource, io)
      else
        locksQ.offer(locks + ((namespace, resource))).bracket { _ =>
          io
        } { _ =>
          locksQ.take.flatMap(locks => locksQ.offer(locks - ((namespace, resource))))
        }
    }

  override def deploy(requests: Deploys): EitherT[IO, DeployFailure, DeploySuccess] = EitherT {
    IO.parSequenceN(8) {
      requests.deployRequests
        .groupBy(e => (e.namespace, e.resource))
        .map {
          case ((namespace, resource), requests) =>
            awaitLock(namespace, resource, {
              deploy(requests.reduce((a, b) => a.withActions(a.actions ++ b.actions))).value
            })
        }
        .toList
    }.map(_
      .map[DeployResult](_.merge)
      .reduce(_ |+| _)
      .toEither
    )
  }

  def deploy(request: Deploy): EitherT[IO, DeployFailure, DeploySuccess]
}
