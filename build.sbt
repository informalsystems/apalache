import scala.util.Failure
import Dependencies._

import scala.sys.process._

///////////////////////////
// Project-wide settings //
///////////////////////////

name := "apalache"
maintainer := "apalache@informal.org"

// See https://www.scala-sbt.org/1.x/docs/Multi-Project.html#Build-wide+settings
ThisBuild / organizationName := "Informal Systems Inc."
ThisBuild / organizationHomepage := Some(url("https://informal.systems"))
ThisBuild / licenses += "Apache 2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")

// We store the version in a bare file to make accessing and updating the version trivial
ThisBuild / versionFile := (ThisBuild / baseDirectory).value / "VERSION"
ThisBuild / version := scala.io.Source.fromFile(versionFile.value).mkString.trim

ThisBuild / organization := "at.forsyte"
ThisBuild / scalaVersion := "2.13.14"

// Add resolver for Sonatype OSS Snapshots and Releases Maven repository
ThisBuild / resolvers ++= Resolver.sonatypeOssRepos("snapshots")
ThisBuild / resolvers ++= Resolver.sonatypeOssRepos("releases")

// Shared dependencies accross all sub projects
ThisBuild / libraryDependencies ++= Seq(
    Deps.guice,
    Deps.logbackClassic,
    Deps.logbackCore,
    Deps.logging,
    Deps.scalaParserCombinators,
    Deps.scalaz,
    Deps.slf4j,
    Deps.tla2tools,
    Deps.z3,
    Deps.shapeless,
    TestDeps.junit,
    TestDeps.easymock,
    TestDeps.scalatest,
    TestDeps.scalacheck,
    TestDeps.scalatestplusEasymock,
    TestDeps.scalatestplusJunit,
    TestDeps.scalatestplusScalacheck,
)

//////////////////////
// Compiler options //
//////////////////////

fatalWarnings := sys.env.get("APALACHE_FATAL_WARNINGS").getOrElse("false").toBoolean
ThisBuild / scalacOptions ++= {
  val commonOptions = Seq(
      // Enable deprecation and feature warnings
      "-deprecation",
      "-feature",
      // Enable `unused` compiler warnings; required by scalafix
      // https://scalacenter.github.io/scalafix/docs/rules/RemoveUnused.html
      "-Ywarn-unused",
      // Fixes warning: "Exhaustivity analysis reached max recursion depth, not all missing cases are reported."
      "-Ypatmat-exhaust-depth",
      "22",
      // Silence compiler warnings in generated files
      // See https://stackoverflow.com/a/66354074/1187277
      "-Wconf:src=src_managed/.*:silent",
  )
  val conditionalOptions = if (fatalWarnings.value) Seq("-Xfatal-warnings") else Nil

  commonOptions ++ conditionalOptions
}

////////////////////////////
// Linting and formatting //
////////////////////////////

// scalafmt
// TODO: Remove if we decide we are happy with allways reformatting all
// Only check/fix against (tracked) files that have changed relative to the trunk
// ThisBuild / scalafmtFilter := "diff-ref=origin/main"
ThisBuild / scalafmtPrintDiff := true

// scalafix
// https://scalacenter.github.io/scalafix/docs/users/installation.html
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

////////////////////////
// Test configuration //
///////////////////////

lazy val testSettings = Seq(
    // Configure the test reporters for concise but informative output.
    // See https://www.scalatest.org/user_guide/using_scalatest_with_sbt
    // for the meaning of the flags.
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oCDEH")
)

///////////////////////
// API Documentation //
///////////////////////

lazy val browseApiDocs = taskKey[Unit]("Build and browse the API docs")
browseApiDocs := {
  val buildDirs = (Compile / unidoc).value
  require(buildDirs.nonEmpty, "unidoc failed to return build path")
  val index = buildDirs(0) / "index.html"
  val operSys = System.getProperty("os.name").toLowerCase();
  if (operSys.contains("nix") || operSys.contains("nux") || operSys.contains("aix")) {
    s"xdg-open ${index}" !
  } else if (operSys.contains("mac")) {
    s"open ${index}" !
  } else {
    println(s"Open you browser to ${index}")
  }
}

