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
package org.openrewrite.gradle.migrate.artifacts;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.*;

class UseMavenCentralInsteadOfJcenterTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseMavenCentralInsteadOfJcenter());
    }

    @DocumentExample
    @Test
    void renameWhenOnlyRepository() {
        rewriteRun(
          buildGradle(
            """
              repositories {
                  jcenter()
              }
              """,
            """
              repositories {
                  mavenCentral()
              }
              """
          )
        );
    }

    @Test
    void renameKeepsPosition() {
        rewriteRun(
          buildGradle(
            """
              repositories {
                  google()
                  jcenter()
                  maven { url = 'https://repo.example.org' }
              }
              """,
            """
              repositories {
                  google()
                  mavenCentral()
                  maven { url = 'https://repo.example.org' }
              }
              """
          )
        );
    }

    @Test
    void deleteWhenMavenCentralFollows() {
        rewriteRun(
          buildGradle(
            """
              repositories {
                  jcenter()
                  mavenCentral()
              }
              """,
            """
              repositories {
                  mavenCentral()
              }
              """
          )
        );
    }

    @Test
    void deleteWhenLastAfterMavenCentral() {
        rewriteRun(
          buildGradle(
            """
              repositories {
                  mavenCentral()
                  jcenter()
              }
              """,
            """
              repositories {
                  mavenCentral()
              }
              """
          )
        );
    }

    @Test
    void twoJcentersBecomeOne() {
        rewriteRun(
          buildGradle(
            """
              repositories {
                  jcenter()
                  jcenter()
              }
              """,
            """
              repositories {
                  mavenCentral()
              }
              """
          )
        );
    }

    @Test
    void renameConfiguredJcenterAlongsideMavenCentral() {
        rewriteRun(
          buildGradle(
            """
              repositories {
                  mavenCentral()
                  jcenter {
                      content {
                          includeGroup 'org.example'
                      }
                  }
              }
              """,
            """
              repositories {
                  mavenCentral()
                  mavenCentral {
                      content {
                          includeGroup 'org.example'
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void renameWhenSiblingMavenCentralIsFiltered() {
        rewriteRun(
          buildGradle(
            """
              repositories {
                  mavenCentral {
                      content {
                          includeGroup 'org.example'
                      }
                  }
                  jcenter()
              }
              """,
            """
              repositories {
                  mavenCentral {
                      content {
                          includeGroup 'org.example'
                      }
                  }
                  mavenCentral()
              }
              """
          )
        );
    }

    @Test
    void eachRepositoriesBlockStandsAlone() {
        rewriteRun(
          buildGradle(
            """
              buildscript {
                  repositories {
                      jcenter()
                  }
              }

              repositories {
                  mavenCentral()
                  jcenter()
              }
              """,
            """
              buildscript {
                  repositories {
                      mavenCentral()
                  }
              }

              repositories {
                  mavenCentral()
              }
              """
          )
        );
    }

    @Test
    void subprojects() {
        rewriteRun(
          buildGradle(
            """
              subprojects {
                  repositories {
                      jcenter()
                  }
              }
              """,
            """
              subprojects {
                  repositories {
                      mavenCentral()
                  }
              }
              """
          )
        );
    }

    @Test
    void qualifiedCall() {
        rewriteRun(
          buildGradle(
            """
              repositories.jcenter()
              project.repositories.jcenter()
              """,
            """
              repositories.mavenCentral()
              project.repositories.mavenCentral()
              """
          )
        );
    }

    @Test
    void settingsScript() {
        rewriteRun(
          settingsGradle(
            """
              pluginManagement {
                  repositories {
                      gradlePluginPortal()
                      jcenter()
                  }
              }
              dependencyResolutionManagement {
                  repositories {
                      jcenter()
                      mavenCentral()
                  }
              }
              """,
            """
              pluginManagement {
                  repositories {
                      gradlePluginPortal()
                      mavenCentral()
                  }
              }
              dependencyResolutionManagement {
                  repositories {
                      mavenCentral()
                  }
              }
              """
          )
        );
    }

    @Test
    void kotlinRename() {
        rewriteRun(
          buildGradleKts(
            """
              repositories {
                  jcenter()
              }
              """,
            """
              repositories {
                  mavenCentral()
              }
              """
          )
        );
    }

    @Test
    void kotlinDelete() {
        rewriteRun(
          buildGradleKts(
            """
              repositories {
                  mavenCentral()
                  jcenter()
              }
              """,
            """
              repositories {
                  mavenCentral()
              }
              """
          )
        );
    }

    @Test
    void kotlinSettingsScript() {
        rewriteRun(
          settingsGradleKts(
            """
              dependencyResolutionManagement {
                  repositories {
                      jcenter()
                  }
              }
              """,
            """
              dependencyResolutionManagement {
                  repositories {
                      mavenCentral()
                  }
              }
              """
          )
        );
    }

    @Nested
    class NoChange {
        @Test
        void alreadyMavenCentral() {
            rewriteRun(
              buildGradle(
                """
                  repositories {
                      mavenCentral()
                  }
                  """
              )
            );
        }

        @Test
        void jcenterOutsideRepositories() {
            rewriteRun(
              buildGradle(
                """
                  def jcenter() { 'not a repository' }
                  println jcenter()
                  """
              )
            );
        }

        @Test
        void qualifiedByAnotherReceiver() {
            rewriteRun(
              buildGradle(
                """
                  repositories {
                      helper.jcenter()
                  }
                  """
              )
            );
        }
    }
}
