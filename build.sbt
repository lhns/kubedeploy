ThisBuild / scalaVersion := "2.13.12"
ThisBuild / name := (server / name).value
name := (ThisBuild / name).value

val V = new {
  val betterMonadicFor = "0.3.1"
  val cats = "2.10.0"
  val catsEffect = "3.5.3"
  val circe = "0.14.6"
  val circeConfig = "0.10.0"
  val circeYaml = "0.15.1"
  val http4s = "0.23.25"
  val http4sErrors = "0.5.1"
  val http4sJdkHttpClient = "0.9.1"
  val jgit = "6.8.0.202311291450-r"
  val logbackClassic = "1.4.14"
  val munit = "0.7.29"
  val munitTaglessFinal = "0.2.0"
  val proxyVole = "1.1.2"
  val trustmanagerUtils = "1.0.0"
}

lazy val commonSettings: Seq[Setting[_]] = Seq(
  version := {
    val Tag = "refs/tags/v?([0-9]+(?:\\.[0-9]+)+(?:[+-].*)?)".r
    sys.env.get("CI_VERSION").collect { case Tag(tag) => tag }
      .getOrElse("0.0.1-SNAPSHOT")
  },
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % V.betterMonadicFor),
  libraryDependencies ++= Seq(
    "ch.qos.logback" % "logback-classic" % V.logbackClassic % Test,
    "de.lolhens" %% "munit-tagless-final" % V.munitTaglessFinal % Test,
    "org.scalameta" %% "munit" % V.munit % Test,
  ),
  testFrameworks += new TestFramework("munit.Framework"),
  assembly / assemblyJarName := s"${name.value}-${version.value}.sh.bat",
  assembly / assemblyOption := (assembly / assemblyOption).value
    .withPrependShellScript(Some(AssemblyPlugin.defaultUniversalScript(shebang = false))),
  assembly / assemblyMergeStrategy := {
    case PathList(paths@_*) if paths.last == "module-info.class" => MergeStrategy.discard
    case x =>
      val oldStrategy = (assembly / assemblyMergeStrategy).value
      oldStrategy(x)
  },
)

lazy val root = project.in(file("."))
  .settings(
    publishArtifact := false
  )
  .aggregate(server)

lazy val server = project
  .enablePlugins(BuildInfoPlugin)
  .settings(commonSettings)
  .settings(
    name := "kubedeploy",

    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % V.logbackClassic,
      "com.hunorkovacs" %% "circe-config" % V.circeConfig,
      "de.lhns" %% "http4s-errors" % V.http4sErrors,
      "de.lhns" %% "scala-trustmanager-utils" % V.trustmanagerUtils,
      "io.circe" %% "circe-core" % V.circe,
      "io.circe" %% "circe-generic" % V.circe,
      "io.circe" %% "circe-parser" % V.circe,
      "io.circe" %% "circe-yaml" % V.circeYaml,
      "org.bidib.com.github.markusbernhardt" % "proxy-vole" % V.proxyVole,
      "org.eclipse.jgit" % "org.eclipse.jgit" % V.jgit,
      "org.http4s" %% "http4s-ember-server" % V.http4s,
      "org.http4s" %% "http4s-circe" % V.http4s,
      "org.http4s" %% "http4s-dsl" % V.http4s,
      "org.http4s" %% "http4s-jdk-http-client" % V.http4sJdkHttpClient,
      "org.typelevel" %% "cats-core" % V.cats,
      "org.typelevel" %% "cats-effect" % V.catsEffect,
    ),
  )