/////////////////////////////
// Sub-project definitions //
/////////////////////////////

lazy val tlair = (project in file("tlair"))
  .settings(
      testSettings,
      libraryDependencies ++= Seq(
          Deps.ujson,
          Deps.kiama,
      ),
  )

lazy val infra = (project in file("mod-infra"))
  .dependsOn(tlair)
  .settings(
      testSettings,
      libraryDependencies ++= Seq(
          Deps.commonsIo,
          Deps.ujson,
          Deps.upickle,
      ),
  )

lazy val tla_io = (project in file("tla-io"))
  .dependsOn(
      tlair,
      // property based tests depend on IR generators defined in the tlair tests
      // See https://www.scala-sbt.org/1.x/docs/Multi-Project.html#Per-configuration+classpath+dependencies
      tlair % "test->test",
      infra,
  )
  .settings(
      testSettings,
      libraryDependencies ++= Seq(
          Deps.commonsIo,
          Deps.pureConfig,
      ),
  )

lazy val tla_typechecker = (project in file("tla-typechecker"))
  .dependsOn(tlair, infra,
      // property based tests depend on IR generators defined in the tlair tests
      // See https://www.scala-sbt.org/1.x/docs/Multi-Project.html#Per-configuration+classpath+dependencies
      tlair % "test->test", tla_io)
  .settings(
      testSettings,
      libraryDependencies += Deps.commonsIo,
  )

lazy val tla_pp = (project in file("tla-pp"))
  .dependsOn(
      tlair,
      // property based tests depend on IR generators defined in the tlair tests
      // See https://www.scala-sbt.org/1.x/docs/Multi-Project.html#Per-configuration+classpath+dependencies
      tlair % "test->test",
      infra,
      tla_io,
  )
  .settings(
      testSettings,
      libraryDependencies += Deps.commonsIo,
  )

lazy val tla_assignments = (project in file("tla-assignments"))
  .dependsOn(tlair, infra, tla_io, tla_pp)
  .settings(
      testSettings,
      libraryDependencies += Deps.commonsIo,
  )

lazy val passes = (project in file("passes"))
  .dependsOn(
      tlair,
      infra,
      tla_io,
      tla_pp,
      tla_assignments,
      tla_typechecker,
  )
  .settings(
      testSettings,
      libraryDependencies += Deps.scalaCollectionContrib,
  )

lazy val tla_bmcmt = (project in file("tla-bmcmt"))
  .dependsOn(
      tlair,
      // property based tests depend on IR generators defined in the tlair tests
      // See https://www.scala-sbt.org/1.x/docs/Multi-Project.html#Per-configuration+classpath+dependencies
      tlair % "test->test",
      infra,
      tla_io,
      tla_pp,
      tla_assignments,
      passes,
  )
  .settings(
      testSettings,
      libraryDependencies += Deps.scalaCollectionContrib,
  )

lazy val shai = (project in file("shai"))
  .dependsOn(tlair, infra, tla_io, tla_typechecker, tla_bmcmt, passes)
  .settings(
      // See https://zio.dev/version-1.x/usecases/usecases_testing/
      testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
      libraryDependencies ++= Seq(
          Deps.zio,
          Deps.grpcNetty,
          Deps.scalapbRuntimGrpc,
          Deps.scalapbRuntime,
          Deps.zioGrpcCodgen,
          TestDeps.zioTest,
          TestDeps.zioTestSbt,
      ),
      // See https://scalapb.github.io/zio-grpc/docs/installation
      Compile / PB.targets := Seq(
          scalapb.gen(grpc = true) -> (Compile / sourceManaged).value / "scalapb",
          scalapb.zio_grpc.ZioCodeGenerator -> (Compile / sourceManaged).value / "scalapb",
      ),
  )

