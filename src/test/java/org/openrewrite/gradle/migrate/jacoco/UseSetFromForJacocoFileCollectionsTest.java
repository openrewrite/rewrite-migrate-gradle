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
package org.openrewrite.gradle.migrate.jacoco;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;

class UseSetFromForJacocoFileCollectionsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseSetFromForJacocoFileCollections());
    }

    @DocumentExample
    @Test
    void assignmentInReportTask() {
        rewriteRun(
          buildGradle(
            """
              jacocoTestReport {
                  afterEvaluate {
                      classDirectories = files(classDirectories.files.collect {
                          fileTree(dir: it, exclude: 'com/acme/generated/**')
                      })
                  }
                  executionData = files('a.exec')
                  sourceDirectories = files('src')
                  additionalClassDirs = files('a')
                  additionalSourceDirs = files('b')
              }
              """,
            """
              jacocoTestReport {
                  afterEvaluate {
                      classDirectories.setFrom(files(classDirectories.files.collect {
                          fileTree(dir: it, exclude: 'com/acme/generated/**')
                      }))
                  }
                  executionData.setFrom(files('a.exec'))
                  sourceDirectories.setFrom(files('src'))
                  additionalClassDirs.setFrom(files('a'))
                  additionalSourceDirs.setFrom(files('b'))
              }
              """
          )
        );
    }

    @Test
    void lastStatementOfBlock() {
        rewriteRun(
          buildGradle(
            """
              jacocoTestCoverageVerification {
                  classDirectories = files('a')
              }
              """,
            """
              jacocoTestCoverageVerification {
                  classDirectories.setFrom(files('a'))
              }
              """
          )
        );
    }

    @Test
    void taskTypes() {
        rewriteRun(
          buildGradle(
            """
              task report(type: JacocoReport) {
                  classDirectories = files('a')
              }
              tasks.register('verify', JacocoCoverageVerification) {
                  classDirectories = files('b')
              }
              tasks.withType(JacocoReport) {
                  executionData = files('c')
              }
              tasks.named('jacocoTestReport') {
                  sourceDirectories = files('d')
              }
              tasks.withType(JacocoReport).configureEach {
                  executionData = files('e')
              }
              """,
            """
              task report(type: JacocoReport) {
                  classDirectories.setFrom(files('a'))
              }
              tasks.register('verify', JacocoCoverageVerification) {
                  classDirectories.setFrom(files('b'))
              }
              tasks.withType(JacocoReport) {
                  executionData.setFrom(files('c'))
              }
              tasks.named('jacocoTestReport') {
                  sourceDirectories.setFrom(files('d'))
              }
              tasks.withType(JacocoReport).configureEach {
                  executionData.setFrom(files('e'))
              }
              """
          )
        );
    }

    @Test
    void qualifiedAndSetterForms() {
        rewriteRun(
          buildGradle(
            """
              jacocoTestReport.classDirectories = files('a')
              tasks.jacocoTestReport.executionData = files('b')
              jacocoTestReport {
                  setSourceDirectories(files('c'))
              }
              """,
            """
              jacocoTestReport.classDirectories.setFrom(files('a'))
              tasks.jacocoTestReport.executionData.setFrom(files('b'))
              jacocoTestReport {
                  sourceDirectories.setFrom(files('c'))
              }
              """
          )
        );
    }

    @Test
    void kotlin() {
        rewriteRun(
          buildGradleKts(
            """
              tasks.jacocoTestReport {
                  classDirectories = files("a")
              }
              tasks.withType<JacocoReport> {
                  executionData = files("b")
              }
              tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
                  sourceDirectories = files("c")
              }
              """,
            """
              tasks.jacocoTestReport {
                  classDirectories.setFrom(files("a"))
              }
              tasks.withType<JacocoReport> {
                  executionData.setFrom(files("b"))
              }
              tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
                  sourceDirectories.setFrom(files("c"))
              }
              """
          )
        );
    }

    @Test
    void unchangedOutsideJacocoTasks() {
        rewriteRun(
          buildGradle(
            """
              def classDirectories = files('a')
              ext.executionData = files('b')
              myTask {
                  classDirectories = files('c')
                  sourceDirectories = files('d')
              }
              task other(type: Copy) {
                  sourceDirectories = files('e')
              }
              sourceDirectories = files('f')
              """
          )
        );
    }

    @Test
    void unchangedScriptVariable() {
        rewriteRun(
          buildGradle(
            """
              jacocoTestReport {
                  def executionData = files('a')
                  executionData = files('b')
                  classDirectories.setFrom(executionData)
              }
              """
          )
        );
    }

    @Test
    void unchangedAlreadySetFromOrAdditive() {
        rewriteRun(
          buildGradle(
            """
              jacocoTestReport {
                  classDirectories.setFrom(files('a'))
                  executionData.from(files('b'))
                  additionalSourceDirs(files('c'))
              }
              """
          )
        );
    }

    @Test
    void unchangedOtherPropertiesOfTask() {
        rewriteRun(
          buildGradle(
            """
              jacocoTestReport {
                  group = 'verification'
                  reports.xml.required = true
              }
              """
          )
        );
    }
}
