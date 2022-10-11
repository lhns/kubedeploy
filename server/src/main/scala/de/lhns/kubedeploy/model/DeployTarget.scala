package de.lhns.kubedeploy.model

import de.lhns.kubedeploy.Secret
import de.lhns.kubedeploy.model.DeployTarget.GitDeployTarget.Committer
import de.lhns.kubedeploy.model.DeployTarget.{DeployTargetId, GitDeployTarget, PortainerDeployTarget}
import io.circe.generic.semiauto._
import io.circe.{Codec, Decoder, Encoder}
import org.http4s.Uri

case class DeployTarget(
                         id: DeployTargetId,
                         secret: Secret[String],
                         git: Option[GitDeployTarget],
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

  private implicit val uriCodec: Codec[Uri] = Codec.from(
    Decoder.decodeString.map(Uri.unsafeFromString),
    Encoder.encodeString.contramap(_.renderString)
  )

  case class PortainerDeployTarget(
                                    url: Uri,
                                    username: String,
                                    password: Secret[String],
                                  )

  object PortainerDeployTarget {
    implicit val codec: Codec[PortainerDeployTarget] = deriveCodec
  }

  case class GitDeployTarget(
                              url: Uri,
                              username: String,
                              password: Secret[String],
                              branch: Option[String],
                              committer: Committer,
                            )

  object GitDeployTarget {
    implicit val codec: Codec[GitDeployTarget] = deriveCodec

    case class Committer(name: String, email: String)

    object Committer {
      implicit val codec: Codec[Committer] = deriveCodec
    }
  }
}
