package de.lolhens.kubedeploy.model

import de.lolhens.kubedeploy.Secret
import de.lolhens.kubedeploy.model.DeployTarget.{DeployTargetId, PortainerDeployTarget}
import io.circe.generic.semiauto._
import io.circe.{Codec, Decoder, Encoder}
import org.http4s.Uri

case class DeployTarget(
                         id: DeployTargetId,
                         secret: Secret[String],
                         portainer: Option[PortainerDeployTarget],
                       )

object DeployTarget {
  implicit val codec: Codec[DeployTarget] = deriveCodec

  case class DeployTargetId(id: String)

  object DeployTargetId {
    implicit val codec: Codec[DeployTargetId] = Codec.from(
      Decoder.decodeString.map(DeployTargetId(_)),
      Encoder.encodeString.contramap(_.id)
    )
  }

  case class PortainerDeployTarget(
                                    url: Uri,
                                    username: String,
                                    password: Secret[String],
                                  )

  object PortainerDeployTarget {
    private implicit val uriCodec: Codec[Uri] = Codec.from(
      Decoder.decodeString.map(Uri.unsafeFromString),
      Encoder.encodeString.contramap(_.renderString)
    )

    implicit val codec: Codec[PortainerDeployTarget] = deriveCodec
  }
}
