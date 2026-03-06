package de.lhns.kubedeploy.deploy

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all._
import de.lhns.kubedeploy.Config
import de.lhns.kubedeploy.model.DeployAction.{ApplyYamlAction, AwaitStatusAction, EnvAction, ImageAction, JsonAction, RegexAction, YamlAction}
import de.lhns.kubedeploy.model.DeployResult.{DeployFailure, DeploySuccess}
import de.lhns.kubedeploy.model.DeployTarget.KubernetesDeployTarget
import de.lhns.kubedeploy.model.{Deploy, DeployAction, DeployTarget}
import io.circe.{ACursor, Json, JsonObject}
import io.circe.yaml.syntax._
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Header, Headers, Method, Request, Status, Uri}
import org.log4s.getLogger
import org.typelevel.ci._

import java.nio.file.{Files, Paths}
import scala.concurrent.duration._

class KubernetesDeployBackend(
                               val target: DeployTarget,
                               kubernetes: KubernetesDeployTarget,
                               client: Client[IO],
                               awaitStatus: Option[Config.AwaitStatus],
                             ) extends DeployBackend with ConsolidateActions {
  private val logger = getLogger

  private case class KubernetesAuth(baseUri: Uri, token: String)
  private case class KubeconfigAuth(baseUri: Uri, token: String)
  private case class ResourceRef(apiVersion: String, kind: String, name: String, namespace: Option[String])

  override def deploy(request: Deploy): EitherT[IO, DeployFailure, DeploySuccess] = {
    val awaitAction = request.actions.collectFirst { case a: AwaitStatusAction => a }
    val awaitConfig = awaitAction.map(action => Config.resolveAwaitStatus(Some(action), awaitStatus))

    (for {
      auth <- EitherT(resolveAuth)
      resources <- applyOrPatch(request, auth)
      _ <- awaitConfig match {
        case Some(config) =>
          awaitResources(auth, resources, config)
        case None =>
          EitherT.rightT[IO, DeployFailure](())
      }
    } yield DeploySuccess(awaitedStatus = awaitConfig.nonEmpty))
  }

  private def applyOrPatch(request: Deploy, auth: KubernetesAuth): EitherT[IO, DeployFailure, Seq[ResourceRef]] = {
    val applyYamls = request.actions.collect {
      case ApplyYamlAction(yaml) => yaml
    }

    if (applyYamls.nonEmpty)
      applyYamlDocuments(applyYamls.mkString("\n---\n"), request, auth)
    else
      patchWorkload(request, auth)
  }

  private def patchWorkload(request: Deploy, auth: KubernetesAuth): EitherT[IO, DeployFailure, Seq[ResourceRef]] = {
    val (kind, name) = parseWorkloadResource(request.resource)
    val namespace = request.namespace.orElse(kubernetes.defaultNamespace).orElse(Some("default"))
    val ref = ResourceRef("apps/v1", kind, name, namespace)

    for {
      current <- executeJson(Request[IO](
        method = Method.GET,
        uri = resourceUri(auth.baseUri, ref),
        headers = authHeaders(auth.token),
      ))
      transformed <- EitherT.fromEither[IO](transformWorkloadJson(current, request.actions))
      _ <- executeUnit(Request[IO](
        method = Method.PATCH,
        uri = resourceUri(auth.baseUri, ref),
        headers = authHeaders(auth.token)
          .put(Header.Raw(ci"Content-Type", "application/merge-patch+json")),
      ).withEntity(transformed.noSpaces))
    } yield Seq(ref)
  }

  private def applyYamlDocuments(
                                  yamlContent: String,
                                  request: Deploy,
                                  auth: KubernetesAuth,
                                ): EitherT[IO, DeployFailure, Seq[ResourceRef]] = {
    val defaultNamespace = request.namespace.orElse(kubernetes.defaultNamespace)

    val docs = yamlContent
      .split("(?m)^---\\s*$")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toSeq

    EitherT.fromEither[IO](parseResourceRefs(docs, defaultNamespace)).flatMap { refs =>
      refs.zip(docs).toList.traverse_ {
        case (ref, doc) =>
          executeUnit(Request[IO](
            method = Method.PATCH,
            uri = resourceUri(auth.baseUri, ref) +? ("fieldManager" -> "kubedeploy") +? ("force" -> "true"),
            headers = authHeaders(auth.token)
              .put(Header.Raw(ci"Content-Type", "application/apply-patch+yaml")),
          ).withEntity(doc))
      } *> EitherT.rightT[IO, DeployFailure](refs)
    }
  }

  private def awaitResources(
                              auth: KubernetesAuth,
                              resources: Seq[ResourceRef],
                              awaitConfig: Config.ResolvedAwaitStatus,
                            ): EitherT[IO, DeployFailure, Unit] = {
    resources
      .filter(resource => isAwaitableWorkload(resource.kind))
      .toList
      .traverse_(awaitResource(auth, _, awaitConfig))
  }

  private def awaitResource(
                             auth: KubernetesAuth,
                             ref: ResourceRef,
                             awaitConfig: Config.ResolvedAwaitStatus,
                           ): EitherT[IO, DeployFailure, Unit] = {
    def isReady(json: Json): Boolean = {
      val replicas = getLong(json, "spec", "replicas").getOrElse(1L)

      ref.kind.toLowerCase match {
        case "deployment" =>
          val readyReplicas = getLong(json, "status", "readyReplicas").getOrElse(0L)
          val availableReplicas = getLong(json, "status", "availableReplicas").getOrElse(0L)
          readyReplicas >= replicas && availableReplicas >= replicas
        case "statefulset" =>
          val readyReplicas = getLong(json, "status", "readyReplicas").getOrElse(0L)
          val updatedReplicas = getLong(json, "status", "updatedReplicas").getOrElse(0L)
          readyReplicas >= replicas && updatedReplicas >= replicas
        case _ =>
          true
      }
    }

    EitherT.right(IO.monotonic).flatMap { start =>
      def loop: EitherT[IO, DeployFailure, Unit] =
        executeJson(Request[IO](
          method = Method.GET,
          uri = resourceUri(auth.baseUri, ref),
          headers = authHeaders(auth.token),
        )).flatMap { json =>
          if (isReady(json))
            EitherT.rightT[IO, DeployFailure](())
          else
            EitherT.right(IO.monotonic).flatMap { now =>
              if (now - start >= awaitConfig.timeout)
                EitherT.leftT[IO, Unit](DeployFailure(s"timed out waiting for ${ref.kind}/${ref.name}"))
              else
                EitherT.right(IO.sleep(awaitConfig.pollInterval)) *> loop
            }
        }

      loop
    }
  }

  private def parseWorkloadResource(resource: String): (String, String) =
    resource.toLowerCase match {
      case s"deployment/$name" => "deployment" -> name
      case s"deployments/$name" => "deployment" -> name
      case s"statefulset/$name" => "statefulset" -> name
      case s"statefulsets/$name" => "statefulset" -> name
      case name => "deployment" -> name
    }

  private def parseResourceRefs(yamlDocs: Seq[String], defaultNamespace: Option[String]): Either[DeployFailure, Seq[ResourceRef]] =
    yamlDocs.traverse { doc =>
      for {
        json <- io.circe.yaml.parser.parse(doc)
          .leftMap(error => DeployFailure(s"failed to parse yaml: ${error.getMessage}"))
        apiVersion <- json.hcursor.downField("apiVersion").as[String]
          .leftMap(_ => DeployFailure("yaml resource is missing apiVersion"))
        kind <- json.hcursor.downField("kind").as[String]
          .leftMap(_ => DeployFailure("yaml resource is missing kind"))
        name <- json.hcursor.downField("metadata").downField("name").as[String]
          .leftMap(_ => DeployFailure("yaml resource is missing metadata.name"))
        namespace = json.hcursor.downField("metadata").downField("namespace").as[String].toOption.orElse(defaultNamespace)
      } yield ResourceRef(apiVersion, kind, name, namespace)
    }

  private def transformWorkloadJson(json: Json, actions: Seq[DeployAction]): Either[DeployFailure, Json] =
    actions
      .filter {
        case _: AwaitStatusAction => false
        case _: ApplyYamlAction => false
        case _ => true
      }
      .foldLeft[Either[DeployFailure, Json]](Right(json)) {
        case (acc, action) =>
          acc.flatMap(current =>
            action match {
              case ImageAction(image) =>
                Right(updateContainers(current)(_.add("image", Json.fromString(image))))
              case envAction: EnvAction =>
                Right(updateContainers(current) { container =>
                  val existingEnv = container("env")
                    .flatMap(_.asArray)
                    .toSeq
                    .flatten
                    .flatMap { env =>
                      for {
                        name <- env.hcursor.downField("name").as[String].toOption
                        value <- env.hcursor.downField("value").as[String].toOption
                      } yield name -> value
                    }
                    .toMap

                  val updatedEnv = envAction.transform(existingEnv)
                  container.add(
                    "env",
                    Json.arr(updatedEnv.toSeq.sortBy(_._1).map {
                      case (name, value) =>
                        Json.obj(
                          "name" -> Json.fromString(name),
                          "value" -> Json.fromString(value),
                        )
                    }: _*)
                  )
                })
              case regexAction: RegexAction =>
                io.circe.parser.parse(regexAction.transform(current.noSpaces))
                  .leftMap(error => DeployFailure(s"regex transformation produced invalid json: ${error.getMessage}"))
              case jsonAction: JsonAction =>
                Right(jsonAction.transform(current))
              case yamlAction: YamlAction =>
                io.circe.yaml.parser.parse(yamlAction.transform(current.asYaml.spaces4))
                  .leftMap(error => DeployFailure(s"yaml transformation produced invalid yaml: ${error.getMessage}"))
              case unsupported =>
                logger.warn("unsupported action: " + unsupported)
                Right(current)
            }
          )
      }

  private def updateContainers(json: Json)(f: JsonObject => JsonObject): Json =
    json.hcursor
      .downField("spec")
      .downField("template")
      .downField("spec")
      .downField("containers")
      .withFocus {
        case value if value.isArray =>
          Json.fromValues(value.asArray.getOrElse(Vector.empty).map { jsonValue =>
            jsonValue.asObject
              .map(obj => Json.fromJsonObject(f(obj)))
              .getOrElse(jsonValue)
          })
        case other => other
      }
      .top
      .getOrElse(json)

  private def executeJson(request: Request[IO]): EitherT[IO, DeployFailure, Json] = EitherT {
    client.run(request).use { response =>
      if (response.status.isSuccess)
        response.as[Json].map(Right(_))
      else
        response.bodyText.compile.string.map(body => Left(statusToFailure(response.status, body)))
    }
  }

  private def executeUnit(request: Request[IO]): EitherT[IO, DeployFailure, Unit] = EitherT {
    client.run(request).use { response =>
      if (response.status.isSuccess)
        IO.pure(Right(()))
      else
        response.bodyText.compile.string.map(body => Left(statusToFailure(response.status, body)))
    }
  }

  private def statusToFailure(status: Status, body: String): DeployFailure = {
    val message = Option(body).filter(_.nonEmpty)
      .getOrElse(s"kubernetes api request failed with status ${status.code}")

    status match {
      case Status.NotFound => DeployFailure(message, notFound = true)
      case Status.Conflict => DeployFailure(message, conflict = true)
      case _ => DeployFailure(message)
    }
  }

  private def resolveAuth: IO[Either[DeployFailure, KubernetesAuth]] =
    resolveKubeconfigAuth.flatMap {
      case Left(error) =>
        IO.pure(Left(error))
      case Right(kubeconfigAuth) =>
        (resolveBaseUri(kubeconfigAuth.map(_.baseUri)), resolveToken(kubeconfigAuth.map(_.token))).mapN {
          case (Right(baseUri), Right(token)) => Right(KubernetesAuth(baseUri, token))
          case (Left(error), _) => Left(error)
          case (_, Left(error)) => Left(error)
        }
    }

  private def resolveKubeconfigAuth: IO[Either[DeployFailure, Option[KubeconfigAuth]]] = {
    val defaultKubeconfigPath = Paths.get(sys.props.getOrElse("user.home", "."), ".kube", "config").toString

    val kubeconfigPath = kubernetes.kubeconfigFile
      .map(_ -> true)
      .orElse(sys.env.get("KUBECONFIG").map(_ -> true))
      .orElse(Some(defaultKubeconfigPath -> false))

    kubeconfigPath match {
      case None =>
        IO.pure(Right(None))
      case Some((pathString, strict)) =>
        IO.blocking(Paths.get(pathString)).attempt.flatMap {
          case Left(error) =>
            if (strict)
              IO.pure(Left(DeployFailure(s"invalid kubeconfig path '$pathString': ${error.getMessage}")))
            else
              IO.pure(Right(None))

          case Right(path) =>
            IO.blocking(Files.exists(path)).flatMap {
              case false if strict =>
                IO.pure(Left(DeployFailure(s"kubeconfig file not found: $pathString")))
              case false =>
                IO.pure(Right(None))
              case true =>
                IO.blocking(Files.readString(path)).attempt.map {
                  case Left(error) =>
                    Left(DeployFailure(s"failed to read kubeconfig file '$pathString': ${error.getMessage}"))
                  case Right(content) =>
                    parseKubeconfigAuth(content).map(Some(_))
                }
            }
        }
    }
  }

  private def parseKubeconfigAuth(content: String): Either[DeployFailure, KubeconfigAuth] = {
    def findNamedEntry(entries: Vector[Json], name: String): Option[Json] =
      entries.find(_.hcursor.get[String]("name").toOption.contains(name))

    for {
      kubeconfig <- io.circe.yaml.parser.parse(content)
        .leftMap(error => DeployFailure(s"failed to parse kubeconfig yaml: ${error.getMessage}"))
      currentContextName <- kubeconfig.hcursor.get[String]("current-context")
        .leftMap(_ => DeployFailure("kubeconfig is missing current-context"))

      contexts <- kubeconfig.hcursor.get[Vector[Json]]("contexts")
        .leftMap(_ => DeployFailure("kubeconfig is missing contexts"))
      context <- findNamedEntry(contexts, currentContextName)
        .toRight(DeployFailure(s"kubeconfig context not found: $currentContextName"))

      clusterName <- context.hcursor.downField("context").get[String]("cluster")
        .leftMap(_ => DeployFailure(s"kubeconfig context '$currentContextName' is missing cluster"))
      userName <- context.hcursor.downField("context").get[String]("user")
        .leftMap(_ => DeployFailure(s"kubeconfig context '$currentContextName' is missing user"))

      clusters <- kubeconfig.hcursor.get[Vector[Json]]("clusters")
        .leftMap(_ => DeployFailure("kubeconfig is missing clusters"))
      cluster <- findNamedEntry(clusters, clusterName)
        .toRight(DeployFailure(s"kubeconfig cluster not found: $clusterName"))
      server <- cluster.hcursor.downField("cluster").get[String]("server")
        .leftMap(_ => DeployFailure(s"kubeconfig cluster '$clusterName' is missing server"))
      baseUri <- Uri.fromString(server)
        .leftMap(error => DeployFailure(s"invalid kubeconfig server uri '$server': ${error.sanitized}"))

      users <- kubeconfig.hcursor.get[Vector[Json]]("users")
        .leftMap(_ => DeployFailure("kubeconfig is missing users"))
      user <- findNamedEntry(users, userName)
        .toRight(DeployFailure(s"kubeconfig user not found: $userName"))
      token <- user.hcursor.downField("user").get[String]("token")
        .leftMap(_ => DeployFailure(s"kubeconfig user '$userName' is missing token"))
      trimmedToken = token.trim
      _ <- Either.cond(trimmedToken.nonEmpty, (), DeployFailure(s"kubeconfig user '$userName' token is empty"))
    } yield KubeconfigAuth(baseUri, trimmedToken)
  }

  private def resolveBaseUri(kubeconfigBaseUri: Option[Uri]): IO[Either[DeployFailure, Uri]] = IO {
    kubernetes.url
      .orElse(kubeconfigBaseUri)
      .orElse {
        for {
          host <- sys.env.get("KUBERNETES_SERVICE_HOST")
          port = sys.env.getOrElse("KUBERNETES_SERVICE_PORT", "443")
        } yield Uri.unsafeFromString(s"https://$host:$port")
      }
      .toRight(DeployFailure("missing kubernetes url (config, kubeconfig, or in-cluster endpoint variables)"))
  }

  private def resolveToken(kubeconfigToken: Option[String]): IO[Either[DeployFailure, String]] =
    kubernetes.token.map(_.value)
      .orElse(kubeconfigToken)
      .filter(_.nonEmpty) match {
      case Some(token) =>
        IO.pure(Right(token))
      case None =>
        val tokenFile = kubernetes.serviceAccountTokenFile
          .getOrElse("/var/run/secrets/kubernetes.io/serviceaccount/token")
        IO.blocking(Paths.get(tokenFile)).attempt.flatMap {
          case Left(error) =>
            IO.pure(Left(DeployFailure(s"failed to read kubernetes service account token path: ${error.getMessage}")))
          case Right(path) =>
            IO.blocking {
              if (Files.exists(path)) {
                val token = Files.readString(path).trim
                if (token.nonEmpty)
                  Right(token)
                else
                  Left(DeployFailure(s"kubernetes service account token file is empty: $tokenFile"))
              } else
                Left(DeployFailure("missing kubernetes token (config/kubeconfig) and service account token file"))
            }.handleError(error => Left(DeployFailure(s"failed to read kubernetes token file: ${error.getMessage}")))
        }
    }

  private def authHeaders(token: String): Headers =
    Headers(Authorization(Credentials.Token(AuthScheme.Bearer, token)))

  private def getLong(json: Json, path: String*): Option[Long] =
    path.foldLeft[ACursor](json.hcursor) {
      case (cursor, segment) => cursor.downField(segment)
    }.as[Long].toOption

  private def isAwaitableWorkload(kind: String): Boolean =
    kind.toLowerCase match {
      case "deployment" => true
      case "statefulset" => true
      case _ => false
    }

  private def resourceUri(baseUri: Uri, ref: ResourceRef): Uri = {
    val apiBase = ref.apiVersion.split("/", 2) match {
      case Array(version) =>
        baseUri / "api" / version
      case Array(group, version) =>
        baseUri / "apis" / group / version
    }

    val kindPath = kindToPlural(ref.kind)

    val namespaced = ref.namespace.map { namespace =>
      apiBase / "namespaces" / namespace / kindPath / ref.name
    }

    namespaced.getOrElse(apiBase / kindPath / ref.name)
  }

  private def kindToPlural(kind: String): String =
    kind.toLowerCase match {
      case "deployment" => "deployments"
      case "statefulset" => "statefulsets"
      case other if other.endsWith("s") => other
      case other => other + "s"
    }
}