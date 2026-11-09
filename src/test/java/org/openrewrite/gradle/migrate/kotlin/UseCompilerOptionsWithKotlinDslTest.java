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

class UseCompilerOptionsWithKotlinDslTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseCompilerOptionsWithKotlinDsl());
    }

    @DocumentExample
    @Test
    void guideExample() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  `kotlin-dsl`
              }

              tasks.withType(KotlinCompile::class).configureEach {
                  kotlinOptions {
                      freeCompilerArgs = listOf("-Xcontext-receivers")
                  }
              }
              """,
            """
              plugins {
                  `kotlin-dsl`
              }

              tasks.withType(KotlinCompile::class).configureEach {
                  compilerOptions {
                      freeCompilerArgs.addAll("-Xcontext-receivers")
                  }
              }
              """
          )
        );
    }

    @Test
    void severalArgumentsAndPluginId() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  id("org.gradle.kotlin.kotlin-dsl") version "6.7.6"
              }

              tasks.withType(KotlinCompile::class).configureEach {
                  kotlinOptions {
                      freeCompilerArgs = listOf("-a", "-b")
                  }
              }
              """,
            """
              plugins {
                  id("org.gradle.kotlin.kotlin-dsl") version "6.7.6"
              }

              tasks.withType(KotlinCompile::class).configureEach {
                  compilerOptions {
                      freeCompilerArgs.addAll("-a", "-b")
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeWithoutKotlinDsl() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  java
              }

              tasks.withType(KotlinCompile::class).configureEach {
                  kotlinOptions {
                      freeCompilerArgs = listOf("-Xcontext-receivers")
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeOtherOptionsInTheBlock() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  `kotlin-dsl`
              }

              tasks.withType(KotlinCompile::class).configureEach {
                  kotlinOptions {
                      jvmTarget = "17"
                      freeCompilerArgs = listOf("-a")
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeComputedArguments() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  `kotlin-dsl`
              }

              val arg = "-a"
              tasks.withType(KotlinCompile::class).configureEach {
                  kotlinOptions {
                      freeCompilerArgs = freeCompilerArgs + "-a"
                  }
              }
              tasks.withType(KotlinCompile::class).configureEach {
                  kotlinOptions {
                      freeCompilerArgs = listOf(arg)
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeAlreadyMigrated() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  `kotlin-dsl`
              }

              tasks.withType(KotlinCompile::class).configureEach {
                  compilerOptions {
                      freeCompilerArgs.addAll("-a")
                  }
              }
              """
          )
        );
    }
}
