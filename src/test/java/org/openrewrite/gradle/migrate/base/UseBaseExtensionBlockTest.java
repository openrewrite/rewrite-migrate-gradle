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
package org.openrewrite.gradle.migrate.base;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;

class UseBaseExtensionBlockTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseBaseExtensionBlock());
    }

    @DocumentExample
    @Test
    void archivesBaseNameAssignment() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
              }

              archivesBaseName = 'gradle'
              """,
            """
              plugins {
                  id 'java'
              }

              base {
                  archivesName = 'gradle'
              }
              """
          )
        );
    }

    @Test
    void projectQualifiedAndExpressionValue() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
              }

              project.archivesBaseName = "app-${rootProject.name}"
              """,
            """
              plugins {
                  id 'java'
              }

              base {
                  archivesName = "app-${rootProject.name}"
              }
              """
          )
        );
    }

    @Test
    void methodCallForm() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
              }

              archivesBaseName 'gradle'
              """,
            """
              plugins {
                  id 'java'
              }

              base {
                  archivesName = 'gradle'
              }
              """
          )
        );
    }

    @Test
    void directoryNames() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
              }

              libsDirName = 'custom-libs'
              distsDirName = 'custom-dist'
              """,
            """
              plugins {
                  id 'java'
              }

              base {
                  libsDirectory = layout.buildDirectory.dir('custom-libs')
              }
              base {
                  distsDirectory = layout.buildDirectory.dir('custom-dist')
              }
              """
          )
        );
    }

    @Test
    void withinExistingBaseBlock() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
              }

              base {
                  archivesBaseName = 'gradle'
              }
              """,
            """
              plugins {
                  id 'java'
              }

              base {
                  archivesName = 'gradle'
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
                  apply plugin: 'java'
                  archivesBaseName = "app-$name"
              }
              """,
            """
              subprojects {
                  apply plugin: 'java'
                  base {
                      archivesName = "app-$name"
                  }
              }
              """
          )
        );
    }

    @Test
    void conditional() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
              }

              if (hasProperty('release')) {
                  archivesBaseName = 'gradle'
              }
              """,
            """
              plugins {
                  id 'java'
              }

              if (hasProperty('release')) {
                  base {
                      archivesName = 'gradle'
                  }
              }
              """
          )
        );
    }

    @Test
    void kotlinSetPropertyWithPluginsBlock() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  java
              }

              setProperty("archivesBaseName", "gradle")
              """,
            """
              plugins {
                  java
              }

              base {
                  archivesName.set("gradle")
              }
              """
          )
        );
    }

    @Test
    void kotlinBaseAccessorProperty() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  java
              }

              base.archivesBaseName = "gradle"
              """,
            """
              plugins {
                  java
              }

              base {
                  archivesName.set("gradle")
              }
              """
          )
        );
    }

    @Test
    void kotlinWithinBaseBlock() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  java
              }

              base {
                  archivesBaseName = "gradle"
              }
              """,
            """
              plugins {
                  java
              }

              base {
                  archivesName.set("gradle")
              }
              """
          )
        );
    }

    @Test
    void kotlinSubprojectsHaveNoAccessor() {
        rewriteRun(
          buildGradleKts(
            """
              subprojects {
                  apply(plugin = "java")
                  setProperty("archivesBaseName", "gradle")
              }
              """,
            """
              subprojects {
                  apply(plugin = "java")
                  configure<BasePluginExtension> {
                      archivesName.set("gradle")
                  }
              }
              """
          )
        );
    }

    @Nested
    class NoChange {
        @Test
        void alreadyMigrated() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'java'
                  }

                  base {
                      archivesName = 'gradle'
                  }
                  """
              )
            );
        }

        @Test
        void scriptVariableOfTheSameName() {
            rewriteRun(
              buildGradle(
                """
                  def archivesBaseName = 'a'
                  archivesBaseName = 'b'
                  println archivesBaseName
                  """
              )
            );
        }

        @Test
        void extraPropertyOfTheSameName() {
            rewriteRun(
              buildGradle(
                """
                  ext.archivesBaseName = 'a'
                  archivesBaseName = 'b'
                  """
              )
            );
        }

        @Test
        void extBlockOfTheSameName() {
            rewriteRun(
              buildGradle(
                """
                  ext {
                      archivesBaseName = 'a'
                  }
                  """
              )
            );
        }

        @Test
        void insideTaskConfiguration() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'java'
                  }

                  jar {
                      archivesBaseName = 'gradle'
                  }
                  """
              )
            );
        }

        @Test
        void readIsLeftAlone() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'java'
                  }

                  println archivesBaseName
                  """
              )
            );
        }

        @Test
        void anotherReceiver() {
            rewriteRun(
              buildGradle(
                """
                  def helper = new Expando()
                  helper.archivesBaseName = 'gradle'
                  """
              )
            );
        }
    }
}
