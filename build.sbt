lazy val baseName = "FileCache"

def baseNameL = baseName.toLowerCase

lazy val projectVersion   = "0.3.2"

lazy val serialVersion    = "1.0.2"

lazy val fileUtilVersion  = "1.1.1"

lazy val scalaTestVersion = "2.1.3"

lazy val scalaSTMVersion  = "0.7"

lazy val commonSettings = Project.defaultSettings ++ Seq(
  version            := projectVersion,
  organization       := "de.sciss",
  scalaVersion       := "2.11.0",
  crossScalaVersions := Seq("2.11.0", "2.10.4"),
  homepage           := Some(url("https://github.com/Sciss/" + baseName)),
  licenses           := Seq("LGPL v2.1+" -> url("http://www.gnu.org/licenses/lgpl-2.1.txt")),
  // retrieveManaged    := true,
  scalacOptions     ++= Seq("-deprecation", "-unchecked", "-feature", "-Xfuture"),
  scalacOptions     ++= Seq("-Xelide-below", "INFO"),    // elide debug logging!
  initialCommands in console := """import de.sciss.filecache._
                                  |import concurrent._
                                  |import java.io.File""".stripMargin,
  // ---- publishing ----
  publishMavenStyle := true,
  publishTo :=
    Some(if (version.value endsWith "-SNAPSHOT")
      "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
    else
      "Sonatype Releases"  at "https://oss.sonatype.org/service/local/staging/deploy/maven2"
    ),
  publishArtifact in Test := false,
  pomIncludeRepository := { _ => false },
  pomExtra :=
    <scm>
      <url>git@github.com:Sciss/{baseName}.git</url>
      <connection>scm:git:git@github.com:Sciss/{baseName}.git</connection>
    </scm>
    <developers>
      <developer>
        <id>sciss</id>
        <name>Hanns Holger Rutz</name>
        <url>http://www.sciss.de</url>
      </developer>
    </developers>
)

lazy val root = Project(
  id        = "root",
  base      = file("."),
  aggregate = Seq(common, mutable, txn),
  settings  = commonSettings ++ Seq(
    packagedArtifacts := Map.empty           // prevent publishing anything!
  )
)

lazy val common = Project(
  id        = s"$baseNameL-common",
  base      = file("common"),
  settings  = commonSettings ++ Seq(
    name        := s"$baseName-common",
    description := "Common functionality of the FileCache project",
    libraryDependencies ++= Seq(
      "de.sciss" %% "serial"   % serialVersion,
      "de.sciss" %% "fileutil" % fileUtilVersion
    )
  )
)

def scalaTest = "org.scalatest" %% "scalatest" % scalaTestVersion % "test"

lazy val mutable = Project(
  id            = s"$baseNameL-mutable",
  base          = file("mutable"),
  dependencies  = Seq(common),
  settings      = commonSettings ++ Seq(
    name        := s"$baseName-mutable",
    description := "A simple file cache management",
    libraryDependencies += scalaTest
  )
)

lazy val txn = Project(
  id            = s"$baseNameL-txn",
  base          = file("txn"),
  dependencies  = Seq(common),
  settings      = commonSettings ++ Seq(
    name        := s"$baseName-txn",
    description := "A simple file cache management, using STM",
    libraryDependencies ++= Seq(
      "org.scala-stm" %% "scala-stm" % scalaSTMVersion,
      scalaTest
    )
  )
)

// ---- ls.implicit.ly ----

seq(lsSettings :_*)

(LsKeys.tags   in LsKeys.lsync) := Seq("file", "io", "cache")

(LsKeys.ghUser in LsKeys.lsync) := Some("Sciss")

(LsKeys.ghRepo in LsKeys.lsync) := Some(name.value)

