package de.lolhens.kubedeploy.model

import de.lolhens.kubedeploy.model.DeployTarget.DeployTargetId
import io.circe.Codec
import io.circe.generic.semiauto._

case class DeployRequest(
                          target: DeployTargetId,
                          resource: String,
                          value: String,
                          locator: Option[String],
                          validate: Boolean,
                        )

object DeployRequest {
  implicit val codec: Codec[DeployRequest] = deriveCodec
}
