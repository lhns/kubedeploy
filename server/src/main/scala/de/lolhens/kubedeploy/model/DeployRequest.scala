package de.lolhens.kubedeploy.model

import io.circe.Codec
import io.circe.generic.semiauto._

case class DeployRequest(
                          resource: String,
                          value: String,
                          locator: Option[String]
                        )

object DeployRequest {
  implicit val codec: Codec[DeployRequest] = deriveCodec
}
