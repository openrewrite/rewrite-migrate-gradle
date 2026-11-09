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

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;
import static org.openrewrite.gradle.Assertions.settingsGradle;

class UseUsingForDependencySubstitutionTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseUsingForDependencySubstitution());
    }

    @DocumentExample
    @Test
    void groovy() {
        rewriteRun(
          buildGradle(
            """
              configurations.all {
                  resolutionStrategy.dependencySubstitution {
                      substitute(module('a:b')).with(project(':sub'))
                  }
              }
              """,
            """
              configurations.all {
                  resolutionStrategy.dependencySubstitution {
                      substitute(module('a:b')).using(project(':sub'))
                  }
              }
              """
          )
        );
    }

    @Test
    void groovyCommandChain() {
        rewriteRun(
          buildGradle(
            """
              configurations.all {
                  resolutionStrategy.dependencySubstitution {
                      substitute module('a:b') with project(':sub')
                  }
              }
              """,
            """
              configurations.all {
                  resolutionStrategy.dependencySubstitution {
                      substitute module('a:b') using project(':sub')
                  }
              }
              """
          )
        );
    }

    @Test
    void throughBecause() {
        rewriteRun(
          buildGradle(
            """
              configurations.all {
                  resolutionStrategy.dependencySubstitution {
                      substitute(module('a:b')).because('why').with(project(':sub'))
                  }
              }
              """,
            """
              configurations.all {
                  resolutionStrategy.dependencySubstitution {
                      substitute(module('a:b')).because('why').using(project(':sub'))
                  }
              }
              """
          )
        );
    }

    @Test
    void settingsScript() {
        rewriteRun(
          settingsGradle(
            """
              includeBuild('other') {
                  dependencySubstitution {
                      substitute(module('a:b')).with(project(':c'))
                  }
              }
              """,
            """
              includeBuild('other') {
                  dependencySubstitution {
                      substitute(module('a:b')).using(project(':c'))
                  }
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
              configurations.all {
                  resolutionStrategy.dependencySubstitution {
                      substitute(module("a:b")).with(project(":sub"))
                  }
              }
              """,
            """
              configurations.all {
                  resolutionStrategy.dependencySubstitution {
                      substitute(module("a:b")).using(project(":sub"))
                  }
              }
              """
          )
        );
    }

    @Test
    void unchangedKotlinStandardLibraryWith() {
        rewriteRun(
          buildGradleKts(
            """
              with(project) {
                  println(name)
              }
              val a = "b".let { with(it) { length } }
              """
          )
        );
    }

    @Test
    void unchangedGroovyObjectWith() {
        rewriteRun(
          buildGradle(
            """
              project.with {
                  println name
              }
              configurations.all {
                  resolutionStrategy.dependencySubstitution {
                      substitute(module('a:b')).with {
                          println it
                      }
                  }
              }
              foo.with(bar)
              """
          )
        );
    }

    @Test
    void unchangedAlreadyUsing() {
        rewriteRun(
          buildGradle(
            """
              configurations.all {
                  resolutionStrategy.dependencySubstitution {
                      substitute(module('a:b')).using(project(':sub')).because('why')
                  }
              }
              """
          )
        );
    }
}
