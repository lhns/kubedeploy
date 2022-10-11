package de.lolhens.kubedeploy

import de.lolhens.kubedeploy.model.DeployTarget
import io.circe.Codec
import io.circe.generic.semiauto._

case class Config(targets: Seq[DeployTarget])

object Config {
  implicit val codec: Codec[Config] = deriveCodec

  lazy val fromEnv: Config =
    Option(System.getenv("CONFIG"))
      .toRight(new IllegalArgumentException("Missing variable: CONFIG"))
      .flatMap(io.circe.config.parser.decode[Config](_))
      .toTry.get
}
