package de.lhns.kubedeploy.model

import de.lhns.kubedeploy.{CatsEffectSuite, Config}
import io.circe.parser.decode

class DeployTargetCodecSuite extends CatsEffectSuite {
  test("decode existing portainer-only target config") {
    val json =
      """{
        |  "id": "portainer",
        |  "secret": "s3cr3t",
        |  "portainer": {
        |    "url": "http://portainer.local",
        |    "username": "admin",
        |    "password": "pw"
        |  }
        |}""".stripMargin

    val decoded = decode[DeployTarget](json)
    assert(decoded.isRight)
    assertEquals(decoded.toOption.flatMap(_.kubernetes), None)
  }

  test("decode kubernetes target config") {
    val json =
      """{
        |  "id": "k8s",
        |  "secret": "s3cr3t",
        |  "kubernetes": {
        |    "url": "https://kubernetes.default.svc",
        |    "token": "token-1",
        |    "serviceAccountTokenFile": "/var/run/secrets/kubernetes.io/serviceaccount/token",
        |    "defaultNamespace": "apps"
        |  }
        |}""".stripMargin

    val decoded = decode[DeployTarget](json)
    assert(decoded.isRight)
    assertEquals(decoded.toOption.flatMap(_.kubernetes).flatMap(_.defaultNamespace), Some("apps"))
  }

  test("decode config awaitStatus defaults") {
    val json =
      """{
        |  "awaitStatus": {
        |    "timeoutSeconds": 180,
        |    "pollIntervalMillis": 2000
        |  },
        |  "targets": []
        |}""".stripMargin

    assertEquals(
      decode[Config](json),
      Right(Config(
        targets = Seq.empty,
        awaitStatus = Some(Config.AwaitStatus(timeoutSeconds = Some(180L), pollIntervalMillis = Some(2000L))),
      )),
    )
  }
}
