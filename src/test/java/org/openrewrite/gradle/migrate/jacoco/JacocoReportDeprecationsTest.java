/*
 * Copyright 2025 the original author or authors.
 * <p>
 * Licensed under the Moderne Source Available License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://docs.moderne.io/licensing/moderne-source-available-license
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.gradle.migrate.jacoco;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;

class JacocoReportDeprecationsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new JacocoReportDeprecations());
    }

    @DocumentExample
    @Test
    void deprecationsInNormalSyntax() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id "java"
                  id "jacoco"
              }

              jacocoTestReport {
                  reports {
                      xml.enabled = false
                      csv.enabled = true
                      html.enabled = false

                      xml.destination = layout.buildDirectory.dir('jacocoXml')
                      csv.destination = layout.buildDirectory.dir('jacocoCsv')
                      html.destination = layout.buildDirectory.dir('jacocoHtml')
                  }
              }
              """,
            """
              plugins {
                  id "java"
                  id "jacoco"
              }

              jacocoTestReport {
                  reports {
                      xml.required = false
                      csv.required = true
                      html.required = false

                      xml.outputLocation = layout.buildDirectory.dir('jacocoXml')
                      csv.outputLocation = layout.buildDirectory.dir('jacocoCsv')
                      html.outputLocation = layout.buildDirectory.dir('jacocoHtml')
                  }
              }
              """
          )
        );
    }

    @Test
    void enabledDeprecatedInCollapsedSyntax() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id "java"
                  id "jacoco"
              }

              jacocoTestReport.reports.xml.enabled = false
              jacocoTestReport.reports.csv.enabled = true
              jacocoTestReport.reports.html.enabled = false

              """,
            """
              plugins {
                  id "java"
                  id "jacoco"
              }

              jacocoTestReport.reports.xml.required = false
              jacocoTestReport.reports.csv.required = true
              jacocoTestReport.reports.html.required = false

              """
          )
        );
    }

    @Test
    void enabledDeprecatedInElapsedSyntax() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id "java"
                  id "jacoco"
              }

              jacocoTestReport {
                  reports {
                      xml {
                          enabled = false
                      }
                      csv {
                          enabled = false
                      }
                      html {
                          enabled = false
                      }
                  }
              }

              """,
            """
              plugins {
                  id "java"
                  id "jacoco"
              }

              jacocoTestReport {
                  reports {
                      xml {
                          required = false
                      }
                      csv {
                          required = false
                      }
                      html {
                          required = false
                      }
                  }
              }

              """
          )
        );
    }

    @Test
    void enabledDeprecatedInMixedSyntax() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id "java"
                  id "jacoco"
              }

              jacocoTestReport {
                  reports {
                      xml.enabled = false
                      csv.enabled = false
                      html {
                          enabled = false
                      }
                  }
              }

              """,
            """
              plugins {
                  id "java"
                  id "jacoco"
              }

              jacocoTestReport {
                  reports {
                      xml.required = false
                      csv.required = false
                      html {
                          required = false
                      }
                  }
              }

              """
          )
        );
    }

    @Test
    void enabledDeprecatedInSemiCollapsedSyntax() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id "java"
                  id "jacoco"
              }

              jacocoTestReport {
                  reports.xml.enabled = false
                  reports.csv.enabled = false
                  reports.html.enabled = false
              }

              """,
            """
              plugins {
                  id "java"
                  id "jacoco"
              }

              jacocoTestReport {
                  reports.xml.required = false
                  reports.csv.required = false
                  reports.html.required = false
              }

              """
          )
        );
    }

    @Test
    void enabledInAnotherExtensionNotTouched() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id "java"
              }

              tasks.register("example", JavaCompile) {
                   xml.enabled = false
                   jacocoTestReport.reports.html.enabled = false
              }

              """
          )
        );
    }

    @Test
    void destinationDeprecatedInCollapsedSyntax() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id "java"
                  id "jacoco"
              }

              jacocoTestReport.reports.xml.destination = layout.buildDirectory.dir('jacocoXml')
              jacocoTestReport.reports.csv.destination = layout.buildDirectory.dir('jacocoCsv')
              jacocoTestReport.reports.html.destination = layout.buildDirectory.dir('jacocoHtml')

              """,
            """
              plugins {
                  id "java"
                  id "jacoco"
              }

              jacocoTestReport.reports.xml.outputLocation = layout.buildDirectory.dir('jacocoXml')
              jacocoTestReport.reports.csv.outputLocation = layout.buildDirectory.dir('jacocoCsv')
              jacocoTestReport.reports.html.outputLocation = layout.buildDirectory.dir('jacocoHtml')

              """
          )
        );
    }

    @Test
    void destinationDeprecatedInElapsedSyntax() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id "java"
                  id "jacoco"
              }

              jacocoTestReport {
                  reports {
                      xml {
                          destination = layout.buildDirectory.dir('jacocoXml')
                      }
                      csv {
                          destination = layout.buildDirectory.dir('jacocoCsv')
                      }
                      html {
                          destination = layout.buildDirectory.dir('jacocoHtml')
                      }
                  }
              }
              """,
            """
              plugins {
                  id "java"
                  id "jacoco"
              }

              jacocoTestReport {
                  reports {
                      xml {
                          outputLocation = layout.buildDirectory.dir('jacocoXml')
                      }
                      csv {
                          outputLocation = layout.buildDirectory.dir('jacocoCsv')
                      }
                      html {
                          outputLocation = layout.buildDirectory.dir('jacocoHtml')
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void destinationDeprecatedInMixedSyntax() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id "java"
                  id "jacoco"
              }

              jacocoTestReport {
                  reports {
                      xml.destination = layout.buildDirectory.dir('jacocoXml')
                      csv.destination = layout.buildDirectory.dir('jacocoCsv')
                      html {
                          destination = layout.buildDirectory.dir('jacocoHtml')
                      }
                  }
              }

              """,
            """
              plugins {
                  id "java"
                  id "jacoco"
              }

              jacocoTestReport {
                  reports {
                      xml.outputLocation = layout.buildDirectory.dir('jacocoXml')
                      csv.outputLocation = layout.buildDirectory.dir('jacocoCsv')
                      html {
                          outputLocation = layout.buildDirectory.dir('jacocoHtml')
                      }
                  }
              }

              """
          )
        );
    }

    @Test
    void destinationDeprecatedInSemiCollapsedSyntax() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id "java"
                  id "jacoco"
              }

              jacocoTestReport {
                  reports.xml.destination = layout.buildDirectory.dir('jacocoXml')
                  reports.csv.destination = layout.buildDirectory.dir('jacocoCsv')
                  reports.html.destination = layout.buildDirectory.dir('jacocoHtml')
              }

              """,
            """
              plugins {
                  id "java"
                  id "jacoco"
              }

              jacocoTestReport {
                  reports.xml.outputLocation = layout.buildDirectory.dir('jacocoXml')
                  reports.csv.outputLocation = layout.buildDirectory.dir('jacocoCsv')
                  reports.html.outputLocation = layout.buildDirectory.dir('jacocoHtml')
              }

              """
          )
        );
    }

    @Test
    void destinationInAnotherExtensionNotTouched() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id "java"
              }

              tasks.register("example", JavaCompile) {
                   xml.destination = false
                   jacocoTestReport.reports.html.destination = layout.buildDirectory.dir('jacocoHtml')
              }

              """
          )
        );
    }

    @Test
    void alsoWorkOnKotlinDsl() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  java
                  jacoco
              }

              tasks.jacocoTestReport {
                  reports {
                      xml.isEnabled = false
                      html.isEnabled(true)
                      html.destination = layout.buildDirectory.dir("jacocoHtml")
                  }
              }
              """,
            """
              plugins {
                  java
                  jacoco
              }

              tasks.jacocoTestReport {
                  reports {
                      xml.required = false
                      html.required = true
                      html.outputLocation = layout.buildDirectory.dir("jacocoHtml")
                  }
              }
              """
          )
        );
    }

    @Nested
    class OtherReportingTasks {
        @Test
        void testTaskInGroovy() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id "java"
                  }

                  test {
                      reports.html.enabled = false
                      reports.junitXml.destination = file("out")
                      reports {
                          junitXml.enabled false
                          html {
                              enabled = true
                              destination file("html")
                          }
                      }
                  }
                  """,
                """
                  plugins {
                      id "java"
                  }

                  test {
                      reports.html.required = false
                      reports.junitXml.outputLocation = file("out")
                      reports {
                          junitXml.required = false
                          html {
                              required = true
                              outputLocation = file("html")
                          }
                      }
                  }
                  """
              )
            );
        }

        @Test
        void taskContainerOwnersInGroovy() {
            rewriteRun(
              buildGradle(
                """
                  task integrationTest(type: Test) {
                      reports.html.enabled = false
                  }
                  integrationTest {
                      reports.xml.enabled = false
                  }
                  tasks.withType(Test) {
                      reports.html.enabled = false
                  }
                  tasks.withType(Test).configureEach {
                      reports.html.enabled = false
                  }
                  tasks.named('test') {
                      reports.html.enabled = false
                  }
                  tasks.named('jacocoTestReport', JacocoReport) {
                      reports.html.enabled = false
                  }
                  tasks.register('t', Test) {
                      reports.html.enabled = false
                  }
                  test.reports.html.enabled = false
                  tasks.test.reports.html.destination = file('x')
                  subprojects {
                      tasks.withType(Test) {
                          reports {
                              html.enabled = false
                          }
                      }
                  }
                  """,
                """
                  task integrationTest(type: Test) {
                      reports.html.required = false
                  }
                  integrationTest {
                      reports.xml.required = false
                  }
                  tasks.withType(Test) {
                      reports.html.required = false
                  }
                  tasks.withType(Test).configureEach {
                      reports.html.required = false
                  }
                  tasks.named('test') {
                      reports.html.required = false
                  }
                  tasks.named('jacocoTestReport', JacocoReport) {
                      reports.html.required = false
                  }
                  tasks.register('t', Test) {
                      reports.html.required = false
                  }
                  test.reports.html.required = false
                  tasks.test.reports.html.outputLocation = file('x')
                  subprojects {
                      tasks.withType(Test) {
                          reports {
                              html.required = false
                          }
                      }
                  }
                  """
              )
            );
        }

        @Test
        void qualityPluginsInGroovy() {
            rewriteRun(
              buildGradle(
                """
                  checkstyleMain {
                      reports {
                          xml.enabled = false
                          html.destination = file('c.html')
                      }
                  }
                  pmdTest {
                      reports.html.enabled = true
                  }
                  codenarcMain {
                      reports {
                          text.enabled = true
                      }
                  }
                  spotbugsMain {
                      reports {
                          html {
                              enabled = true
                              destination = file('s.html')
                          }
                          sarif.enabled = false
                      }
                  }
                  tasks.withType(Checkstyle) {
                      reports {
                          sarif.enabled = true
                      }
                  }
                  tasks.withType(Pmd) {
                      reports.xml.enabled = false
                  }
                  tasks.withType(CodeNarc) {
                      reports.console.enabled = false
                  }
                  tasks.withType(com.github.spotbugs.snom.SpotBugsTask) {
                      reports.xml.enabled = false
                  }
                  """,
                """
                  checkstyleMain {
                      reports {
                          xml.required = false
                          html.outputLocation = file('c.html')
                      }
                  }
                  pmdTest {
                      reports.html.required = true
                  }
                  codenarcMain {
                      reports {
                          text.required = true
                      }
                  }
                  spotbugsMain {
                      reports {
                          html {
                              required = true
                              outputLocation = file('s.html')
                          }
                          sarif.required = false
                      }
                  }
                  tasks.withType(Checkstyle) {
                      reports {
                          sarif.required = true
                      }
                  }
                  tasks.withType(Pmd) {
                      reports.xml.required = false
                  }
                  tasks.withType(CodeNarc) {
                      reports.console.required = false
                  }
                  tasks.withType(com.github.spotbugs.snom.SpotBugsTask) {
                      reports.xml.required = false
                  }
                  """
              )
            );
        }

        @Test
        void everyReport() {
            rewriteRun(
              buildGradle(
                """
                  checkstyleMain {
                      reports.all {
                          enabled = false
                      }
                      reports.all { report ->
                          report.enabled = true
                      }
                      reports.each {
                          it.enabled = true
                      }
                      reports {
                          all {
                              enabled = false
                          }
                      }
                  }
                  """,
                """
                  checkstyleMain {
                      reports.all {
                          required = false
                      }
                      reports.all { report ->
                          report.required = true
                      }
                      reports.each {
                          it.required = true
                      }
                      reports {
                          all {
                              required = false
                          }
                      }
                  }
                  """
              )
            );
        }

        @Test
        void closuresOnReportsContainer() {
            rewriteRun(
              buildGradle(
                """
                  test {
                      reports.html {
                          enabled = false
                          destination = file('h')
                      }
                  }
                  spotbugsMain {
                      reports.create("html") {
                          enabled = true
                      }
                  }
                  """,
                """
                  test {
                      reports.html {
                          required = false
                          outputLocation = file('h')
                      }
                  }
                  spotbugsMain {
                      reports.create("html") {
                          required = true
                      }
                  }
                  """
              )
            );
        }

        @Test
        void kotlinTaskOwners() {
            rewriteRun(
              buildGradleKts(
                """
                  plugins {
                      java
                      checkstyle
                  }

                  tasks.test {
                      reports.html.isEnabled = false
                      reports {
                          junitXml.isEnabled = true
                          html.setDestination(file("o"))
                      }
                  }
                  tasks.withType<Test> {
                      reports.html.isEnabled = false
                  }
                  tasks.withType<Checkstyle>().configureEach {
                      reports {
                          xml.isEnabled = false
                          html.destination = file("o")
                      }
                  }
                  tasks.named<Test>("test") {
                      reports.junitXml.isEnabled = false
                  }
                  tasks.register<Test>("it") {
                      reports.html.isEnabled = false
                  }
                  tasks {
                      test {
                          reports.html.isEnabled = false
                      }
                  }
                  """,
                """
                  plugins {
                      java
                      checkstyle
                  }

                  tasks.test {
                      reports.html.required = false
                      reports {
                          junitXml.required = true
                          html.outputLocation = file("o")
                      }
                  }
                  tasks.withType<Test> {
                      reports.html.required = false
                  }
                  tasks.withType<Checkstyle>().configureEach {
                      reports {
                          xml.required = false
                          html.outputLocation = file("o")
                      }
                  }
                  tasks.named<Test>("test") {
                      reports.junitXml.required = false
                  }
                  tasks.register<Test>("it") {
                      reports.html.required = false
                  }
                  tasks {
                      test {
                          reports.html.required = false
                      }
                  }
                  """
              )
            );
        }

        @Test
        void stringDestinationIsWrappedInFile() {
            rewriteRun(
              buildGradle(
                """
                  test {
                      reports.html.destination = "$buildDir/out"
                      reports {
                          junitXml.destination 'junit'
                      }
                  }
                  """,
                """
                  test {
                      reports.html.outputLocation = file("$buildDir/out")
                      reports {
                          junitXml.outputLocation = file('junit')
                      }
                  }
                  """
              )
            );
        }

        @Test
        void doNotChangeEnabledOutsideOfReports() {
            rewriteRun(
              buildGradle(
                """
                  test {
                      enabled = false
                      html.enabled = false
                  }
                  jacocoTestReport.enabled = false
                  def task = tasks.getByName('test')
                  task.enabled = false
                  tasks.withType(Test) {
                      enabled = false
                      setEnabled(false)
                  }
                  checkstyleMain {
                      enabled = true
                      destination = file('x')
                  }
                  reports.html.enabled = false
                  html.enabled = false
                  """
              ),
              buildGradleKts(
                """
                  tasks.test {
                      enabled = false
                      isEnabled = false
                  }
                  """,
                spec -> spec.path("sub/build.gradle.kts")
              )
            );
        }

        @Test
        void doNotChangeReportsOfOtherOwners() {
            rewriteRun(
              buildGradle(
                """
                  detekt {
                      reports {
                          html {
                              enabled = true
                              destination = file('x')
                          }
                          xml.enabled = false
                      }
                  }
                  kover {
                      reports.html.enabled = false
                  }
                  tasks.withType(Foo) {
                      reports {
                          html.enabled = false
                      }
                  }
                  task custom(type: Foo) {
                      reports.html.enabled = false
                  }
                  ext.reports.html.enabled = false
                  testing {
                      suites {
                          test {
                              reports.html.enabled = false
                          }
                      }
                  }
                  """
              )
            );
        }

        @Test
        void doNotChangeOtherPropertiesOfReports() {
            rewriteRun(
              buildGradle(
                """
                  test {
                      reports {
                          html {
                              required = true
                              outputLocation = file('x')
                              stylesheet = 'x'
                          }
                          foo.enabled = false
                          foo {
                              enabled = false
                          }
                      }
                      reports.foo.enabled = false
                  }
                  """
              )
            );
        }

        @Test
        void doNotChangeReads() {
            rewriteRun(
              buildGradle(
                """
                  def isOn = test.reports.html.enabled
                  test {
                      reports {
                          println(html.destination)
                      }
                  }
                  """
              )
            );
        }

        @Test
        void doNotChangeScriptVariable() {
            rewriteRun(
              buildGradle(
                """
                  def reports = [:]
                  test {
                      reports.html.enabled = false
                  }
                  """
              )
            );
        }
    }

    @Nested
    class MethodInvocationToAssignments {
        @Test
        void deprecationsInMethodInvocationSyntax() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id "java"
                      id "jacoco"
                  }
    
                  jacocoTestReport {
                      reports {
                          xml.enabled false
                          csv.enabled true
                          html.enabled false
                      }
                  }
                  """,
                """
                  plugins {
                      id "java"
                      id "jacoco"
                  }
    
                  jacocoTestReport {
                      reports {
                          xml.required = false
                          csv.required = true
                          html.required = false
                      }
                  }
                  """
              )
            );
        }

        @Test
        void deprecationsInMethodInvocationInsideNestedClosure() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id "java"
                      id "jacoco"
                  }
                  jacocoTestReport {
                      reports {
                          xml {
                              enabled false
                          }
                          csv {
                              enabled true
                          }
                          html {
                              enabled false
                          }
                      }
                  }
                  """,
                """
                  plugins {
                      id "java"
                      id "jacoco"
                  }
                  jacocoTestReport {
                      reports {
                          xml {
                              required = false
                          }
                          csv {
                              required = true
                          }
                          html {
                              required = false
                          }
                      }
                  }
                  """
              )
            );
        }

        @Test
        void deprecationsInMethodInvocationWithFieldAccess() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id "java"
                      id "jacoco"
                  }
                  jacocoTestReport {
                      reports.xml.enabled false
                      reports.csv.enabled true
                      reports.html.enabled false
                  }
                  """,
                """
                  plugins {
                      id "java"
                      id "jacoco"
                  }
                  jacocoTestReport {
                      reports.xml.required = false
                      reports.csv.required = true
                      reports.html.required = false
                  }
                  """
              )
            );
        }

        @Test
        void destinationInMethodInvocationSyntax() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id "java"
                      id "jacoco"
                  }
                  jacocoTestReport {
                      reports {
                          xml.destination layout.buildDirectory.dir('jacocoXml')
                          csv.destination layout.buildDirectory.dir('jacocoCsv')
                          html.destination layout.buildDirectory.dir('jacocoHtml')
                      }
                  }
                  """,
                """
                  plugins {
                      id "java"
                      id "jacoco"
                  }
                  jacocoTestReport {
                      reports {
                          xml.outputLocation = layout.buildDirectory.dir('jacocoXml')
                          csv.outputLocation = layout.buildDirectory.dir('jacocoCsv')
                          html.outputLocation = layout.buildDirectory.dir('jacocoHtml')
                      }
                  }
                  """
              )
            );
        }

        @Test
        void destinationInMethodInvocationInsideNestedClosure() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id "java"
                      id "jacoco"
                  }
                  jacocoTestReport {
                      reports {
                          xml {
                              destination layout.buildDirectory.dir('jacocoXml')
                          }
                          csv {
                              destination layout.buildDirectory.dir('jacocoCsv')
                          }
                          html {
                              destination layout.buildDirectory.dir('jacocoHtml')
                          }
                      }
                  }
                  """,
                """
                  plugins {
                      id "java"
                      id "jacoco"
                  }
                  jacocoTestReport {
                      reports {
                          xml {
                              outputLocation = layout.buildDirectory.dir('jacocoXml')
                          }
                          csv {
                              outputLocation = layout.buildDirectory.dir('jacocoCsv')
                          }
                          html {
                              outputLocation = layout.buildDirectory.dir('jacocoHtml')
                          }
                      }
                  }
                  """
              )
            );
        }
    }

    @Test
    void allNotationsMixedFromReports() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id "java"
                  id "jacoco"
              }

              jacocoTestReport {
                  reports {
                      xml.enabled = true
                      xml.setEnabled(false)
                      xml.enabled(true)
                      xml.enabled false
                      xml.destination = layout.buildDirectory.dir('jacocoXml')
                      xml.setDestination layout.buildDirectory.dir('jacocoXml2')
                      xml.destination(layout.buildDirectory.dir('jacocoXml3'))
                  }
              }
              """,
            """
              plugins {
                  id "java"
                  id "jacoco"
              }

              jacocoTestReport {
                  reports {
                      xml.required = true
                      xml.required = false
                      xml.required = true
                      xml.required = false
                      xml.outputLocation = layout.buildDirectory.dir('jacocoXml')
                      xml.outputLocation = layout.buildDirectory.dir('jacocoXml2')
                      xml.outputLocation = layout.buildDirectory.dir('jacocoXml3')
                  }
              }
              """
          )
        );
    }

    @Test
    void allNotationsMixedFromReportsXml() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id "java"
                  id "jacoco"
              }

              jacocoTestReport {
                  reports {
                      xml {
                          enabled = true
                          setEnabled(false)
                          enabled(true)
                          enabled false
                          destination = layout.buildDirectory.dir('jacocoXml')
                          setDestination layout.buildDirectory.dir('jacocoXml2')
                          destination(layout.buildDirectory.dir('jacocoXml3'))
                      }
                  }
              }
              """,
            """
              plugins {
                  id "java"
                  id "jacoco"
              }

              jacocoTestReport {
                  reports {
                      xml {
                          required = true
                          required = false
                          required = true
                          required = false
                          outputLocation = layout.buildDirectory.dir('jacocoXml')
                          outputLocation = layout.buildDirectory.dir('jacocoXml2')
                          outputLocation = layout.buildDirectory.dir('jacocoXml3')
                      }
                  }
              }
              """
          )
        );
    }
}
