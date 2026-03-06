package de.lhns.kubedeploy

import cats.effect.IO
import de.lhns.kubedeploy.deploy.{DeployBackend, GitDeployBackend, KubernetesDeployBackend, PortainerDeployBackend}
import de.lhns.kubedeploy.model.DeployTarget
import de.lhns.kubedeploy.model.DeployTarget.{DeployTargetId, GitDeployTarget, KubernetesDeployTarget, PortainerDeployTarget}
import org.http4s.Uri
import org.http4s.client.Client

class MainLoadBackendsSuite extends CatsEffectSuite {
  private val client: Client[IO] = Client.fromHttpApp(org.http4s.HttpApp.notFound)

  test("load all backend types") {
    val config = Config(
      targets = Seq(
        DeployTarget(
          id = DeployTargetId("portainer"),
          secret = Secret("secret-1"),
          git = None,
          portainer = Some(PortainerDeployTarget(
            url = Uri.unsafeFromString("http://portainer"),
            username = "user",
            password = Secret("pass"),
          )),
          kubernetes = None,
        ),
        DeployTarget(
          id = DeployTargetId("git"),
          secret = Secret("secret-2"),
          git = Some(GitDeployTarget(
            url = Uri.unsafeFromString("https://github.com/org/repo.git"),
            username = "git-user",
            password = Secret("git-pass"),
            branch = Some("main"),
            committer = GitDeployTarget.Committer("test", "test@example.com"),
          )),
          portainer = None,
          kubernetes = None,
        ),
        DeployTarget(
          id = DeployTargetId("kubernetes"),
          secret = Secret("secret-3"),
          git = None,
          portainer = None,
          kubernetes = Some(KubernetesDeployTarget(
            url = Some(Uri.unsafeFromString("https://kubernetes.default.svc")),
            token = Some(Secret("k8s-token")),
            serviceAccountTokenFile = None,
            defaultNamespace = Some("default"),
          )),
        ),
      ),
      awaitStatus = Some(Config.AwaitStatus(timeoutSeconds = Some(120L), pollIntervalMillis = Some(500L))),
    )

    val backends: Map[DeployTargetId, DeployBackend] = Main.loadBackends(config, client)

    assert(backends(DeployTargetId("portainer")).isInstanceOf[PortainerDeployBackend])
    assert(backends(DeployTargetId("git")).isInstanceOf[GitDeployBackend])
    assert(backends(DeployTargetId("kubernetes")).isInstanceOf[KubernetesDeployBackend])
  }

  test("reject invalid multi-backend target") {
    val config = Config(
      targets = Seq(
        DeployTarget(
          id = DeployTargetId("invalid"),
          secret = Secret("secret"),
          git = Some(GitDeployTarget(
            url = Uri.unsafeFromString("https://github.com/org/repo.git"),
            username = "git-user",
            password = Secret("git-pass"),
            branch = Some("main"),
            committer = GitDeployTarget.Committer("test", "test@example.com"),
          )),
          portainer = Some(PortainerDeployTarget(
            url = Uri.unsafeFromString("http://portainer"),
            username = "user",
            password = Secret("pass"),
          )),
          kubernetes = None,
        )
      ),
      awaitStatus = None,
    )

    val error = intercept[RuntimeException] {
      Main.loadBackends(config, client)
    }
    assert(error.getMessage.contains("invalid target"))
  }
}
