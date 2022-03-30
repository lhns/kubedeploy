package de.lolhens.kubedeploy

import cats.effect._
import com.github.markusbernhardt.proxy.ProxySearch
import de.lolhens.kubedeploy.model.DeployTarget
import de.lolhens.kubedeploy.repo.DeployTargetRepo
import de.lolhens.kubedeploy.route.KubedeployRoutes
import io.circe.Codec
import io.circe.generic.semiauto._
import io.circe.syntax._
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.implicits._
import org.http4s.jdkhttpclient.JdkHttpClient
import org.log4s.getLogger

import java.net.ProxySelector
import scala.util.chaining._

object Server extends IOApp {
  private[this] val logger = getLogger

  case class Config(targets: Seq[DeployTarget])

  object Config {
    implicit val codec: Codec[Config] = deriveCodec
  }

  override def run(args: List[String]): IO[ExitCode] = {
    ProxySelector.setDefault(
      Option(new ProxySearch().tap { s =>
        s.addStrategy(ProxySearch.Strategy.JAVA)
        s.addStrategy(ProxySearch.Strategy.ENV_VAR)
      }.getProxySelector)
        .getOrElse(ProxySelector.getDefault)
    )

    val config = io.circe.parser.decode[Config](
      Option(System.getenv("CONFIG"))
        .getOrElse(throw new IllegalArgumentException("Missing variable: CONFIG"))
    ).toTry.get

    logger.info(s"CONFIG:\n${config.asJson.spaces2}\n")

    applicationResource(config).use(_ => IO.never)
  }

  private def applicationResource(config: Config): Resource[IO, Unit] =
    for {
      client <- JdkHttpClient.simple[IO]
      deployTargetRepo = DeployTargetRepo.fromSeq(config.targets)
      routes = new KubedeployRoutes(client, deployTargetRepo)
      _ <- BlazeServerBuilder[IO]
        .bindHttp(8080, "0.0.0.0")
        .withHttpApp(
          routes.toRoutes.orNotFound
        )
        .resource
    } yield ()
}
