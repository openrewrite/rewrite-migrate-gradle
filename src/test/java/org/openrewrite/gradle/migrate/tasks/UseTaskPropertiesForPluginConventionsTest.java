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

class UseTaskPropertiesForPluginConventionsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseTaskPropertiesForPluginConventions());
    }

    @DocumentExample
    @Test
    void webAppDirName() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'war'
              }

              webAppDirName = 'src/web'
              """,
            """
              plugins {
                  id 'war'
              }

              war {
                  webAppDirectory = file('src/web')
              }
              """
          )
        );
    }

    @Test
    void appDirName() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'ear'
              }

              appDirName = 'src/app'
              """,
            """
              plugins {
                  id 'ear'
              }

              ear {
                  appDirectory = file('src/app')
              }
              """
          )
        );
    }

    @Test
    void projectReportDirName() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'project-report'
              }

              projectReportDirName = "custom"
              """,
            """
              plugins {
                  id 'project-report'
              }

              tasks.withType(HtmlDependencyReportTask) {
                  projectReportDirectory = project.layout.buildDirectory.dir("reports/custom")
              }
              """
          )
        );
    }

    @Test
    void kotlinAccessors() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  war
              }

              webAppDirName = "src/web"
              """,
            """
              plugins {
                  war
              }

              tasks.war {
                  webAppDirectory.set(file("src/web"))
              }
              """
          ),
          buildGradleKts(
            """
              plugins {
                  id("ear")
              }

              appDirName = "src/app"
              """,
            """
              plugins {
                  id("ear")
              }

              tasks.ear {
                  appDirectory.set(file("src/app"))
              }
              """,
            spec -> spec.path("ear/build.gradle.kts")
          ),
          buildGradleKts(
            """
              plugins {
                  `project-report`
              }

              projectReportDirName = "custom"
              """,
            """
              plugins {
                  `project-report`
              }

              tasks.withType<HtmlDependencyReportTask> {
                  projectReportDirectory.set(project.layout.buildDirectory.dir("reports/custom"))
              }
              """,
            spec -> spec.path("report/build.gradle.kts")
          )
        );
    }

    @Test
    void kotlinWithoutAccessors() {
        rewriteRun(
          buildGradleKts(
            """
              apply(plugin = "war")

              webAppDirName = "src/web"
              """,
            """
              apply(plugin = "war")

              tasks.named<War>("war") {
                  webAppDirectory.set(file("src/web"))
              }
              """
          ),
          buildGradleKts(
            """
              plugins {
                  ear
              }

              subprojects {
                  apply(plugin = "ear")
                  appDirName = "src/app"
              }
              """,
            """
              plugins {
                  ear
              }

              subprojects {
                  apply(plugin = "ear")
                  tasks.named<Ear>("ear") {
                      appDirectory.set(file("src/app"))
                  }
              }
              """,
            spec -> spec.path("ear/build.gradle.kts")
          )
        );
    }

    @Test
    void customWarTaskKeepsFollowingTheDirectory() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'war'
              }

              webAppDirName = 'src/web'

              tasks.register('otherWar', War) {
                  archiveClassifier = 'other'
              }
              """,
            """
              plugins {
                  id 'war'
              }

              tasks.withType(War).configureEach {
                  webAppDirectory = file('src/web')
              }

              tasks.register('otherWar', War) {
                  archiveClassifier = 'other'
              }
              """
          )
        );
    }

    @Test
    void customEarTaskKotlin() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  ear
              }

              appDirName = "src/app"

              tasks.register<Ear>("otherEar") {
              }
              """,
            """
              plugins {
                  ear
              }

              tasks.withType<Ear>().configureEach {
                  appDirectory.set(file("src/app"))
              }

              tasks.register<Ear>("otherEar") {
              }
              """
          )
        );
    }

    @Test
    void projectReceiverInSubprojectsAsLastStatement() {
        rewriteRun(
          buildGradle(
            """
              subprojects {
                  apply plugin: 'war'
                  project.webAppDirName = "src/${project.name}"
              }
              """,
            """
              subprojects {
                  apply plugin: 'war'
                  war {
                      webAppDirectory = file("src/${project.name}")
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeNonLiteralProjectReportDirName() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'project-report'

              projectReportDirName = "custom-${version}"
              """
          )
        );
    }

    @Test
    void doNotChangeOwnVariableOrExtraProperty() {
        rewriteRun(
          buildGradle(
            """
              def appDirName = 'x'
              appDirName = 'y'
              ext.webAppDirName = 'z'
              webAppDirName = 'w'
              ext {
                  projectReportDirName = 'v'
              }
              projectReportDirName = 'u'
              """
          )
        );
    }

    @Test
    void doNotChangeInsideOtherBlocks() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'war'

              tasks.register('custom') {
                  webAppDirName = 'x'
              }
              someExtension.appDirName = 'y'
              println webAppDirName
              """
          )
        );
    }

    @Test
    void doNotChangeAlreadyMigrated() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'war'
              }

              war {
                  webAppDirectory = file('src/web')
              }
              """
          )
        );
    }

    @Test
    void libDirNameMovesIntoTheEarTask() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'ear'
              }

              libDirName = 'myLib'
              """,
            """
              plugins {
                  id 'ear'
              }

              ear {
                  libDirName = 'myLib'
              }
              """
          )
        );
    }

    @Test
    void libDirNameInKotlin() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  id("ear")
              }

              libDirName = "myLib"
              """,
            """
              plugins {
                  id("ear")
              }

              tasks.ear {
                  libDirName = "myLib"
              }
              """
          )
        );
    }

    @Test
    void doNotChangeAScriptsOwnLibDirName() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'ear'
              }

              def libDirName = 'mine'
              """
          )
        );
    }

    @Test
    void multiLineValueKeepsItsOwnIndentation() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'war'
              }

              webAppDirName = 'src/' +
                  'main/webapp'
              """,
            """
              plugins {
                  id 'war'
              }

              war {
                  webAppDirectory = file('src/' +
                      'main/webapp')
              }
              """
          )
        );
    }
}
