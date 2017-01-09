lazy val baseName         = "FileCache"
lazy val baseNameL        = baseName.toLowerCase

lazy val projectVersion   = "0.3.5-SNAPSHOT"
lazy val mimaVersion      = "0.3.1"

lazy val serialVersion    = "1.0.3"
lazy val fileUtilVersion  = "1.1.2"
lazy val scalaTestVersion = "3.0.1"
lazy val scalaSTMVersion  = "0.8"

lazy val commonSettings = Seq(
  version            := projectVersion,
  organization       := "de.sciss",
  scalaVersion       := "2.11.8",
  crossScalaVersions := Seq("2.12.1", "2.11.8", "2.10.6"),
  homepage           := Some(url(s"https://github.com/Sciss/$baseName")),
  licenses           := Seq("LGPL v2.1+" -> url("http://www.gnu.org/licenses/lgpl-2.1.txt")),
  scalacOptions     ++= Seq("-deprecation", "-unchecked", "-feature", "-Xfuture", "-encoding", "utf8", "-Xlint"),
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
    name := baseName,
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
    ),
    mimaPreviousArtifacts := Set("de.sciss" %% s"$baseNameL-common" % mimaVersion)
  )

def scalaTest = "org.scalatest" %% "scalatest" % scalaTestVersion % "test"

lazy val mutable = Project(id = s"$baseNameL-mutable", base = file("mutable")).
  dependsOn(common).
  settings(commonSettings).
  settings(
    name        := s"$baseName-mutable",
    description := "A simple file cache management",
    libraryDependencies += scalaTest,
    mimaPreviousArtifacts := Set("de.sciss" %% s"$baseNameL-mutable" % mimaVersion)
  )

lazy val txn = Project(id = s"$baseNameL-txn", base = file("txn")).
  dependsOn(common).
  settings(commonSettings).
  settings(
    name        := s"$baseName-txn",
    description := "A simple file cache management, using STM",
    libraryDependencies += {
      val sv   = scalaVersion.value
      val stmV = if (sv.startsWith("2.10") || sv.startsWith("2.11")) "0.7" else scalaSTMVersion
      "org.scala-stm" %% "scala-stm" % stmV
    },
    libraryDependencies += scalaTest,
    mimaPreviousArtifacts := Set("de.sciss" %% s"$baseNameL-txn" % mimaVersion)
  )
