package de.lhns.kubedeploy

import cats.data.OptionT
import cats.effect.Sync
import cats.effect.std.Env
import de.lhns.kubedeploy.model.DeployAction.AwaitStatusAction
import de.lhns.kubedeploy.model.DeployTarget
import io.circe.Codec
import io.circe.generic.semiauto._

import scala.concurrent.duration._

case class Config(
                   targets: Seq[DeployTarget],
                   awaitStatus: Option[Config.AwaitStatus],
                 )

object Config {
  implicit val codec: Codec[Config] = deriveCodec

  case class AwaitStatus(timeoutSeconds: Option[Long], pollIntervalMillis: Option[Long])

  object AwaitStatus {
    val defaultTimeoutSeconds: Long = 120L
    val defaultPollIntervalMillis: Long = 1000L

    implicit val codec: Codec[AwaitStatus] = deriveCodec
  }

  case class ResolvedAwaitStatus(timeout: FiniteDuration, pollInterval: FiniteDuration)

  def resolveAwaitStatus(action: Option[AwaitStatusAction], config: Option[AwaitStatus]): ResolvedAwaitStatus = {
    val timeoutSeconds = action.flatMap(_.timeoutSeconds)
      .orElse(config.flatMap(_.timeoutSeconds))
      .getOrElse(AwaitStatus.defaultTimeoutSeconds)

    val pollIntervalMillis = action.flatMap(_.pollIntervalMillis)
      .orElse(config.flatMap(_.pollIntervalMillis))
      .getOrElse(AwaitStatus.defaultPollIntervalMillis)

    ResolvedAwaitStatus(
      timeout = timeoutSeconds.seconds,
      pollInterval = pollIntervalMillis.millis,
    )
  }

  def fromEnv[F[_] : Sync](env: Env[F]): F[Config] =
    OptionT(env.get("CONFIG"))
      .toRight(new IllegalArgumentException("Missing environment variable: CONFIG"))
      .subflatMap(io.circe.config.parser.decode[Config](_))
      .rethrowT
}
