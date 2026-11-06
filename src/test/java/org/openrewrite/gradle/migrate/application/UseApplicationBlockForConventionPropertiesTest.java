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
package org.openrewrite.gradle.migrate.application;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;

class UseApplicationBlockForConventionPropertiesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseApplicationBlockForConventionProperties());
    }

    @DocumentExample
    @Test
    void topLevelGroovy() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'application'
              }

              applicationDefaultJvmArgs = ['-Dgreeting.language=en']
              """,
            """
              plugins {
                  id 'application'
              }

              application {
                  applicationDefaultJvmArgs = ['-Dgreeting.language=en']
              }
              """
          )
        );
    }

    @Test
    void topLevelKotlin() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  application
              }

              applicationDefaultJvmArgs = listOf("-Dgreeting.language=en")
              """,
            """
              plugins {
                  application
              }

              application {
                  applicationDefaultJvmArgs = listOf("-Dgreeting.language=en")
              }
              """
          )
        );
    }

    @Test
    void kotlinWithoutPluginsBlockHasNoAccessor() {
        rewriteRun(
          buildGradleKts(
            """
              apply(plugin = "application")

              applicationDefaultJvmArgs = listOf("-Dx=1")
              """,
            """
              apply(plugin = "application")

              configure<JavaApplication> {
                  applicationDefaultJvmArgs = listOf("-Dx=1")
              }
              """
          )
        );
    }

    @Test
    void kotlinInsideSubprojectsHasNoAccessor() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  application
              }

              subprojects {
                  apply(plugin = "application")
                  applicationDefaultJvmArgs = listOf("-Dx=1")
              }
              """,
            """
              plugins {
                  application
              }

              subprojects {
                  apply(plugin = "application")
                  configure<JavaApplication> {
                      applicationDefaultJvmArgs = listOf("-Dx=1")
                  }
              }
              """
          )
        );
    }

    @Test
    void projectReceiver() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'application'

              project.applicationDefaultJvmArgs = ['-Dx=1', '-Dy=2']
              """,
            """
              apply plugin: 'application'

              application {
                  applicationDefaultJvmArgs = ['-Dx=1', '-Dy=2']
              }
              """
          )
        );
    }

    @Test
    void lastStatementOfSubprojects() {
        rewriteRun(
          buildGradle(
            """
              subprojects {
                  apply plugin: 'application'
                  applicationDefaultJvmArgs = ["-Dx=${rootProject.name}"]
              }
              """,
            """
              subprojects {
                  apply plugin: 'application'
                  application {
                      applicationDefaultJvmArgs = ["-Dx=${rootProject.name}"]
                  }
              }
              """
          )
        );
    }

    @Test
    void insideConditional() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'application'

              if (hasProperty('debugJvm')) {
                  applicationDefaultJvmArgs = ['-Xdebug']
              }
              """,
            """
              apply plugin: 'application'

              if (hasProperty('debugJvm')) {
                  application {
                      applicationDefaultJvmArgs = ['-Xdebug']
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeInsideApplicationBlock() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'application'
              }

              application {
                  applicationDefaultJvmArgs = ['-Dx=1']
              }
              """
          )
        );
    }

    @Test
    void doNotChangeApplicationExtensionReceiver() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'application'
              }

              application.applicationDefaultJvmArgs = ['-Dx=1']
              """
          )
        );
    }

    @Test
    void doNotChangeOwnVariable() {
        rewriteRun(
          buildGradle(
            """
              def applicationDefaultJvmArgs = ['-Dx=1']
              applicationDefaultJvmArgs = ['-Dy=2']
              println applicationDefaultJvmArgs
              """
          )
        );
    }

    @Test
    void doNotChangeOwnKotlinVariable() {
        rewriteRun(
          buildGradleKts(
            """
              var applicationDefaultJvmArgs = listOf("-Dx=1")
              applicationDefaultJvmArgs = listOf("-Dy=2")
              println(applicationDefaultJvmArgs)
              """
          )
        );
    }

    @Test
    void doNotChangeExtraProperty() {
        rewriteRun(
          buildGradle(
            """
              ext.applicationDefaultJvmArgs = ['-Dx=1']
              applicationDefaultJvmArgs = ['-Dy=2']
              """
          )
        );
    }

    @Test
    void doNotChangeInsideTaskConfiguration() {
        rewriteRun(
          buildGradle(
            """
              tasks.register('custom') {
                  applicationDefaultJvmArgs = ['-Dx=1']
              }
              """
          )
        );
    }

    @Test
    void doNotChangeReadOrAppend() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'application'

              applicationDefaultJvmArgs += ['-Dx=1']
              println applicationDefaultJvmArgs
              """
          )
        );
    }

    @Test
    void doNotChangeOtherReceiver() {
        rewriteRun(
          buildGradle(
            """
              project(':app').applicationDefaultJvmArgs = ['-Dx=1']
              settings.applicationDefaultJvmArgs = ['-Dx=1']
              """
          )
        );
    }

    @Test
    void applicationNameMovesIntoTheBlock() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'application'
              }

              applicationName = rootProject.name
              """,
            """
              plugins {
                  id 'application'
              }

              application {
                  applicationName = rootProject.name
              }
              """
          )
        );
    }

    @Test
    void executableDirMovesIntoTheBlock() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'application'
              }

              executableDir = 'bin'
              """,
            """
              plugins {
                  id 'application'
              }

              application {
                  executableDir = 'bin'
              }
              """
          )
        );
    }

    @Test
    void shadowingOnePropertyLeavesTheOthersAlone() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'application'
              }

              def applicationName = 'mine'
              executableDir = 'bin'
              """,
            """
              plugins {
                  id 'application'
              }

              def applicationName = 'mine'
              application {
                  executableDir = 'bin'
              }
              """
          )
        );
    }
}
