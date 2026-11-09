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

class UseProjectPathForDependencyNotationTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseProjectPathForDependencyNotation());
    }

    @DocumentExample
    @Test
    void localVariable() {
        rewriteRun(
          buildGradle(
            """
              def someProject = project(":some-project")
              dependencies {
                  implementation(someProject)
                  testImplementation someProject
              }
              """,
            """
              def someProject = project(":some-project")
              dependencies {
                  implementation(project(":some-project"))
                  testImplementation project(":some-project")
              }
              """
          )
        );
    }

    @Test
    void keepsConfigurationClosureAndAdd() {
        rewriteRun(
          buildGradle(
            """
              def sub = project(':sub')
              dependencies {
                  implementation(sub) {
                      transitive = false
                  }
                  add('api', sub)
              }
              """,
            """
              def sub = project(':sub')
              dependencies {
                  implementation(project(':sub')) {
                      transitive = false
                  }
                  add('api', project(':sub'))
              }
              """
          )
        );
    }

    @Test
    void wrappedNotations() {
        rewriteRun(
          buildGradle(
            """
              def sub = project(':sub')
              dependencies {
                  implementation(platform(sub))
                  testImplementation(testFixtures(sub))
              }
              """,
            """
              def sub = project(':sub')
              dependencies {
                  implementation(platform(project(':sub')))
                  testImplementation(testFixtures(project(':sub')))
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
              val sub = project(":sub")
              dependencies {
                  implementation(sub)
                  "api"(sub)
                  add("runtimeOnly", sub)
              }
              """,
            """
              val sub = project(":sub")
              dependencies {
                  implementation(project(":sub"))
                  "api"(project(":sub"))
                  add("runtimeOnly", project(":sub"))
              }
              """
          )
        );
    }

    @Test
    void unchangedProjectsOfUnknownPath() {
        rewriteRun(
          buildGradle(
            """
              subprojects.each {
                  dependencies {
                      implementation it
                  }
              }
              subprojects.each { sub ->
                  dependencies {
                      implementation sub
                  }
              }
              dependencies {
                  implementation rootProject
                  implementation project.parent
                  implementation project
              }
              """
          )
        );
    }

    @Test
    void unchangedOtherInitializers() {
        rewriteRun(
          buildGradle(
            """
              def a = findProject(':a')
              def path = ':b'
              def b = project(path)
              def c = project(path: ':c')
              def d = project('relative')
              def e = rootProject.project(':e')
              dependencies {
                  implementation a
                  implementation b
                  implementation c
                  implementation d
                  implementation e
              }
              """
          )
        );
    }

    @Test
    void unchangedReassigned() {
        rewriteRun(
          buildGradle(
            """
              def sub = project(':a')
              sub = project(':b')
              dependencies {
                  implementation sub
              }
              """
          )
        );
    }

    @Test
    void unchangedShadowed() {
        rewriteRun(
          buildGradle(
            """
              def sub = project(':a')
              allprojects.each { sub ->
                  dependencies {
                      implementation sub
                  }
              }
              """
          )
        );
    }

    @Test
    void unchangedOutsideDependencies() {
        rewriteRun(
          buildGradle(
            """
              def sub = project(':a')
              println(sub)
              evaluationDependsOn(sub.path)
              dependencies {
                  implementation sub.files('x.jar')
                  components.all(sub)
              }
              """
          )
        );
    }
}
