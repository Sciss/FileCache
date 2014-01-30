name          := "FileCache"

version       := "0.2.1"

organization  := "de.sciss"

scalaVersion  := "2.10.1"

description   := "A simple file cache management"

homepage      := Some(url("https://github.com/Sciss/" + name.value))

licenses      := Seq("GPL v2+" -> url("http://www.gnu.org/licenses/gpl-2.0.txt"))

initialCommands in console := """
  |import de.sciss.filecache._
  |import concurrent._
  |import java.io.File""".stripMargin

libraryDependencies ++= Seq(
  "de.sciss" %% "serial" % "1.0.+",
  "org.scalatest" %% "scalatest" % "1.9.1" % "test"
)

retrieveManaged := true

scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature")

scalacOptions ++= Seq("-Xelide-below", "INFO")     // elide debug logging!

// ---- publishing ----

publishMavenStyle := true

publishTo :=
  Some(if (version.value endsWith "-SNAPSHOT")
    "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
  else
    "Sonatype Releases"  at "https://oss.sonatype.org/service/local/staging/deploy/maven2"
  )

publishArtifact in Test := false

pomIncludeRepository := { _ => false }

pomExtra := { val n = name.value
<scm>
  <url>git@github.com:Sciss/{n}.git</url>
  <connection>scm:git:git@github.com:Sciss/{n}.git</connection>
</scm>
<developers>
   <developer>
      <id>sciss</id>
      <name>Hanns Holger Rutz</name>
      <url>http://www.sciss.de</url>
   </developer>
</developers>
}

// ---- ls.implicit.ly ----

seq(lsSettings :_*)

(LsKeys.tags   in LsKeys.lsync) := Seq("file", "io", "cache")

(LsKeys.ghUser in LsKeys.lsync) := Some("Sciss")

(LsKeys.ghRepo in LsKeys.lsync) := Some(name.value)

