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
package org.openrewrite.gradle.migrate.kotlin;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradleKts;
import static org.openrewrite.gradle.Assertions.settingsGradleKts;

class UseProjectInsteadOfBuildScriptLabelTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseProjectInsteadOfBuildScriptLabel());
    }

    @DocumentExample
    @Test
    void methodAndProperty() {
        rewriteRun(
          buildGradleKts(
            """
              tasks.register("show") {
                  println(this@Build_gradle.file("x"))
                  this@Build_gradle.logger.lifecycle("hello")
              }
              """,
            """
              tasks.register("show") {
                  println(project.file("x"))
                  project.logger.lifecycle("hello")
              }
              """
          )
        );
    }

    @Test
    void insideDependencies() {
        rewriteRun(
          buildGradleKts(
            """
              dependencies {
                  println(this@Build_gradle.uri("x"))
              }
              """,
            """
              dependencies {
                  println(project.uri("x"))
              }
              """
          )
        );
    }

    @Test
    void doNotChangeTopLevelSymbolsOfTheScript() {
        rewriteRun(
          buildGradleKts(
            """
              val x = "top"
              fun file(name: String) = name
              tasks.register("show") {
                  val x = "nested"
                  println(this@Build_gradle.x)
                  println(this@Build_gradle.file("y"))
              }
              """
          )
        );
    }

    @Test
    void doNotChangeInOtherProjectBlocks() {
        rewriteRun(
          buildGradleKts(
            """
              subprojects {
                  println(this@Build_gradle.file("x"))
              }
              allprojects {
                  println(this@Build_gradle.uri("x"))
              }
              project(":a") {
                  println(this@Build_gradle.files("x"))
              }
              """
          )
        );
    }

    @Test
    void doNotChangeBareLabelOrOtherLabels() {
        rewriteRun(
          buildGradleKts(
            """
              val self = this@Build_gradle
              class A {
                  val z = this@A
              }
              """
          )
        );
    }

    @Test
    void doNotChangeSettingsScriptLabel() {
        rewriteRun(
          settingsGradleKts(
            """
              pluginManagement {
                  println(this@Settings_gradle.file("x"))
              }
              """
          )
        );
    }
}
