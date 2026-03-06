package de.lhns.kubedeploy

import cats.effect.{IO, unsafe}
import munit.{FunSuite, Location}

abstract class CatsEffectSuite extends FunSuite {
  protected def runIO[A](f: IO[A]): A = f.unsafeRunSync()(unsafe.IORuntime.global)

  protected def testIO(name: String)(body: => IO[Unit])(implicit loc: Location): Unit =
    test(name) {
      runIO(body)
    }
}
