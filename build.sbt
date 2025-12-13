ThisBuild / scalaVersion := "2.13.17"
ThisBuild / name := (server / name).value
name := (ThisBuild / name).value

val V = new {
  val betterMonadicFor = "0.3.1"
  val cats = "2.13.0"
  val catsEffect = "3.6.3"
  val circe = "0.14.15"
  val circeConfig = "0.10.0"
  val circeYaml = "0.16.1"
  val http4s = "0.23.33"
  val http4sErrors = "0.5.1"
  val http4sJdkHttpClient = "0.9.2"
  val jgit = "7.5.0.202512021534-r"
  val logbackClassic = "1.5.20"
  val munit = "1.2.1"
  val munitTaglessFinal = "0.2.0"
  val proxyVole = "1.1.7"
  val trustmanagerUtils = "1.1.0"
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
