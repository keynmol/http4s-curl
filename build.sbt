ThisBuild / tlBaseVersion := "0.0"

ThisBuild / developers := List(
  tlGitHubDev("armanbilge", "Arman Bilge")
)
ThisBuild / startYear := Some(2022)

ThisBuild / crossScalaVersions := Seq("3.1.3", "2.13.8")
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))
ThisBuild / tlJdkRelease := Some(8)
ThisBuild / githubWorkflowOSes :=
  Seq("ubuntu-20.04", "ubuntu-22.04", "macos-11", "macos-12")

ThisBuild / githubWorkflowBuildPreamble +=
  WorkflowStep.Run(
    List("sudo apt-get update", "sudo apt-get install libcurl4-openssl-dev"),
    name = Some("Install libcurl"),
    cond = Some("startsWith(matrix.os, 'ubuntu')"),
  )
ThisBuild / githubWorkflowBuildPostamble ~= {
  _.filterNot(_.name.contains("Check unused compile dependencies"))
}

val http4sVersion = "0.23.14-101-02562a0-SNAPSHOT"
val munitCEVersion = "2.0-4e051ab-SNAPSHOT"
ThisBuild / resolvers ++= Resolver.sonatypeOssRepos("snapshots")

ThisBuild / nativeConfig ~= { c =>
  val osName = Option(System.getProperty("os.name"))
  val isMacOs = osName.exists(_.toLowerCase().contains("mac"))
  if (isMacOs) { // brew-installed curl
    c.withLinkingOptions(c.linkingOptions :+ "-L/usr/local/opt/curl/lib")
  } else c
}

lazy val root = project.in(file(".")).enablePlugins(NoPublishPlugin).aggregate(curl, example)

lazy val curl = project
  .in(file("curl"))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "http4s-curl",
    libraryDependencies ++= Seq(
      "com.armanbilge" %%% "http4s-client" % http4sVersion,
      "com.armanbilge" %%% "munit-cats-effect" % munitCEVersion % Test,
    ),
  )

lazy val example = project
  .in(file("example"))
  .enablePlugins(ScalaNativePlugin, NoPublishPlugin, VcpkgPlugin)
  .dependsOn(curl)
  .settings(
    libraryDependencies ++= Seq(
      "com.armanbilge" %%% "http4s-circe" % http4sVersion
    ),
    vcpkgDependencies := Set("curl", "zlib"),
  )
  .settings(vcpkgNativeConfig())

def vcpkgNativeConfig(rename: String => String = identity) = Seq(
  nativeConfig := {
    /* import bindgen.interface.Platform.OS.* */
    /* import bindgen.interface.Platform */
    val configurator = vcpkgConfigurator.value
    val manager = vcpkgManager.value
    val conf = nativeConfig.value
    val deps = vcpkgDependencies.value.toSeq.map(rename)

    val files = deps.map(d => manager.files(d))

    val compileArgsApprox = files.flatMap { f =>
      List("-I" + f.includeDir.toString)
    }
    val linkingArgsApprox = files.flatMap { f =>
      List("-L" + f.libDir) ++ f.staticLibraries.map(_.toString)
    }

    import scala.util.control.NonFatal

    def updateLinkingFlags(current: Seq[String], deps: String*) =
      try
        configurator.updateLinkingFlags(
          current,
          deps*
        )
      catch {
        case NonFatal(exc) =>
          current ++ linkingArgsApprox
      }

    def updateCompilationFlags(current: Seq[String], deps: String*) =
      try
        configurator.updateCompilationFlags(
          current,
          deps*
        )
      catch {
        case NonFatal(exc) =>
          current ++ compileArgsApprox
      }

    val arch64 =
      if (System.getProperty("os.arch") == "aarch64")
        List(
          "-arch",
          "arm64",
          "-framework",
          "CoreFoundation",
          "-framework",
          "Security",
          "-framework",
          "SystemConfiguration",
        )
      else Nil

    println(arch64)

    conf
      .withLinkingOptions(
        updateLinkingFlags(
          conf.linkingOptions ++ arch64,
          deps*
        )
      )
      .withCompileOptions(
        updateCompilationFlags(
          conf.compileOptions ++ arch64,
          deps*
        )
      )
  }
)
