lazy val baseName         = "FileCache"
lazy val baseNameL        = baseName.toLowerCase

lazy val projectVersion   = "1.1.2"
lazy val mimaVersion      = "1.1.0"

lazy val deps = new {
  val main = new {
    val serial    = "2.0.1"
    val fileUtil  = "1.1.5"
    val scalaSTM  = "0.11.1"
  }
  val test = new {
    val scalaTest = "3.2.9"
  }
}

ThisBuild / version       := projectVersion
ThisBuild / organization  := "de.sciss"
ThisBuild / versionScheme := Some("pvp")

lazy val commonSettings = Seq(
  scalaVersion       := "2.13.6",
  crossScalaVersions := Seq("3.0.1", "2.13.6", "2.12.14"),
  homepage           := Some(url(s"https://github.com/Sciss/$baseName")),
  licenses           := Seq("LGPL v2.1+" -> url("http://www.gnu.org/licenses/lgpl-2.1.txt")),
  scalacOptions     ++= Seq("-deprecation", "-unchecked", "-feature", "-encoding", "utf8", "-Xlint", "-Xsource:2.13"),
  scalacOptions     ++= {
    // XXX TODO Dotty does not yet support eliding
    // if (isDotty.value) Nil else 
    Seq("-Xelide-below", /*"CONFIG"*/ "INFO")    // elide debug logging!
  },
//  sources in (Compile, doc) := {
//    if (isDotty.value) Nil else (sources in (Compile, doc)).value // bug in dottydoc
//  },
  console / initialCommands := """import de.sciss.filecache._
                                 |import concurrent._
                                 |import java.io.File""".stripMargin,
) ++ publishSettings

lazy val publishSettings = Seq(
  publishMavenStyle := true,
  Test / publishArtifact := false,
  pomIncludeRepository := { _ => false },
  developers := List(
    Developer(
      id    = "sciss",
      name  = "Hanns Holger Rutz",
      email = "contact@sciss.de",
      url   = url("https://www.sciss.de")
    )
  ),
  scmInfo := {
    val h = "github.com"
    val a = s"Sciss/$baseName"
    Some(ScmInfo(url(s"https://$h/$a"), s"scm:git@$h:$a.git"))
  },
)

lazy val root = project.withId("root").in(file("."))
  .aggregate(common, mutable, txn)
  .settings(commonSettings)
  .settings(
    name := baseName,
    packagedArtifacts := Map.empty           // prevent publishing anything!
  )

lazy val common = project.withId(s"$baseNameL-common").in(file("common"))
  .settings(commonSettings)
  .settings(
    name        := s"$baseName-common",
    description := "Common functionality of the FileCache project",
    libraryDependencies ++= Seq(
      "de.sciss" %% "serial"   % deps.main.serial,
      "de.sciss" %% "fileutil" % deps.main.fileUtil
    ),
    mimaPreviousArtifacts := Set("de.sciss" %% s"$baseNameL-common" % mimaVersion)
  )

lazy val testSettings = Seq(
  libraryDependencies += {
    "org.scalatest" %% "scalatest" % deps.test.scalaTest % Test
  }
)

lazy val mutable = project.withId(s"$baseNameL-mutable").in(file("mutable"))
  .dependsOn(common)
  .settings(commonSettings)
  .settings(testSettings)
  .settings(
    name        := s"$baseName-mutable",
    description := "A simple file cache management",
    mimaPreviousArtifacts := Set("de.sciss" %% s"$baseNameL-mutable" % mimaVersion)
  )

lazy val txn = project.withId(s"$baseNameL-txn").in(file("txn"))
  .dependsOn(common)
  .settings(commonSettings)
  .settings(testSettings)
  .settings(
    name        := s"$baseName-txn",
    description := "A simple file cache management, using STM",
    libraryDependencies ++= Seq(
      "org.scala-stm" %% "scala-stm" % deps.main.scalaSTM
    ),
    mimaPreviousArtifacts := Set("de.sciss" %% s"$baseNameL-txn" % mimaVersion)
  )
