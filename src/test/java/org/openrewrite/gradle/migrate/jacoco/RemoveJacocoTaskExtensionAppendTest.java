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

class RemoveJacocoTaskExtensionAppendTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RemoveJacocoTaskExtensionAppend());
    }

    @DocumentExample
    @Test
    void removeFromTaskExtension() {
        rewriteRun(
          buildGradle(
            """
              test {
                  jacoco {
                      append = false
                      destinationFile = file('a.exec')
                  }
              }
              """,
            """
              test {
                  jacoco {
                      destinationFile = file('a.exec')
                  }
              }
              """
          )
        );
    }

    @Test
    void removeLastStatement() {
        rewriteRun(
          buildGradle(
            """
              test {
                  jacoco {
                      destinationFile = file('a.exec')
                      append = true
                  }
              }
              """,
            """
              test {
                  jacoco {
                      destinationFile = file('a.exec')
                  }
              }
              """
          )
        );
    }

    @Test
    void removeEmptiedExtensionBlockKeepingTask() {
        rewriteRun(
          buildGradle(
            """
              tasks.withType(Test) {
                  maxParallelForks = 2
                  jacoco {
                      append = false
                  }
              }
              """,
            """
              tasks.withType(Test) {
                  maxParallelForks = 2
              }
              """
          )
        );
    }

    @Test
    void removeQualifiedForms() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'jacoco'
              test.jacoco.append = false
              test {
                  jacoco.append = false
                  jacoco.setAppend(false)
                  maxParallelForks = 2
              }
              """,
            """
              apply plugin: 'jacoco'
              test {
                  maxParallelForks = 2
              }
              """
          )
        );
    }

    @Test
    void unchangedOutsideJacoco() {
        rewriteRun(
          buildGradle(
            """
              append = false
              ext.append = false
              test {
                  append = false
              }
              myTask {
                  append = true
                  foo.append = true
              }
              """
          )
        );
    }

    @Test
    void unchangedExtraProperty() {
        rewriteRun(
          buildGradle(
            """
              test {
                  jacoco {
                      ext.append = false
                  }
              }
              """
          )
        );
    }

    @Test
    void unchangedOtherJacocoProperties() {
        rewriteRun(
          buildGradle(
            """
              jacoco {
                  toolVersion = '0.8.12'
              }
              test {
                  jacoco {
                      enabled = false
                  }
              }
              """
          )
        );
    }

    @Test
    void unchangedKotlin() {
        rewriteRun(
          buildGradleKts(
            """
              tasks.test {
                  configure<JacocoTaskExtension> {
                      isAppend = false
                  }
              }
              """
          )
        );
    }
}
