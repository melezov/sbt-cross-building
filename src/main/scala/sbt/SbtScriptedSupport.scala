/* sbt -- Simple Build Tool
 * Copyright 2011 Artyom Olshevskiy
 *
 * Copyright 2012 Johannes Rudolph
 *
 * The settings were imported mostly from 2422df23c601089aa275b1bd04f28defb438d07f
 * and then adapted to use the new version dependent launcher.
 *
 * The main adaptation is to use the correct launcher for the sbt version
 * to test against.
 */
package sbt

import classpath.ClasspathUtilities
import Keys._
import java.lang.reflect.Method
import Project.Initialize

object SbtScriptedSupport {
  import CrossBuilding._
  import ScriptedPlugin._

  val sbtLauncher = TaskKey[File]("sbt-launcher")

  def scriptedTask: Initialize[InputTask[Unit]] = InputTask(_ => complete.Parsers.spaceDelimited("<arg>")) { result =>
    (scriptedDependencies, scriptedTests, scriptedRun, sbtTestDirectory, scriptedBufferLog, scriptedSbt, scriptedScalas, sbtLauncher, result) map {
      (deps, m, r, testdir, bufferlog, version, scriptedScalas, launcher, args) =>
        val baseParams = Seq(testdir, bufferlog: java.lang.Boolean, version.toString, scriptedScalas.build, scriptedScalas.versions, args.toArray, launcher)
        val params =
          CrossBuilding.byMajorVersion(version) { major =>
            if (major < 12)
              baseParams
            else
              baseParams :+ Array[String]()
          }
        try { r.invoke(m, params: _*) }
        catch { case e: java.lang.reflect.InvocationTargetException => throw e.getCause }
    }
  }

  def sbtLaunchUrl(version: String) =
    "http://typesafe.artifactoryonline.com/typesafe/ivy-releases/%s/sbt-launch/%s/sbt-launch.jar" format (groupIdByVersion(version), version)

  val scriptedSettings = seq(
    ivyConfigurations += scriptedConf,
    scriptedSbt <<= pluginSbtVersion(CrossBuilding.currentCompatibleSbtVersion),
    scriptedScalas <<= (scalaVersion) { (scala) => ScriptedScalas(scala, scala) },
    libraryDependencies <++= (scriptedScalas, scriptedSbt) { (scalas, version) =>
      Seq(
        groupIdByVersion(version) % scriptedSbtName(version, scalas.build) % version % scriptedConf.toString,
        groupIdByVersion(version) % "sbt-launch" % version % scriptedConf.toString from sbtLaunchUrl(version)
      )
    },
    resolvers += Resolver.url("Typesafe repository", url("http://typesafe.artifactoryonline.com/typesafe/ivy-releases/"))(Resolver.defaultIvyPatterns),
    sbtTestDirectory <<= sourceDirectory / "sbt-test",
    scriptedBufferLog := true,
    scriptedClasspath <<= (classpathTypes, update) map { (ct, report) => PathFinder(Classpaths.managedJars(scriptedConf, ct, report).map(_.data)) },
    scriptedTests <<= scriptedTestsTask,
    scriptedRun <<= scriptedRunTask,
    scriptedDependencies <<= (compile in Test, publishLocal) map { (analysis, pub) => Unit },
    scripted <<= scriptedTask,

    sbtLauncher <<= (update in scriptedConf) map { updateReport =>
      val mr = updateReport.configuration(scriptedConf.toString).get.modules.find(_.module.name == "sbt-launch").get
      mr.artifacts.head._2
    }
  )

  def scriptedRunTask: Initialize[Task[Method]] = (scriptedTests, scriptedSbt) map { (m, version) =>
    val paramTypes =
      CrossBuilding.byMajorVersion(version) { major =>
        if (major < 12)
          Seq(classOf[File], classOf[Boolean], classOf[String], classOf[String], classOf[String], classOf[Array[String]], classOf[File])
        else
          Seq(classOf[File], classOf[Boolean], classOf[String], classOf[String], classOf[String], classOf[Array[String]], classOf[File], classOf[Array[String]])
      }

    m.getClass.getMethod("run", paramTypes: _*)
  }

  def scriptedSbtName(version: String, scalaVersion: String): String =
    if (CrossBuilding.usesCrossBuilding(version))
      "scripted-sbt_" + scalaVersion
    else
      "scripted-sbt"
}
