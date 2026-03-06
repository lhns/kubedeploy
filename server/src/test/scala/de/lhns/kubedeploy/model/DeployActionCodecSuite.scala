package de.lhns.kubedeploy.model

import de.lhns.kubedeploy.CatsEffectSuite
import de.lhns.kubedeploy.model.DeployAction.{ApplyYamlAction, AwaitStatusAction, ImageAction}
import io.circe.parser.decode
import io.circe.syntax._

class DeployActionCodecSuite extends CatsEffectSuite {
  test("decode legacy awaitStatus string") {
    assertEquals(
      decode[DeployAction]("\"awaitStatus\""),
      Right(AwaitStatusAction(timeoutSeconds = None, pollIntervalMillis = None)),
    )
  }

  test("decode awaitStatus object with params") {
    val json =
      """{"awaitStatus":{"timeoutSeconds":5,"pollIntervalMillis":250}}"""

    assertEquals(
      decode[DeployAction](json),
      Right(AwaitStatusAction(timeoutSeconds = Some(5L), pollIntervalMillis = Some(250L))),
    )
  }

  test("encode awaitStatus without params as legacy string") {
    val action: DeployAction = AwaitStatusAction(timeoutSeconds = None, pollIntervalMillis = None)
    assertEquals(action.asJson.noSpaces, "\"awaitStatus\"")
  }

  test("round-trip applyYaml action") {
    val action: DeployAction = ApplyYamlAction("apiVersion: v1\nkind: ConfigMap")

    assertEquals(
      decode[DeployAction](action.asJson.noSpaces),
      Right(action),
    )
  }

  test("existing actions remain compatible") {
    val action: DeployAction = ImageAction("ghcr.io/lhns/kubedeploy:1.0.0")

    assertEquals(
      decode[DeployAction](action.asJson.noSpaces),
      Right(action),
    )
  }
}