lazy val tool = (project in file("mod-tool"))
  .dependsOn(tlair, tla_io, tla_assignments, tla_typechecker, tla_bmcmt, shai, passes)
  .enablePlugins(BuildInfoPlugin)
  .settings(
      testSettings,
      // The following buildInfo values will be available in the source
      // code in the `apalache.BuildInfo` singleton.
      // See https://github.com/sbt/sbt-buildinfo
      buildInfoPackage := "apalache",
      buildInfoKeys := {
        val build = Process("git describe --tags --always").!!.trim
        Seq[BuildInfoKey](
            BuildInfoKey.map(version) { case (k, v) =>
              if (isSnapshot.value) (k -> build) else (k -> v)
            },
            BuildInfoKey.action("build")(build),
        )
      },
      libraryDependencies ++= Seq(
          Deps.commonsConfiguration2,
          Deps.commonsBeanutils,
          Deps.clistCore,
          Deps.clistMacros,
      ),
  )

lazy val distribution = (project in file("mod-distribution"))
  .dependsOn(tlair, tla_io, tla_assignments, tla_bmcmt, passes, tool)
  .settings(
      testSettings
  )

///////////////
// Packaging //
///////////////

lazy val apalacheCurrentPackage = taskKey[File]("Set the current executable apalche-mc to the latest package")

// Define the main entrypoint and uber jar package
lazy val root = (project in file("."))
  .enablePlugins(UniversalPlugin, sbtdocker.DockerPlugin, ChangelingPlugin, ScalaUnidocPlugin)
  .dependsOn(distribution)
  .aggregate(
      // propagate commands to these sub-projects
      tlair,
      infra,
      tla_io,
      tla_typechecker,
      tla_pp,
      tla_assignments,
      tla_bmcmt,
      passes,
      shai,
      tool,
      distribution,
  )
  .settings(
      testSettings,
      // TODO: uncomment to enable building unidoc for for all test and src code
      // Generate scaladoc for both test and src code
      // ScalaUnidoc / unidoc / unidocConfigurationFilter := inConfigurations(Compile, Test),
      // Package definition
      Compile / packageBin / mappings ++= Seq(
          // Include theese assets in the compiled package at the specified locations
          ((ThisBuild / baseDirectory).value / "README.md" -> "README.md"),
          ((ThisBuild / baseDirectory).value / "LICENSE" -> "LICENSE"),
      ),
      // Assembly constructs our "fat jar"
      assembly / assemblyJarName := s"apalache-pkg-${version.value}-full.jar",
      assembly / mainClass := Some("at.forsyte.apalache.tla.Tool"),
      assembly / assembledMappings += {
        // To make our custom TLA modules available for import in TLA specs, we add them
        // to the tla2sany/StandardModules directory.
        // See https://github.com/sbt/sbt-assembly/issues/227#issuecomment-283504401
        val tlaModuleMapping =
          ((ThisBuild / baseDirectory).value / "src" / "tla")
            .listFiles()
            .collect {
              case f if f.getName.endsWith(".tla") =>
                f -> s"tla2sany/StandardModules/${f.getName}"
            }
            .toVector
        sbtassembly.MappingSet(None, tlaModuleMapping)
      },
      assembly / assemblyMergeStrategy := {
        // Workaround for conflict with grpc-netty manifest files
        // See https://github.com/sbt/sbt-assembly/issues/362
        case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.first
        // Workaround for conflict between gson and slf4j manifest files:
        // [error] (assembly) deduplicate: different file contents found in the following:
        // [error] .../.cache/coursier/v1/https/repo1.maven.org/maven2/com/google/code/gson/gson/2.9.0/gson-2.9.0.jar:META-INF/versions/9/module-info.class
        // [error] .../.cache/coursier/v1/https/repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.0/slf4j-api-2.0.0.jar:META-INF/versions/9/module-info.class
        // See https://stackoverflow.com/a/67937671/1187277
        case PathList("module-info.class")         => MergeStrategy.discard
        case x if x.endsWith("/module-info.class") => MergeStrategy.discard
        case x                                     => (assembly / assemblyMergeStrategy).value(x)
      },
      // Package the distribution files
      Universal / mappings ++= Seq(
          // The fat jar
          assembly.value -> "lib/apalache.jar",
          (ThisBuild / baseDirectory).value / "LICENSE" -> "LICENSE",
      ),
      apalacheCurrentPackage := {
        val log = streams.value.log
        val pkg = (Universal / packageZipTarball).value
        val (unzipped, _) = IO.split(pkg.toString)
        val target_dir = (Universal / target).value
        val current_pkg = (Universal / target).value / "current-pkg"
        log.info(s"Unpacking package ${pkg} to ${target_dir}")
        // send outputs directly to std{err,out} instead of logging here
        // to avoid misleading logging output from tar
        // See https://github.com/informalsystems/apalache/pull/1382
        (s"tar zxvf ${pkg} -C ${target_dir}" !)
        log.info(s"Symlinking ${current_pkg} -> ${unzipped}")
        if (current_pkg.exists) {
          log.info(s"${current_pkg} already exists, overwriting")
          current_pkg.delete
        }
        java.nio.file.Files.createSymbolicLink(
            current_pkg.toPath,
            file(unzipped).toPath,
        )
        file(unzipped)
      },
  )

