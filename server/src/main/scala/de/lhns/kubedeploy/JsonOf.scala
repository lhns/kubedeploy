package de.lhns.kubedeploy

import cats.effect.Concurrent
import io.circe.{Decoder, Encoder}
import org.http4s.circe._
import org.http4s.{EntityDecoder, EntityEncoder}

case class JsonOf[A](value: A)

object JsonOf {
  implicit def entityDecoder[F[_] : Concurrent, A](implicit decoder: Decoder[A]): EntityDecoder[F, JsonOf[A]] =
    jsonOf[F, A].map(JsonOf(_))

  implicit def entityEncoder[F[_], A](implicit encoder: Encoder[A]): EntityEncoder[F, JsonOf[A]] =
    jsonEncoderOf[F, A].contramap(_.value)
}