lazy val baseName = "FileCache"

lazy val commonSettings = Project.defaultSettings ++ Seq(
  version           := "0.3.0-SNAPSHOT",
  organization      := "de.sciss",
  homepage          := Some(url("https://github.com/Sciss/" + name.value)),
  scalaVersion      := "2.10.3",
  homepage          := Some(url("https://github.com/Sciss/abc4j")),
  licenses          := Seq("GPL v2+" -> url("http://www.gnu.org/licenses/gpl-2.0.txt")),
  retrieveManaged   := true,
  scalacOptions    ++= Seq("-deprecation", "-unchecked", "-feature"),
  scalacOptions    ++= Seq("-Xelide-below", "INFO"),    // elide debug logging!
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

lazy val common = Project(
  id        = s"$baseName-common",
  base      = file("common"),
  settings  = commonSettings ++ Seq(
    name        := s"$baseName-common",
    description := "Common functionality of the FileCache project",
    libraryDependencies ++= Seq(
      "de.sciss" %% "serial"   % "1.0.+",
      "de.sciss" %% "fileutil" % "1.1.+"
    )
  )
)

lazy val mutable = Project(
  id            = s"$baseName-mutable",
  base          = file("mutable"),
  dependencies  = Seq(common),
  settings      = commonSettings ++ Seq(
    name        := s"$baseName-mutable",
    description := "A simple file cache management",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "2.0" % "test"
    )
  )
)

lazy val txn = Project(
  id            = s"$baseName-txn",
  base          = file("txn"),
  dependencies  = Seq(common),
  settings      = commonSettings ++ Seq(
    name        := s"$baseName-txn",
    description := "A simple file cache management, using STM",
    libraryDependencies ++= Seq(
      "org.scala-stm" %% "scala-stm" % "0.7"
    )
  )
)

// ---- ls.implicit.ly ----

seq(lsSettings :_*)

(LsKeys.tags   in LsKeys.lsync) := Seq("file", "io", "cache")

(LsKeys.ghUser in LsKeys.lsync) := Some("Sciss")

(LsKeys.ghRepo in LsKeys.lsync) := Some(name.value)

