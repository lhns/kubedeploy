package de.lhns.kubedeploy.model

import io.circe.generic.semiauto._
import io.circe.{Codec, Decoder, Encoder}

case class Deploy(
                   resource: String,
                   namespace: Option[String],
                   actions: Seq[DeployAction],
                 ) {
  def withActions(actions: Seq[DeployAction]): Deploy =
    copy(actions = actions)
}

object Deploy {
  implicit val codec: Codec[Deploy] = deriveCodec

  case class Deploys(deployRequests: Seq[Deploy])

  object Deploys {
    implicit val codec: Codec[Deploys] = Codec.from(
      Decoder[Seq[Deploy]].or(Decoder[Deploy].map(Seq(_))).map(Deploys(_)),
      Encoder[Seq[Deploy]].contramap(_.deployRequests)
    )
  }
}