docker / imageNames := {
  val img: String => ImageName = s =>
    ImageName(
        namespace = Some("ghcr.io/informalsystems"),
        repository = name.value,
        tag = Some(s),
    )
  Seq(
      img(version.value),
      img("latest"),
  )
}

docker / dockerfile := {
  val rootDir = (ThisBuild / baseDirectory).value
  // Docker Working Dir
  val dwd = "/opt/apalache"

  val pkg = apalacheCurrentPackage.value

  val runner = rootDir / "bin" / "run-in-docker-container"
  val runnerTarget = s"${dwd}/bin/run-in-docker-container"
  val readme = rootDir / "README.md"

  new Dockerfile {
    from("eclipse-temurin:17")

    workDir(dwd)

    add(pkg, ".")
    add(runner, runnerTarget)
    add(readme, s"${dwd}/${readme.name}")

    // TLA parser requires all specification files to be in the same directory
    // We assume the user bind-mounted the spec dir into /var/apalache
    // but, in case the directory was not bind-mounted, we create one
    run("mkdir", "/var/apalache")

    // We need sudo to run apalache using the user (created in the entrypoint script)
    run("apt", "update")
    run("apt", "install", "sudo")

    entryPoint("/opt/apalache/bin/run-in-docker-container")
  }
}

//////////////
// appendix //
//////////////

// For some reason `scalafmtFilter` doesn't register as being used, tho it is
// so this quiets the erroneous linting.
Global / excludeLintKeys += scalafmtFilter

lazy val versionFile = settingKey[File]("Location of the file tracking the project version")

// These tasks are used in our bespoke release pipeline
// TODO(shon): Once we've changed our packaging to conform to more standard SBT structures and practices,
// we should consider moving to a release pipeline based around sbt-release.
// See https://github.com/informalsystems/apalache/issues/1248

lazy val printVersion = taskKey[Unit]("Print the current version")
printVersion := {
  println((ThisBuild / version).value)
}

lazy val removeVersionSnapshot = taskKey[Unit]("Remove version snapshot from the version file")
removeVersionSnapshot := {
  val releaseVersion = (ThisBuild / version).value.stripSuffix("-SNAPSHOT")
  IO.writeLines((ThisBuild / versionFile).value, Seq(releaseVersion))
}

lazy val setVersion = inputKey[Unit]("Set the version recorded in the version file")
setVersion := {
  val version: String = complete.Parsers.spaceDelimited("<args>").parsed(0)
  IO.writeLines((ThisBuild / versionFile).value, Seq(version))
}

lazy val incrVersion = taskKey[String]("Increment to the next patch snapshot version")
incrVersion := {
  val fullVersion = (ThisBuild / version).value
  val currentVersion =
    try {
      fullVersion.split("-")(0)
    } catch {
      case _: ArrayIndexOutOfBoundsException =>
        throw new RuntimeException(s"Invalid version: ${fullVersion}")
    }
  val nextVersion = currentVersion.split("\\.") match {
    case Array(maj, min, patch) => {
      val nextPatch = ((patch.toInt) + 1).toString
      s"${maj}.${min}.${nextPatch}-SNAPSHOT"
    }
    case arr =>
      throw new RuntimeException(s"Invalid version: ${fullVersion}")
  }
  IO.writeLines((ThisBuild / versionFile).value, Seq(nextVersion))
  nextVersion
}

lazy val fatalWarnings = settingKey[Boolean]("Whether or not to compile with fatal warnings")
