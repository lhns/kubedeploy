package de.lhns.kubedeploy.model

import cats.syntax.semigroup._
import de.lhns.kubedeploy.CatsEffectSuite
import de.lhns.kubedeploy.model.DeployResult.{DeployFailure, DeploySuccess}
import io.circe.parser.decode
import io.circe.syntax._

class DeployResultSuite extends CatsEffectSuite {
  test("encode/decode awaited success") {
    val value: DeployResult = DeploySuccess(awaitedStatus = true)
    assertEquals(decode[DeployResult](value.asJson.noSpaces), Right(value))
  }

  test("encode/decode not found failure") {
    val value: DeployResult = DeployFailure("not found", notFound = true)
    assertEquals(decode[DeployResult](value.asJson.noSpaces), Right(value))
  }

  test("semigroup keeps failures over successes") {
    val merged = (DeployFailure("failed"): DeployResult) |+| (DeploySuccess(awaitedStatus = true): DeployResult)
    assertEquals(merged, DeployFailure("failed"))
  }

  test("semigroup combines success awaitedStatus with logical and") {
    val merged = (DeploySuccess(awaitedStatus = true): DeployResult) |+| (DeploySuccess(awaitedStatus = false): DeployResult)
    assertEquals(merged, DeploySuccess(awaitedStatus = false))
  }
}
