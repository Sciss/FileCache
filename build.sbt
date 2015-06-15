lazy val baseName         = "FileCache"
lazy val baseNameL        = baseName.toLowerCase

lazy val projectVersion   = "0.4.0-SNAPSHOT"

lazy val serialVersion    = "1.1.0-SNAPSHOT"
lazy val fileUtilVersion  = "1.1.1"
lazy val scalaTestVersion = "2.2.5"
lazy val scalaSTMVersion  = "0.7"

lazy val commonSettings = Seq(
  version            := projectVersion,
  organization       := "de.sciss",
  scalaVersion       := "2.11.6",
  crossScalaVersions := Seq("2.11.6", "2.10.4"),
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
    Some(if (isSnapshot.value)
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

lazy val root = Project(id = "root", base = file(".")).
  aggregate(common, mutable, txn).
  settings(commonSettings).
  settings(
    packagedArtifacts := Map.empty           // prevent publishing anything!
  )

lazy val common = Project(id = s"$baseNameL-common", base = file("common")).
  settings(commonSettings).
  settings(
    name        := s"$baseName-common",
    description := "Common functionality of the FileCache project",
    libraryDependencies ++= Seq(
      "de.sciss" %% "serial"   % serialVersion,
      "de.sciss" %% "fileutil" % fileUtilVersion
    )
  )

def scalaTest = "org.scalatest" %% "scalatest" % scalaTestVersion % "test"

lazy val mutable = Project(id = s"$baseNameL-mutable", base = file("mutable")).
  dependsOn(common).
  settings(commonSettings).
  settings(
    name        := s"$baseName-mutable",
    description := "A simple file cache management",
    libraryDependencies += scalaTest
  )

lazy val txn = Project(id = s"$baseNameL-txn", base = file("txn")).
  dependsOn(common).
  settings(commonSettings).
  settings(
    name        := s"$baseName-txn",
    description := "A simple file cache management, using STM",
    libraryDependencies ++= Seq(
      "org.scala-stm" %% "scala-stm" % scalaSTMVersion,
      scalaTest
    )
  )

// ---- ls.implicit.ly ----

// seq(lsSettings :_*)
// (LsKeys.tags   in LsKeys.lsync) := Seq("file", "io", "cache")
// (LsKeys.ghUser in LsKeys.lsync) := Some("Sciss")
// (LsKeys.ghRepo in LsKeys.lsync) := Some(name.value)
