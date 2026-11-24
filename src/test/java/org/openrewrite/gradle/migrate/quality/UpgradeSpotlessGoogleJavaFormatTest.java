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

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.gradle.migrate.GradleProjectMarkers;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;

class UpgradeSpotlessGoogleJavaFormatTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UpgradeSpotlessGoogleJavaFormat("1.27.x"))
          .allSources(source -> source.markers(GradleProjectMarkers.project()));
    }

    @DocumentExample
    @Test
    void pinDefaultOfOlderPlugin() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
                  id 'com.diffplug.spotless' version '6.25.0'
              }

              spotless {
                  java {
                      googleJavaFormat()
                  }
              }
              """,
            """
              plugins {
                  id 'java'
                  id 'com.diffplug.spotless' version '6.25.0'
              }

              spotless {
                  java {
                      googleJavaFormat('1.27.0')
                  }
              }
              """
          )
        );
    }

    @Test
    void upgradeExplicitVersion() {
        rewriteRun(
          buildGradle(
            """
              spotless {
                  java {
                      googleJavaFormat('1.17.0').aosp()
                  }
              }
              """,
            """
              spotless {
                  java {
                      googleJavaFormat('1.27.0').aosp()
                  }
              }
              """
          )
        );
    }

    @Test
    void pinChainedDefaultInKotlin() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  id("com.diffplug.spotless") version "7.2.1"
              }

              spotless {
                  java {
                      googleJavaFormat().reflowLongStrings()
                  }
              }
              """,
            """
              plugins {
                  id("com.diffplug.spotless") version "7.2.1"
              }

              spotless {
                  java {
                      googleJavaFormat("1.27.0").reflowLongStrings()
                  }
              }
              """
          )
        );
    }

    @Test
    void unchangedDefaultOfCurrentPlugin() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'com.diffplug.spotless' version '8.0.0'
              }

              spotless {
                  java {
                      googleJavaFormat()
                  }
              }
              """
          )
        );
    }

    @Test
    void unchangedDefaultWithoutDeclaredPluginVersion() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'com.diffplug.spotless'
              }

              spotless {
                  java {
                      googleJavaFormat()
                  }
              }
              """
          )
        );
    }

    @Test
    void unchangedNewerVersion() {
        rewriteRun(
          buildGradle(
            """
              spotless {
                  java {
                      googleJavaFormat('1.28.0')
                  }
              }
              """
          )
        );
    }
}
