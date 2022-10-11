package de.lhns.kubedeploy

import io.circe.{Decoder, Encoder, Json}

case class Secret[A](value: A)

object Secret {
  implicit def decoder[A: Decoder]: Decoder[Secret[A]] = Decoder[A].map(Secret(_))

  implicit def encoder[A: Encoder]: Encoder[Secret[A]] = Encoder.instance(_ => Json.fromString("****"))
}
