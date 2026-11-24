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
package org.openrewrite.gradle.migrate.quality;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.gradle.migrate.GradleProjectMarkers;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;

class UpgradeSpotBugsToolVersionTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UpgradeSpotBugsToolVersion("4.9.x"))
          .allSources(source -> source.markers(GradleProjectMarkers.project()));
    }

    @DocumentExample
    @Test
    void upgradeAssignedToolVersion() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
                  id 'com.github.spotbugs' version '5.2.5'
              }

              spotbugs {
                  toolVersion = '4.8.3'
              }
              """,
            spec -> spec.after(actual -> assertThat(actual)
              .containsPattern("spotbugs \\{\n    toolVersion = '4\\.9\\.\\d+'\n}")
              .actual())
          )
        );
    }

    @Test
    void upgradeToolVersionMethodCall() {
        rewriteRun(
          buildGradle(
            """
              spotbugs {
                  toolVersion '4.8.3'
              }
              """,
            spec -> spec.after(actual -> assertThat(actual)
              .containsPattern("toolVersion '4\\.9\\.\\d+'")
              .actual())
          )
        );
    }

    @Test
    void upgradeExtensionPropertyOutsideBlock() {
        rewriteRun(
          buildGradle(
            """
              spotbugs.toolVersion = '4.8.3'
              """,
            spec -> spec.after(actual -> assertThat(actual)
              .containsPattern("spotbugs.toolVersion = '4\\.9\\.\\d+'")
              .actual())
          )
        );
    }

    @Test
    void upgradeKotlinPropertySet() {
        rewriteRun(
          buildGradleKts(
            """
              spotbugs {
                  toolVersion.set("4.8.3")
              }
              """,
            spec -> spec.after(actual -> assertThat(actual)
              .containsPattern("toolVersion\\.set\\(\"4\\.9\\.\\d+\"\\)")
              .actual())
          )
        );
    }

    @Test
    void upgradeToolVersionInSubprojects() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'com.github.spotbugs' version '5.2.5' apply false
              }

              subprojects {
                  apply plugin: 'com.github.spotbugs'
                  spotbugs {
                      toolVersion = '4.8.3'
                  }
              }
              """,
            spec -> spec.after(actual -> assertThat(actual)
              .containsPattern("toolVersion = '4\\.9\\.\\d+'")
              .actual())
          )
        );
    }

    @Test
    void addToolVersionForOlderPluginDefault() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
                  id 'com.github.spotbugs' version '5.2.5'
              }
              """,
            spec -> spec.after(actual -> assertThat(actual)
              .matches("""
                plugins \\{
                    id 'java'
                    id 'com.github.spotbugs' version '5.2.5'
                }

                spotbugs \\{
                    toolVersion = '4\\.9\\.\\d+'
                }\
                """)
              .actual())
          )
        );
    }

    @Test
    void addToolVersionToExistingBlock() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'com.github.spotbugs' version '5.2.5'
              }

              spotbugs {
                  ignoreFailures = true
              }
              """,
            spec -> spec.after(actual -> assertThat(actual)
              .containsPattern("spotbugs \\{\n    ignoreFailures = true\n    toolVersion = '4\\.9\\.\\d+'\n}")
              .actual())
          )
        );
    }

    @Test
    void addToolVersionInKotlin() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  id("com.github.spotbugs") version "5.2.5"
              }
              """,
            spec -> spec.after(actual -> assertThat(actual)
              .matches("""
                plugins \\{
                    id\\("com.github.spotbugs"\\) version "5.2.5"
                }

                spotbugs \\{
                    toolVersion = "4\\.9\\.\\d+"
                }\
                """)
              .actual())
          )
        );
    }

    @Nested
    class NoChange {
        @Test
        void unchangedWhenPluginDefaultReadsJava25() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'com.github.spotbugs' version '6.2.4'
                  }
                  """
              )
            );
        }

        @Test
        void unchangedWhenPluginNotApplied() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'com.github.spotbugs' version '5.2.5' apply false
                  }

                  subprojects {
                      apply plugin: 'com.github.spotbugs'
                  }
                  """
              )
            );
        }

        @Test
        void unchangedNewerToolVersion() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'com.github.spotbugs' version '5.2.5'
                  }

                  spotbugs {
                      toolVersion = '5.0.0'
                  }
                  """
              )
            );
        }

        @Test
        void unchangedComputedToolVersion() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'com.github.spotbugs' version '5.2.5'
                  }

                  spotbugs {
                      toolVersion = spotbugsVersion
                  }
                  """
              )
            );
        }

        @Test
        void unchangedToolVersionOfOtherExtension() {
            rewriteRun(
              buildGradle(
                """
                  checkstyle {
                      toolVersion = '8.45'
                  }
                  """
              )
            );
        }
    }
}
