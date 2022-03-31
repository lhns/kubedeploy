package de.lolhens.kubedeploy.model

import io.circe.generic.semiauto._
import io.circe.{Codec, Decoder, Encoder}

sealed trait DeployResult

object DeployResult {
  implicit val codec: Codec[DeployResult] = {
    case class DeployResultSurrogate(
                                      status: Status,
                                      errorMessage: Option[String],
                                    )

    val codec = deriveCodec[DeployResultSurrogate]
    Codec.from(
      codec.map { surrogate =>
        surrogate.status match {
          case Status.NotFound =>
            DeployFailure(surrogate.errorMessage.getOrElse(""), notFound = true)

          case status if status.failure =>
            DeployFailure(surrogate.errorMessage.getOrElse(""))

          case Status.Current =>
            DeploySuccess(validated = true)

          case _ =>
            DeploySuccess(validated = false)
        }
      },
      codec.contramap {
        case DeploySuccess(validated) =>
          DeployResultSurrogate(
            status = if (validated) Status.Current else Status.Unknown,
            errorMessage = None,
          )
        case DeployFailure(message, notFound) =>
          DeployResultSurrogate(
            status = if (notFound) Status.NotFound else Status.Failed,
            errorMessage = Some(message),
          )
      }
    )
  }

  case class DeploySuccess(validated: Boolean) extends DeployResult

  case class DeployFailure(message: String, notFound: Boolean = false) extends DeployResult

  // https://github.com/kubernetes-sigs/cli-utils/blob/master/pkg/kstatus/README.md#statuses
  sealed abstract class Status(val string: String, val failure: Boolean)

  object Status {
    case object InProgress extends Status("InProgress", failure = false)

    case object Failed extends Status("Failed", failure = true)

    case object Current extends Status("Current", failure = false)

    case object NotFound extends Status("NotFound", failure = true)

    case object Unknown extends Status("Unknown", failure = false)

    val values: Seq[Status] = Seq(InProgress, Failed, Current, NotFound, Unknown)

    implicit val codec: Codec[Status] = {
      val map = values.map(e => e.string -> e).toMap
      Codec.from(
        Decoder.decodeString.emap(e => map.get(e).toRight(s"unknown status: $e")),
        Encoder.encodeString.contramap(_.string)
      )
    }
  }
}
