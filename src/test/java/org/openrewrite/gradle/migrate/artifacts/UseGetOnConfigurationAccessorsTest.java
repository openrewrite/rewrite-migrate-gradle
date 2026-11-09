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

class UseGetOnConfigurationAccessorsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseGetOnConfigurationAccessors());
    }

    @DocumentExample
    @Test
    void propertyAccess() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  java
              }

              tasks.register("show") {
                  doLast {
                      println(configurations.compileClasspath.files)
                      println(configurations.runtimeClasspath.asPath)
                  }
              }
              """,
            """
              plugins {
                  java
              }

              tasks.register("show") {
                  doLast {
                      println(configurations.compileClasspath.get().files)
                      println(configurations.runtimeClasspath.get().asPath)
                  }
              }
              """
          )
        );
    }

    @Test
    void methodCall() {
        rewriteRun(
          buildGradleKts(
            """
              val classpath = configurations.compileClasspath.resolve()
              configurations.implementation.exclude(group = "a")
              """,
            """
              val classpath = configurations.compileClasspath.get().resolve()
              configurations.implementation.get().exclude(group = "a")
              """
          )
        );
    }

    @Test
    void assignment() {
        rewriteRun(
          buildGradleKts(
            """
              configurations.implementation.isTransitive = false
              """,
            """
              configurations.implementation.get().isTransitive = false
              """
          )
        );
    }

    @Test
    void chained() {
        rewriteRun(
          buildGradleKts(
            """
              val names = configurations.runtimeClasspath.files.map { it.name }
              val also = project.configurations.compileClasspath.singleFile
              """,
            """
              val names = configurations.runtimeClasspath.get().files.map { it.name }
              val also = project.configurations.compileClasspath.get().singleFile
              """
          )
        );
    }

    @Test
    void unchangedDelegateAndLookups() {
        rewriteRun(
          buildGradleKts(
            """
              val foo by configurations.creating
              val a = foo.files
              val b = configurations.getByName("foo").files
              val c = configurations["foo"].files
              """
          )
        );
    }

    @Test
    void unchangedAlreadyGet() {
        rewriteRun(
          buildGradleKts(
            """
              val a = configurations.compileClasspath.get().files
              """
          )
        );
    }

    @Test
    void unchangedProviderMembers() {
        rewriteRun(
          buildGradleKts(
            """
              val a = configurations.compileClasspath.map { it.name }
              val b = configurations.compileClasspath.name
              configurations.implementation {
                  isTransitive = false
              }
              configurations.implementation.configure {
                  isTransitive = false
              }
              """
          )
        );
    }

    @Test
    void unchangedOtherReceivers() {
        rewriteRun(
          buildGradleKts(
            """
              val a = rootProject.configurations.compileClasspath.files
              val b = sourceSets.main.files
              val c = foo.compileClasspath.files
              """
          )
        );
    }

    @Test
    void unchangedLocalVariableNamedLikeContainer() {
        rewriteRun(
          buildGradleKts(
            """
              val configurations = mapOf("a" to "b")
              val a = configurations.keys.isEmpty
              """
          )
        );
    }

    @Test
    void unchangedGroovy() {
        rewriteRun(
          buildGradle(
            """
              def a = configurations.compileClasspath.files
              """
          )
        );
    }
}
