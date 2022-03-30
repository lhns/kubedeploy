package de.lolhens.kubedeploy

import cats.effect._
import com.github.markusbernhardt.proxy.ProxySearch
import de.bitmarck.fhir.versionproxy.model.VersionRule
import de.bitmarck.fhir.versionproxy.route.VersionProxyRoutes
import de.lolhens.kubedeploy.deploy.PortainerDeploy
import de.lolhens.kubedeploy.route.KubedeployRoutes
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.implicits._
import org.http4s.jdkhttpclient.JdkHttpClient
import org.log4s.getLogger

import java.net.ProxySelector
import scala.util.chaining._

object Server extends IOApp {
  private[this] val logger = getLogger

  override def run(args: List[String]): IO[ExitCode] = {
    ProxySelector.setDefault(
      Option(new ProxySearch().tap { s =>
        s.addStrategy(ProxySearch.Strategy.JAVA)
        s.addStrategy(ProxySearch.Strategy.ENV_VAR)
      }.getProxySelector)
        .getOrElse(ProxySelector.getDefault)
    )

    applicationResource().use(_ => IO.never)
  }

  private def applicationResource(): Resource[IO, Unit] =
    for {
      client <- JdkHttpClient.simple[IO]
      deploy = new PortainerDeploy(client)
      routes = new KubedeployRoutes(deploy)

      _ <- BlazeServerBuilder[IO]
        .bindHttp(8080, "0.0.0.0")
        .withHttpApp(
          routes.toRoutes.orNotFound
        )
        .resource
    } yield ()
}
