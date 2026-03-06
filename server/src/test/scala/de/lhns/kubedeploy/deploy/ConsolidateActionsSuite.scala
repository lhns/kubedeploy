package de.lhns.kubedeploy.deploy

import cats.data.EitherT
import cats.effect.{Deferred, IO, Ref}
import de.lhns.kubedeploy.{CatsEffectSuite, Secret}
import de.lhns.kubedeploy.model.Deploy.Deploys
import de.lhns.kubedeploy.model.DeployAction.ImageAction
import de.lhns.kubedeploy.model.DeployResult.{DeployFailure, DeploySuccess}
import de.lhns.kubedeploy.model.DeployTarget.DeployTargetId
import de.lhns.kubedeploy.model.{Deploy, DeployAction, DeployTarget}

import scala.concurrent.duration._

class ConsolidateActionsSuite extends CatsEffectSuite {
  private val target = DeployTarget(
    id = DeployTargetId("test"),
    secret = Secret("secret"),
    git = None,
    portainer = None,
    kubernetes = None,
  )

  testIO("serializes same namespace/resource across concurrent deploy calls") {
    for {
      running <- Ref.of[IO, Int](0)
      maxRunning <- Ref.of[IO, Int](0)
      backend = new DeployBackend with ConsolidateActions {
        override val target: DeployTarget = ConsolidateActionsSuite.this.target

        override def deploy(request: Deploy): EitherT[IO, DeployFailure, DeploySuccess] = EitherT.right {
          for {
            current <- running.updateAndGet(_ + 1)
            _ <- maxRunning.update(max => math.max(max, current))
            _ <- IO.sleep(75.millis)
            _ <- running.update(_ - 1)
          } yield DeploySuccess(awaitedStatus = false)
        }
      }
      deploys = Deploys(Seq(Deploy(resource = "my-app", namespace = Some("apps"), actions = Seq.empty[DeployAction])))
      (first, second) <- IO.both(
        backend.deploy(deploys).value,
        backend.deploy(deploys).value,
      )
      max <- maxRunning.get
    } yield {
      assertEquals(first, Right(DeploySuccess(awaitedStatus = false)))
      assertEquals(second, Right(DeploySuccess(awaitedStatus = false)))
      assertEquals(max, 1)
    }
  }

  testIO("runs different resources in parallel within one deploy batch") {
    for {
      running <- Ref.of[IO, Int](0)
      maxRunning <- Ref.of[IO, Int](0)
      started <- Ref.of[IO, Int](0)
      gate <- Deferred[IO, Unit]
      backend = new DeployBackend with ConsolidateActions {
        override val target: DeployTarget = ConsolidateActionsSuite.this.target

        override def deploy(request: Deploy): EitherT[IO, DeployFailure, DeploySuccess] = EitherT.right {
          for {
            current <- running.updateAndGet(_ + 1)
            _ <- maxRunning.update(max => math.max(max, current))
            startedCount <- started.updateAndGet(_ + 1)
            _ <- if (startedCount >= 2) gate.complete(()).attempt.void else IO.unit
            _ <- gate.get.timeoutTo(1.second, IO.unit)
            _ <- IO.sleep(25.millis)
            _ <- running.update(_ - 1)
          } yield DeploySuccess(awaitedStatus = false)
        }
      }
      deploys = Deploys(Seq(
        Deploy(resource = "app-a", namespace = Some("apps"), actions = Seq.empty[DeployAction]),
        Deploy(resource = "app-b", namespace = Some("apps"), actions = Seq.empty[DeployAction]),
      ))
      result <- backend.deploy(deploys).value
      max <- maxRunning.get
    } yield {
      assertEquals(result, Right(DeploySuccess(awaitedStatus = false)))
      assert(max >= 2)
    }
  }

  testIO("groups same resource requests and merges actions") {
    for {
      seen <- Ref.of[IO, List[Deploy]](Nil)
      backend = new DeployBackend with ConsolidateActions {
        override val target: DeployTarget = ConsolidateActionsSuite.this.target

        override def deploy(request: Deploy): EitherT[IO, DeployFailure, DeploySuccess] =
          EitherT.right(seen.update(_ :+ request).as(DeploySuccess(awaitedStatus = false)))
      }
      deploys = Deploys(Seq(
        Deploy(resource = "my-app", namespace = Some("apps"), actions = Seq(ImageAction("image:a"))),
        Deploy(resource = "my-app", namespace = Some("apps"), actions = Seq(ImageAction("image:b"))),
        Deploy(resource = "other-app", namespace = Some("apps"), actions = Seq(ImageAction("image:c"))),
      ))
      result <- backend.deploy(deploys).value
      requests <- seen.get
      mergedActions = requests.find(_.resource == "my-app").map(_.actions).getOrElse(Seq.empty)
    } yield {
      assertEquals(result, Right(DeploySuccess(awaitedStatus = false)))
      assertEquals(requests.size, 2)
      assertEquals(mergedActions.size, 2)
      assertEquals(
        mergedActions.collect { case ImageAction(image) => image },
        Seq("image:a", "image:b"),
      )
    }
  }
}
