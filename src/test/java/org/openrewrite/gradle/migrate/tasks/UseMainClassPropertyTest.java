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
package org.openrewrite.gradle.migrate.tasks;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;

class UseMainClassPropertyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseMainClassProperty());
    }

    @DocumentExample
    @Test
    void assignmentInTypedTask() {
        rewriteRun(
          buildGradle(
            """
              task runApp(type: JavaExec) {
                  main = 'com.example.App'
              }
              """,
            """
              task runApp(type: JavaExec) {
                  mainClass = 'com.example.App'
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
              task runApp(type: JavaExec) {
                  main 'com.example.App'
                  args 'a'
              }
              tasks.register('other', JavaExec) {
                  args 'a'
                  main('com.example.Other')
              }
              """,
            """
              task runApp(type: JavaExec) {
                  mainClass = 'com.example.App'
                  args 'a'
              }
              tasks.register('other', JavaExec) {
                  args 'a'
                  mainClass = 'com.example.Other'
              }
              """
          )
        );
    }

    @Test
    void withType() {
        rewriteRun(
          buildGradle(
            """
              tasks.withType(JavaExec) {
                  main = 'com.example.App'
              }
              """,
            """
              tasks.withType(JavaExec) {
                  mainClass = 'com.example.App'
              }
              """
          )
        );
    }

    @Test
    void applicationRunTask() {
        rewriteRun(
          buildGradle(
            """
              run {
                  main = 'com.example.App'
              }
              tasks.run {
                  main 'com.example.App'
              }
              tasks.named('run') {
                  main = 'com.example.App'
              }
              """,
            """
              run {
                  mainClass = 'com.example.App'
              }
              tasks.run {
                  mainClass = 'com.example.App'
              }
              tasks.named('run') {
                  mainClass = 'com.example.App'
              }
              """
          )
        );
    }

    @Test
    void kotlinRegister() {
        rewriteRun(
          buildGradleKts(
            """
              tasks.register<JavaExec>("runApp") {
                  main = "com.example.App"
              }
              """,
            """
              tasks.register<JavaExec>("runApp") {
                  mainClass = "com.example.App"
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
                  task runApp(type: JavaExec) {
                      mainClass = 'com.example.App'
                  }
                  """
              )
            );
        }

        @Test
        void notAJavaExec() {
            rewriteRun(
              buildGradle(
                """
                  task other(type: Exec) {
                      ext.main = 'a'
                      main = 'b'
                  }
                  sourceSets {
                      main {
                          java.srcDirs = ['src']
                      }
                  }
                  """
              )
            );
        }

        @Test
        void sourceSetsMainInsideJavaExec() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'java'
                  }
                  task runApp(type: JavaExec) {
                      classpath = sourceSets.main.runtimeClasspath
                  }
                  """
              )
            );
        }

        @Test
        void kotlinScopeFunctionNamedRun() {
            rewriteRun(
              buildGradleKts(
                """
                  var main = "a"
                  run {
                      main = "b"
                  }
                  """
              )
            );
        }
    }
}
