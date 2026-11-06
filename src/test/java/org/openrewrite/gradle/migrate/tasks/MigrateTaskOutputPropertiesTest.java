/*
 * Copyright 2026 the original author or authors.
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
package org.openrewrite.gradle.migrate.tasks;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;

class MigrateTaskOutputPropertiesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateTaskOutputProperties());
    }

    @DocumentExample
    @Test
    void allProperties() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
                  id 'jacoco'
              }

              jacoco {
                  reportsDir = file('j')
              }
              test {
                  binResultsDir = file('b')
              }
              task allTests(type: TestReport) {
                  destinationDir = file('d')
                  testResultDirs = files('r')
              }
              """,
            """
              plugins {
                  id 'java'
                  id 'jacoco'
              }

              jacoco {
                  reportsDirectory = file('j')
              }
              test {
                  binaryResultsDirectory = file('b')
              }
              task allTests(type: TestReport) {
                  destinationDirectory = file('d')
                  testResults.setFrom(files('r'))
              }
              """
          )
        );
    }

    @Test
    void methodForms() {
        rewriteRun(
          buildGradle(
            """
              jacoco {
                  reportsDir file('j')
              }
              test {
                  setBinResultsDir(file('b'))
              }
              tasks.withType(TestReport) {
                  destinationDir file('d')
                  testResultDirs files('r')
                  setTestResultDirs(files('s'))
              }
              """,
            """
              jacoco {
                  reportsDirectory = file('j')
              }
              test {
                  binaryResultsDirectory = file('b')
              }
              tasks.withType(TestReport) {
                  destinationDirectory = file('d')
                  testResults.setFrom(files('r'))
                  testResults.setFrom(files('s'))
              }
              """
          )
        );
    }

    @Test
    void testResultDirsKeepsValuesTheAssignmentWouldReject() {
        rewriteRun(
          buildGradle(
            """
              tasks.register('allTests', TestReport) {
                  testResultDirs = [file('r')]
              }
              """,
            """
              tasks.register('allTests', TestReport) {
                  testResults.setFrom([file('r')])
              }
              """
          )
        );
    }

    @Test
    void stringsAreWrappedInFile() {
        rewriteRun(
          buildGradle(
            """
              jacoco {
                  reportsDir = "$buildDir/j"
              }
              test {
                  binResultsDir 'b'
              }
              tasks.withType(TestReport) {
                  destinationDir = 'd'
              }
              """,
            """
              jacoco {
                  reportsDirectory = file("$buildDir/j")
              }
              test {
                  binaryResultsDirectory = file('b')
              }
              tasks.withType(TestReport) {
                  destinationDirectory = file('d')
              }
              """
          )
        );
    }

    @Test
    void writesThroughReference() {
        rewriteRun(
          buildGradle(
            """
              task allTests(type: TestReport)
              jacoco.reportsDir = file('j')
              project.jacoco.reportsDir = file('p')
              test.binResultsDir = file('b')
              allTests.destinationDir = file('d')
              allTests.testResultDirs = files('r')
              tasks.allTests.setDestinationDir(file('e'))
              """,
            """
              task allTests(type: TestReport)
              jacoco.reportsDirectory = file('j')
              project.jacoco.reportsDirectory = file('p')
              test.binaryResultsDirectory = file('b')
              allTests.destinationDirectory = file('d')
              allTests.testResults.setFrom(files('r'))
              tasks.allTests.destinationDirectory = file('e')
              """
          )
        );
    }

    @Test
    void kotlin() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  java
                  jacoco
              }

              jacoco {
                  reportsDir = file("j")
              }
              configure<JacocoPluginExtension> {
                  reportsDir = file("c")
              }
              tasks.test {
                  binResultsDir = file("b")
              }
              tasks.withType<Test> {
                  binResultsDir = file("t")
              }
              tasks.register<TestReport>("allTests") {
                  destinationDir = file("d")
                  testResultDirs = files("r")
              }
              tasks.withType<TestReport> {
                  setDestinationDir(file("e"))
              }
              """,
            """
              plugins {
                  java
                  jacoco
              }

              jacoco {
                  reportsDirectory = file("j")
              }
              configure<JacocoPluginExtension> {
                  reportsDirectory = file("c")
              }
              tasks.test {
                  binaryResultsDirectory = file("b")
              }
              tasks.withType<Test> {
                  binaryResultsDirectory = file("t")
              }
              tasks.register<TestReport>("allTests") {
                  destinationDirectory = file("d")
                  testResults.setFrom(files("r"))
              }
              tasks.withType<TestReport> {
                  destinationDirectory = file("e")
              }
              """
          )
        );
    }

    @Test
    void composedInGradle8Migration() {
        rewriteRun(
          spec -> spec.recipeFromResources("org.openrewrite.gradle.migrate.MigrateToGradle8"),
          buildGradle(
            """
              test {
                  binResultsDir = file('b')
              }
              """,
            """
              test {
                  binaryResultsDirectory = file('b')
              }
              """
          )
        );
    }

    @Test
    void doNotChangeSamePropertiesOnOtherOwners() {
        rewriteRun(
          buildGradle(
            """
              checkstyle {
                  reportsDir = file('c')
              }
              pmd {
                  reportsDir = file('p')
              }
              javadoc {
                  destinationDir = file('j')
              }
              tasks.withType(Javadoc) {
                  destinationDir = file('j')
              }
              tasks.withType(GroovyCompile) {
                  destinationDir = file('g')
              }
              jar {
                  destinationDir = file('a')
              }
              task docs(type: Foo) {
                  destinationDir = file('f')
                  binResultsDir = file('f')
                  testResultDirs = files('f')
              }
              reportsDir = file('x')
              binResultsDir = file('x')
              destinationDir = file('x')
              ext.reportsDir = file('x')
              custom {
                  test {
                      binResultsDir = file('x')
                  }
              }
              """
          ),
          buildGradleKts(
            """
              tasks.javadoc {
                  destinationDir = file("j")
              }
              checkstyle {
                  reportsDir = file("c")
              }
              """,
            spec -> spec.path("sub/build.gradle.kts")
          )
        );
    }

    @Test
    void doNotChangeTaskReportWhereTheTestTaskIsNotProven() {
        rewriteRun(
          buildGradle(
            """
              test {
                  destinationDir = file('d')
                  testResultDirs = files('r')
              }
              tasks.withType(TestReport) {
                  binResultsDir = file('b')
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
              def a = jacoco.reportsDir
              def b = test.binResultsDir
              task allTests(type: TestReport) {
                  from(destinationDir)
                  println(testResultDirs)
              }
              task run(type: Exec) {
                  doLast {
                      println(execResult.exitValue)
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeScriptVariables() {
        rewriteRun(
          buildGradle(
            """
              def binResultsDir = file('x')
              test {
                  binResultsDir = file('y')
              }
              """
          )
        );
    }
}
