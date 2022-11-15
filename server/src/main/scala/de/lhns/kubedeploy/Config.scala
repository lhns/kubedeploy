package de.lhns.kubedeploy

import cats.data.OptionT
import cats.effect.Sync
import cats.effect.std.Env
import de.lhns.kubedeploy.model.DeployTarget
import io.circe.Codec
import io.circe.generic.semiauto._

case class Config(targets: Seq[DeployTarget])

object Config {
  implicit val codec: Codec[Config] = deriveCodec

  def fromEnv[F[_] : Sync]: F[Config] =
    OptionT(Env.make[F].get("CONFIG"))
      .toRight(new IllegalArgumentException("Missing environment variable: CONFIG"))
      .subflatMap(io.circe.config.parser.decode[Config](_))
      .rethrowT
}
