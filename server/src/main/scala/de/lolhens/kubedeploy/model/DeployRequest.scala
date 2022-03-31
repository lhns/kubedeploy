package de.lolhens.kubedeploy.model

import cats.syntax.functor._
import de.lolhens.kubedeploy.model.DeployRequest.Locator
import de.lolhens.kubedeploy.model.DeployTarget.DeployTargetId
import io.circe.generic.semiauto._
import io.circe.{Codec, Decoder, Encoder, Json}

case class DeployRequest(
                          target: DeployTargetId,
                          resource: String,
                          value: String,
                          locator: Option[Locator],
                          awaitStatus: Option[Boolean],
                        ) {
  def locatorOrDefault: Locator = locator.getOrElse(Locator.Version)

  def awaitStatusOrDefault: Boolean = awaitStatus.getOrElse(false)
}

object DeployRequest {
  implicit val codec: Codec[DeployRequest] = deriveCodec

  sealed trait Locator

  object Locator {
    case object Version extends Locator {
      implicit val codec: Codec[Version.type] = Codec.from(
        Decoder.decodeString.emap {
          case "version" => Right(Version)
          case _ => Left("not a version locator")
        },
        Encoder.instance(_ => Json.fromString("version"))
      )
    }

    case class Regex(regex: String) extends Locator

    object Regex {
      implicit val codec: Codec[Regex] = deriveCodec
    }

    implicit val codec: Codec[Locator] = Codec.from(
      Decoder[Version.type].widen[Locator]
        .or(Decoder[Regex].widen[Locator]),
      Encoder.instance {
        case Version => Encoder[Version.type].apply(Version)
        case regex: Regex => Encoder[Regex].apply(regex)
      }
    )
  }
}
