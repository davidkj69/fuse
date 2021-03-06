package org.fusesource.fabric.fab.osgi.itests

import javax.inject.Inject

import org.junit.Test
import org.junit.Assert._
import org.osgi.framework.{Bundle, BundleContext}
import org.ops4j.pax.exam.Option

import org.ops4j.pax.exam.CoreOptions._;
import org.junit.runner.RunWith
import org.ops4j.pax.exam.junit.{ExamReactorStrategy, JUnit4TestRunner, Configuration}
import org.ops4j.pax.exam.spi.reactors.AllConfinedStagedReactorFactory
import org.fusesource.fabric.fab.osgi.internal.Bundles
;

/**
 *
 */
@RunWith(classOf[JUnit4TestRunner])
@ExamReactorStrategy(Array(classOf[AllConfinedStagedReactorFactory]))
class FabSamplesWithoutCamelPreinstalledTest {

  lazy val VERSION = System.getProperty("project.version")

  @Inject
  var context: BundleContext = null;

  @Configuration
  def config: Array[Option] = Array(
    junitBundles(),
    felix(),
    equinox(),

    // vmOption( "-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5006" ),
    systemProperty("project.version").value(VERSION),

    // we need the boot delegation to allow the Spring/Blueprint XML parsing with JAXP to succeed
    bootDelegationPackage("com.sun.*"),

    mavenBundle("org.ops4j.pax.url", "pax-url-mvn").versionAsInProject(),

    mavenBundle("org.apache.felix", "org.apache.felix.fileinstall").versionAsInProject(),
    mavenBundle("org.apache.felix", "org.apache.felix.configadmin").versionAsInProject(),

    mavenBundle("org.apache.karaf.jaas", "org.apache.karaf.jaas.boot").versionAsInProject(),
    mavenBundle("org.apache.karaf.jaas", "org.apache.karaf.jaas.config").versionAsInProject(),
    mavenBundle("org.apache.karaf.jaas", "org.apache.karaf.jaas.modules").versionAsInProject(),

    mavenBundle("org.apache.servicemix.bundles", "org.apache.servicemix.bundles.asm").versionAsInProject(),
    mavenBundle("org.apache.aries", "org.apache.aries.util").versionAsInProject(),
    mavenBundle("org.apache.aries.proxy", "org.apache.aries.proxy").versionAsInProject(),
    mavenBundle("org.apache.aries.blueprint", "org.apache.aries.blueprint.api").versionAsInProject(),
    mavenBundle("org.apache.aries.blueprint", "org.apache.aries.blueprint.core").versionAsInProject(),
    mavenBundle("org.apache.karaf.features", "org.apache.karaf.features.core").versionAsInProject(),
    mavenBundle("org.apache.karaf.shell", "org.apache.karaf.shell.console").versionAsInProject(),

    // and then add a few extra bundles to it to enable Scala- and FAB-support
    mavenBundle("org.apache.servicemix.bundles", "org.apache.servicemix.bundles.scala-library").versionAsInProject(),
    mavenBundle("org.fusesource.fabric.fab", "fab-osgi").versionAsInProject()
  )

  /**
   * Get the fab: url for a given example
   *
   * @param groupId the artifact's group id
   * @param artifactId the artifact id
   * @return a fab: url
   */
  def fab(groupId: String, artifactId: String) = "fab:mvn:%s/%s/%s".format(groupId, artifactId, VERSION)

  @Test
  def testCamelBlueprintShare = {
    val url = fab("org.fusesource.fabric.fab.tests", "fab-sample-camel-blueprint-share")

    val (change, _) = bundlesChanged(context) {
      context.installBundle(url)
    }

    assertTrue("Expected FAB itself to be added", change.map(_.getLocation).contains(url))
    assertTrue("Expected camel-core to be added", change.map(_.getSymbolicName).contains("org.apache.camel.camel-core"))
    assertTrue("Expected camel-blueprint to be added", change.map(_.getSymbolicName).contains("org.apache.camel.camel-core"))
  }

  /**
   * Determines the list of bundles added to the bundle context while executing a block of code
   *
   * @param context the bundle context
   * @param block the block of code to be executed
   * @return a set of bundles that have been added, together with the result of the code block
   */
  def bundlesChanged[R](context: BundleContext)(block: => R): (Set[Bundle], R) = {
    val start = context.getBundles
    val result = block
    (context.getBundles.toSet -- start, result)
  }
}
