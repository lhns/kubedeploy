package de.lhns.kubedeploy.model

import cats.kernel.Semigroup
import de.lhns.kubedeploy.model.DeployResult.{DeployFailure, DeploySuccess}
import io.circe.generic.semiauto._
import io.circe.{Codec, Decoder, Encoder}

sealed trait DeployResult {
  def toEither: Either[DeployFailure, DeploySuccess]
}

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
            DeploySuccess(awaitedStatus = true)

          case _ =>
            DeploySuccess(awaitedStatus = false)
        }
      },
      codec.contramap {
        case DeploySuccess(validated) =>
          DeployResultSurrogate(
            status = if (validated) Status.Current else Status.Unknown,
            errorMessage = None,
          )
        case DeployFailure(message, notFound, conflict) =>
          DeployResultSurrogate(
            status = if (notFound) Status.NotFound else if (conflict) Status.Conflict else Status.Failed,
            errorMessage = Some(message),
          )
      }
    )
  }

  implicit val semigroup: Semigroup[DeployResult] = Semigroup.instance {
    case (DeployFailure(_, false, _), b@DeployFailure(_, true, _)) => b
    case (a: DeployFailure, _: DeployFailure) => a
    case (a: DeployFailure, _) => a
    case (_, b: DeployFailure) => b
    case (DeploySuccess(aAwaitedStatus), DeploySuccess(bAwaitedStatus)) =>
      DeploySuccess(aAwaitedStatus && bAwaitedStatus)
  }

  case class DeploySuccess(awaitedStatus: Boolean) extends DeployResult {
    override def toEither: Either[DeployFailure, DeploySuccess] = Right(this)
  }

  case class DeployFailure(message: String, notFound: Boolean = false, conflict: Boolean = false) extends DeployResult {
    override def toEither: Either[DeployFailure, DeploySuccess] = Left(this)
  }

  // https://github.com/kubernetes-sigs/cli-utils/blob/master/pkg/kstatus/README.md#statuses
  sealed abstract class Status(val string: String, val failure: Boolean)

  object Status {
    case object InProgress extends Status("InProgress", failure = false)

    case object Failed extends Status("Failed", failure = true)

    case object Current extends Status("Current", failure = false)

    case object NotFound extends Status("NotFound", failure = true)

    case object Unknown extends Status("Unknown", failure = false)

    case object Conflict extends Status("Conflict", failure = true)

    val values: Seq[Status] = Seq(InProgress, Failed, Current, NotFound, Unknown, Conflict)

    implicit val codec: Codec[Status] = {
      val map = values.map(e => e.string -> e).toMap
      Codec.from(
        Decoder.decodeString.emap(e => map.get(e).toRight(s"unknown status: $e")),
        Encoder.encodeString.contramap(_.string)
      )
    }
  }
}
