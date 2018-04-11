lazy val baseName         = "FileCache"
lazy val baseNameL        = baseName.toLowerCase

lazy val projectVersion   = "0.4.0"
lazy val mimaVersion      = "0.4.0"

lazy val serialVersion    = "1.1.0"
lazy val fileUtilVersion  = "1.1.3"
lazy val scalaTestVersion = "3.0.5"
lazy val scalaSTMVersion  = "0.8"

lazy val commonSettings = Seq(
  version            := projectVersion,
  organization       := "de.sciss",
  scalaVersion       := "2.12.5",
  crossScalaVersions := Seq("2.12.5", "2.11.12"),
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
      "de.sciss" %% "serial"   % serialVersion,
      "de.sciss" %% "fileutil" % fileUtilVersion
    ),
    mimaPreviousArtifacts := Set("de.sciss" %% s"$baseNameL-common" % mimaVersion)
  )

def scalaTest = "org.scalatest" %% "scalatest" % scalaTestVersion % "test"

lazy val mutable = project.withId(s"$baseNameL-mutable").in(file("mutable"))
  .dependsOn(common)
  .settings(commonSettings)
  .settings(
    name        := s"$baseName-mutable",
    description := "A simple file cache management",
    libraryDependencies += scalaTest,
    mimaPreviousArtifacts := Set("de.sciss" %% s"$baseNameL-mutable" % mimaVersion)
  )

lazy val txn = project.withId(s"$baseNameL-txn").in(file("txn"))
  .dependsOn(common)
  .settings(commonSettings)
  .settings(
    name        := s"$baseName-txn",
    description := "A simple file cache management, using STM",
    libraryDependencies ++= Seq(
      "org.scala-stm" %% "scala-stm" % scalaSTMVersion
    ),
    libraryDependencies += scalaTest,
    mimaPreviousArtifacts := Set("de.sciss" %% s"$baseNameL-txn" % mimaVersion)
  )
