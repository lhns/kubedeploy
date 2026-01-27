package de.lhns.kubedeploy

import cats.effect._
import cats.effect.std.Env
import com.comcast.ip4s._
import com.github.markusbernhardt.proxy.ProxySearch
import de.lhns.kubedeploy.deploy.{DeployBackend, GitDeployBackend, PortainerDeployBackend}
import de.lhns.kubedeploy.model.DeployTarget
import de.lhns.kubedeploy.model.DeployTarget.DeployTargetId
import de.lhns.kubedeploy.route.KubedeployRoutes
import de.lhns.trustmanager.TrustManagers._
import io.circe.syntax._
import org.http4s.HttpApp
import org.http4s.client.Client
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.jdkhttpclient.JdkHttpClient
import org.http4s.server.Server
import org.http4s.server.middleware.ErrorAction
import org.log4s.getLogger

import java.net.ProxySelector
import scala.concurrent.duration._
import scala.util.chaining._

object Main extends IOApp {
  private[this] val logger = getLogger

  override def run(args: List[String]): IO[ExitCode] = {
    ProxySelector.setDefault(
      Option(new ProxySearch().tap { s =>
        s.addStrategy(ProxySearch.Strategy.JAVA)
        s.addStrategy(ProxySearch.Strategy.ENV_VAR)
      }.getProxySelector)
        .getOrElse(ProxySelector.getDefault)
    )

    setDefaultTrustManager(jreTrustManagerWithEnvVar)

    applicationResource.use(_ => IO.never)
  }

  def applicationResource: Resource[IO, Unit] =
    for {
      config <- Resource.eval(Config.fromEnv(Env.make[IO]))
      _ = logger.info(s"CONFIG: ${config.asJson.spaces2}")
      client <- JdkHttpClient.simple[IO]
      backends = loadBackends(config, client)
      routes = new KubedeployRoutes(backends)
      _ <- serverResource(
        SocketAddress(host"0.0.0.0", port"8080"),
        routes.toRoutes.orNotFound
      )
    } yield ()

  def loadBackends(config: Config, client: Client[IO]): Map[DeployTargetId, DeployBackend] = config.targets.map {
    case target@DeployTarget(id, _, None, Some(portainer)) =>
      id -> new PortainerDeployBackend(target, portainer, client)

    case target@DeployTarget(id, _, Some(git), None) =>
      id -> new GitDeployBackend(target, git)

    case target =>
      throw new RuntimeException("invalid target: " + target.id)
  }.toMap

  def serverResource[F[_] : Async](socketAddress: SocketAddress[Host], http: HttpApp[F]): Resource[F, Server] =
    EmberServerBuilder.default[F]
      .withHost(socketAddress.host)
      .withPort(socketAddress.port)
      .withHttpApp(ErrorAction.log(
        http = http,
        messageFailureLogAction = (t, msg) => Async[F].delay(logger.debug(t)(msg)),
        serviceErrorLogAction = (t, msg) => Async[F].delay(logger.error(t)(msg))
      ))
      .withShutdownTimeout(1.second)
      .build
}
