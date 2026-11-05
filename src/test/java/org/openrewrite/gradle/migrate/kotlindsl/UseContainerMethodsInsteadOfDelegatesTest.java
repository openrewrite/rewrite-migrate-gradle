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
package org.openrewrite.gradle.migrate.kotlindsl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.Issue;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;

@Issue("https://github.com/openrewrite/rewrite-migrate-gradle/issues/5")
class UseContainerMethodsInsteadOfDelegatesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseContainerMethodsInsteadOfDelegates());
    }

    @DocumentExample
    @Test
    void tasksRegistering() {
        rewriteRun(
          buildGradleKts(
            """
              val warmCache by tasks.registering {
                  doLast { println("warm") }
              }
              val pythonSetupVenv by tasks.registering(Exec::class) {
                  commandLine("python3", "-m", "venv", ".venv")
              }
              """,
            """
              val warmCache = tasks.register("warmCache") {
                  doLast { println("warm") }
              }
              val pythonSetupVenv = tasks.register<Exec>("pythonSetupVenv") {
                  commandLine("python3", "-m", "venv", ".venv")
              }
              """
          )
        );
    }

    @Test
    void registeringWithoutConfiguration() {
        rewriteRun(
          buildGradleKts(
            """
              val aggregate by tasks.registering
              val copyDocs by tasks.registering(Copy::class)
              val empty by tasks.registering()
              """,
            """
              val aggregate = tasks.register("aggregate")
              val copyDocs = tasks.register<Copy>("copyDocs")
              val empty = tasks.register("empty")
              """
          )
        );
    }

    @Test
    void registeringInsideTasksBlock() {
        rewriteRun(
          buildGradleKts(
            """
              tasks {
                  val generateRecipes by registering(JavaExec::class) {
                      mainClass.set("org.example.Generate")
                  }
                  val docs by registering
              }
              """,
            """
              tasks {
                  val generateRecipes = register<JavaExec>("generateRecipes") {
                      mainClass.set("org.example.Generate")
                  }
                  val docs = register("docs")
              }
              """
          )
        );
    }

    @Test
    void configurationsCreating() {
        rewriteRun(
          buildGradleKts(
            """
              val antlrGeneration by configurations.creating
              val tools by configurations.creating {
                  isTransitive = false
              }
              """,
            """
              val antlrGeneration = configurations.create("antlrGeneration")
              val tools = configurations.create("tools") {
                  isTransitive = false
              }
              """
          )
        );
    }

    @Test
    void tasksExisting() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  java
              }
              val jar by tasks.existing
              val test by tasks.existing(Test::class) {
                  useJUnitPlatform()
              }
              """,
            """
              plugins {
                  java
              }
              val jar = tasks.named("jar")
              val test = tasks.named<Test>("test") {
                  useJUnitPlatform()
              }
              """
          )
        );
    }

    @Test
    void suitesGetting() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  java
              }
              testing {
                  suites {
                      val test by getting(JvmTestSuite::class) {
                          useJUnitJupiter()
                      }
                  }
              }
              val main by sourceSets.getting
              """,
            """
              plugins {
                  java
              }
              testing {
                  suites {
                      val test = getByName<JvmTestSuite>("test") {
                          useJUnitJupiter()
                      }
                  }
              }
              val main = sourceSets.getByName("main")
              """
          )
        );
    }

    @Test
    void keepsExplicitPropertyType() {
        rewriteRun(
          buildGradleKts(
            """
              val integrationTest: TaskProvider<Test> by tasks.registering(Test::class)
              """,
            """
              val integrationTest: TaskProvider<Test> = tasks.register<Test>("integrationTest")
              """
          )
        );
    }

    @Test
    void backtickedPropertyName() {
        rewriteRun(
          buildGradleKts(
            """
              val `publish-docs` by tasks.registering
              """,
            """
              val `publish-docs` = tasks.register("publish-docs")
              """
          )
        );
    }

    @Test
    void kotlinMultiplatformSourceSets() {
        rewriteRun(
          buildGradleKts(
            """
              kotlin {
                  sourceSets {
                      val commonMain by getting {
                          dependencies {
                              implementation("org.example:lib:1.0")
                          }
                      }
                      val jvmTest by getting
                  }
              }
              """,
            """
              kotlin {
                  sourceSets {
                      val commonMain = getByName("commonMain") {
                          dependencies {
                              implementation("org.example:lib:1.0")
                          }
                      }
                      val jvmTest = getByName("jvmTest")
                  }
              }
              """
          )
        );
    }

    @Test
    void keepsReceiverOnItsOwnLine() {
        rewriteRun(
          buildGradleKts(
            """
              val pythonSetupVenv by project.tasks
                  .registering(Exec::class) {
                      commandLine("python3", "-m", "venv", ".venv")
                  }
              """,
            """
              val pythonSetupVenv = project.tasks
                  .register<Exec>("pythonSetupVenv") {
                      commandLine("python3", "-m", "venv", ".venv")
                  }
              """
          )
        );
    }

    @Test
    void precompiledScriptPlugin() {
        rewriteRun(
          buildGradleKts(
            """
              val aggregate by tasks.registering
              """,
            """
              val aggregate = tasks.register("aggregate")
              """,
            spec -> spec.path("buildSrc/src/main/kotlin/my-convention.gradle.kts")
          )
        );
    }

    @Nested
    class NoChange {

        @Test
        void alreadyUsingRegister() {
            rewriteRun(
              buildGradleKts(
                """
                  val warmCache = tasks.register("warmCache") {
                      doLast { println("warm") }
                  }
                  """
              )
            );
        }

        @Test
        void otherDelegates() {
            rewriteRun(
              buildGradleKts(
                """
                  val signingKey: String? by project
                  val greeting by lazy { "hello" }
                  """
              )
            );
        }

        @Test
        void javaClassArgument() {
            rewriteRun(
              buildGradleKts(
                """
                  val listFiles by tasks.creating(Exec::class.java) {
                      commandLine("ls")
                  }
                  """
              )
            );
        }

        @Test
        void groovyDsl() {
            rewriteRun(
              buildGradle(
                """
                  def registering = tasks.register("registering")
                  """
              )
            );
        }
    }
}
