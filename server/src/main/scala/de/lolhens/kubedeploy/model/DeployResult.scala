package de.lolhens.kubedeploy.model

import io.circe.Codec
import io.circe.generic.semiauto._

sealed trait DeployResult

object DeployResult {
  implicit val codec: Codec[DeployResult] = {
    case class DeployResultSurrogate(
                                      success: Boolean,
                                      errorMessage: Option[String],
                                      validated: Option[Boolean],
                                    )

    val codec = deriveCodec[DeployResultSurrogate]
    Codec.from(
      codec.map { surrogate =>
        if (surrogate.success)
          DeploySuccess(validated = surrogate.validated.getOrElse(false))
        else
          DeployFailure(surrogate.errorMessage.getOrElse(""))
      },
      codec.contramap {
        case DeploySuccess(validated) =>
          DeployResultSurrogate(
            success = true,
            errorMessage = None,
            validated = Some(validated),
          )
        case DeployFailure(message) =>
          DeployResultSurrogate(
            success = false,
            errorMessage = Some(message),
            validated = None,
          )
      }
    )
  }

  case class DeploySuccess(validated: Boolean) extends DeployResult

  case class DeployFailure(message: String) extends DeployResult
}
