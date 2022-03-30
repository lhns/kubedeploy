ThisBuild / scalaVersion := "2.13.8"
ThisBuild / name := (server / name).value
name := (ThisBuild / name).value

lazy val commonSettings: Seq[Setting[_]] = Seq(
  version := {
    val Tag = "v?([0-9]+(?:\\.[0-9]+)+(?:[+-].*)?)".r
    sys.env.get("CI_COMMIT_TAG").collect { case Tag(tag) => tag }
      .getOrElse("0.0.1-SNAPSHOT")
  },

  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),

  libraryDependencies ++= Seq(
    "ch.qos.logback" % "logback-classic" % "1.2.8" % Test,
    "de.lolhens" %% "munit-tagless-final" % "0.2.0" % Test,
    "org.scalameta" %% "munit" % "0.7.29" % Test,
  ),

  testFrameworks += new TestFramework("munit.Framework"),

  assembly / assemblyJarName := s"${name.value}-${version.value}.sh.bat",

  assembly / assemblyOption := (assembly / assemblyOption).value
    .withPrependShellScript(Some(AssemblyPlugin.defaultUniversalScript(shebang = false))),

  assembly / assemblyMergeStrategy := {
    case PathList(paths@_*) if paths.last == "module-info.class" => MergeStrategy.discard
    case PathList("META-INF", "jpms.args") => MergeStrategy.discard
    case x =>
      val oldStrategy = (assembly / assemblyMergeStrategy).value
      oldStrategy(x)
  },
)

val V = new {
  val circe = "0.14.1"
  val http4s = "0.23.11"
}

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
      "ch.qos.logback" % "logback-classic" % "1.2.11",
      "io.circe" %% "circe-core" % V.circe,
      "io.circe" %% "circe-generic" % V.circe,
      "io.circe" %% "circe-parser" % V.circe,
      "org.bidib.com.github.markusbernhardt" % "proxy-vole" % "1.0.16",
      "org.http4s" %% "http4s-blaze-server" % V.http4s,
      "org.http4s" %% "http4s-circe" % V.http4s,
      "org.http4s" %% "http4s-dsl" % V.http4s,
      "org.http4s" %% "http4s-jdk-http-client" % "0.7.0",
      "org.typelevel" %% "cats-core" % "2.7.0",
      "org.typelevel" %% "cats-effect" % "3.3.9",
    ),
  )
