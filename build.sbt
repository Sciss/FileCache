lazy val baseName         = "FileCache"
lazy val baseNameL        = baseName.toLowerCase

lazy val projectVersion   = "1.0.0"
lazy val mimaVersion      = "1.0.0"

lazy val deps = new {
  val main = new {
    val serial    = "2.0.0"
    val fileUtil  = "1.1.5"
    val scalaSTM  = "0.10.0"
  }
  val test = new {
    val scalaTest = "3.2.2"
  }
}

lazy val commonSettings = Seq(
  version            := projectVersion,
  organization       := "de.sciss",
  scalaVersion       := "0.27.0-RC1", // "2.13.3",
  crossScalaVersions := Seq("0.27.0-RC1", "2.13.3", "2.12.12"),
  homepage           := Some(url(s"https://git.iem.at/sciss/$baseName")),
  licenses           := Seq("LGPL v2.1+" -> url("http://www.gnu.org/licenses/lgpl-2.1.txt")),
  scalacOptions     ++= Seq("-deprecation", "-unchecked", "-feature", "-encoding", "utf8", "-Xlint", "-Xsource:2.13"),
  scalacOptions     ++= {
    // XXX TODO Dotty does not yet support eliding
    if (isDotty.value) Nil else Seq("-Xelide-below", /*"CONFIG"*/ "INFO")    // elide debug logging!
  },
  sources in (Compile, doc) := {
    if (isDotty.value) Nil else (sources in (Compile, doc)).value // bug in dottydoc
  },
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
      <url>git@git.iem.at:sciss/{baseName}.git</url>
      <connection>scm:git:git@git.iem.at:sciss/{baseName}.git</connection>
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
      "de.sciss" %% "serial"   % deps.main.serial,
      "de.sciss" %% "fileutil" % deps.main.fileUtil
    ),
    mimaPreviousArtifacts := Set("de.sciss" %% s"$baseNameL-common" % mimaVersion)
  )

lazy val testSettings = Seq(
  libraryDependencies += {
    if (scalaVersion.value == "2.13.0") {
      "org.scalatest" % "scalatest_2.13.0-RC3" % deps.test.scalaTest % Test
    } else {
      "org.scalatest" %% "scalatest" % deps.test.scalaTest % Test
    }
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
