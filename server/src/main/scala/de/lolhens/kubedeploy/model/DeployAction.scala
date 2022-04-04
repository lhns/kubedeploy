package de.lolhens.kubedeploy.model

import cats.syntax.functor._
import io.circe._
import io.circe.generic.semiauto.deriveCodec
import io.circe.yaml.syntax._

sealed trait DeployAction {
  def encode: Json
}

object DeployAction {
  implicit val codec: Codec[DeployAction] = Codec.from(
    Decoder[AwaitStatusAction.type].widen[DeployAction]
      .or(Decoder[ImageAction].widen[DeployAction])
      .or(Decoder[EnvAction].widen[DeployAction])
      .or(Decoder[JsonAction].widen[DeployAction])
      .or(Decoder[RegexAction].widen[DeployAction])
      .or(Decoder[YamlAction].widen[DeployAction]),
    Encoder.instance(_.encode)
  )

  object AwaitStatusAction extends DeployAction {
    implicit val codec: Codec[AwaitStatusAction.type] = Codec.from(
      Decoder.decodeString.emap {
        case "awaitStatus" => Right(AwaitStatusAction)
        case _ => Left("invalid value")
      },
      Encoder.instance(_ => Json.fromString("awaitStatus"))
    )

    override def encode: Json = AwaitStatusAction.codec(this)
  }

  case class ImageAction(image: String) extends DeployAction {
    override def encode: Json = ImageAction.codec(this)
  }

  object ImageAction {
    implicit val codec: Codec[ImageAction] = deriveCodec
  }

  case class EnvAction(env: Map[String, String]) extends DeployAction {
    override def encode: Json = EnvAction.codec(this)

    def transform(obj: Map[String, String]): Map[String, String] =
      obj ++ env
  }

  object EnvAction {
    implicit val codec: Codec[EnvAction] = deriveCodec
  }

  case class RegexAction(regex: RegexAction.Params) extends DeployAction {
    override def encode: Json = RegexAction.codec(this)

    def transform(string: String): String =
      if (regex.allOrDefault)
        string.replaceAll(regex.find, regex.replace)
      else
        string.replaceFirst(regex.find, regex.replace)
  }

  object RegexAction {
    implicit val codec: Codec[RegexAction] = deriveCodec

    case class Params(find: String, replace: String, all: Option[Boolean]) {
      def allOrDefault: Boolean = all.getOrElse(false)
    }

    object Params {
      implicit val codec: Codec[Params] = deriveCodec
    }
  }

  case class JsonAction(json: JsonAction.Params) extends DeployAction {
    private def params = json

    override def encode: Json = JsonAction.codec(this)

    def transform(json: Json): Json =
      params.path.foldLeft[ACursor](json.hcursor) { (hcursor, e) =>
        if (hcursor.focus.exists(_.isArray)) hcursor.downN(e.toInt)
        else hcursor.downField(e)
      }
        .withFocus(_ => params.value)
        .root.value

    def transform(jsonString: String): String =
      transform(io.circe.parser.parse(jsonString).toTry.get)
        .spaces2
  }

  object JsonAction {
    implicit val codec: Codec[JsonAction] = deriveCodec

    case class Params(path: Seq[String], value: Json)

    object Params {
      implicit val codec: Codec[Params] = deriveCodec
    }
  }

  case class YamlAction(yaml: JsonAction.Params) extends DeployAction {
    private def params = yaml

    override def encode: Json = YamlAction.codec(this)

    def transform(yamlString: String): String =
      JsonAction(params).transform(io.circe.yaml.parser.parse(yamlString).toTry.get)
        .asYaml.spaces4
  }

  object YamlAction {
    implicit val codec: Codec[YamlAction] = deriveCodec
  }
}
