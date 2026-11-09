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

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;

class ReplaceBuiltInWrapperTaskTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new ReplaceBuiltInWrapperTask());
    }

    @DocumentExample
    @Test
    void taskDeclaration() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
              }

              task wrapper(type: Wrapper) {
                  gradleVersion = '8.14.4'
              }
              """,
            """
              plugins {
                  id 'java'
              }

              wrapper {
                  gradleVersion = '8.14.4'
              }
              """
          )
        );
    }

    @Test
    void containerRegister() {
        rewriteRun(
          buildGradle(
            """
              tasks.register('wrapper', Wrapper) {
                  gradleVersion = '8.14.4'
              }
              """,
            """
              wrapper {
                  gradleVersion = '8.14.4'
              }
              """
          )
        );
    }

    @Test
    void containerCreate() {
        rewriteRun(
          buildGradle(
            """
              tasks.create("wrapper", Wrapper) {
                  gradleVersion = '8.14.4'
              }
              """,
            """
              wrapper {
                  gradleVersion = '8.14.4'
              }
              """
          )
        );
    }

    @Test
    void qualifiedType() {
        rewriteRun(
          buildGradle(
            """
              task wrapper(type: org.gradle.api.tasks.wrapper.Wrapper) {
                  gradleVersion = '8.14.4'
              }
              """,
            """
              wrapper {
                  gradleVersion = '8.14.4'
              }
              """
          )
        );
    }

    @Test
    void declarationWithoutConfigurationIsRemoved() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
              }

              task wrapper(type: Wrapper)

              group = 'org.example'
              """,
            """
              plugins {
                  id 'java'
              }

              group = 'org.example'
              """
          )
        );
    }

    @Test
    void kotlinTask() {
        rewriteRun(
          buildGradleKts(
            """
              task<Wrapper>("wrapper") {
                  gradleVersion = "8.14.4"
              }
              """,
            """
              tasks.wrapper {
                  gradleVersion = "8.14.4"
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
              tasks.register<Wrapper>("wrapper") {
                  gradleVersion = "8.14.4"
              }
              """,
            """
              tasks.wrapper {
                  gradleVersion = "8.14.4"
              }
              """
          )
        );
    }

    @Test
    void kotlinCreate() {
        rewriteRun(
          buildGradleKts(
            """
              tasks.create<Wrapper>("wrapper") {
                  gradleVersion = "8.14.4"
              }
              """,
            """
              tasks.wrapper {
                  gradleVersion = "8.14.4"
              }
              """
          )
        );
    }

    @Test
    void composedInGradle5Migration() {
        rewriteRun(
          spec -> spec.recipeFromResources("org.openrewrite.gradle.migrate.MigrateToGradle5"),
          buildGradle(
            """
              task wrapper(type: Wrapper) {
                  gradleVersion = '8.14.4'
              }
              """,
            """
              wrapper {
                  gradleVersion = '8.14.4'
              }
              """
          )
        );
    }

    @Test
    void doNotChangeSubproject() {
        rewriteRun(
          buildGradle(
            """
              task wrapper(type: Wrapper) {
                  gradleVersion = '8.14.4'
              }
              """,
            spec -> spec.path("sub/build.gradle")
          ),
          buildGradleKts(
            """
              task<Wrapper>("wrapper") {
                  gradleVersion = "8.14.4"
              }
              """,
            spec -> spec.path("sub/build.gradle.kts")
          )
        );
    }

    @Test
    void doNotChangeNestedDeclaration() {
        rewriteRun(
          buildGradle(
            """
              subprojects {
                  task wrapper(type: Wrapper) {
                      gradleVersion = '8.14.4'
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeDeclarationWithOtherArguments() {
        rewriteRun(
          buildGradle(
            """
              task wrapper(type: Wrapper, group: 'build') {
                  gradleVersion = '8.14.4'
              }
              """
          )
        );
    }

    @Test
    void doNotChangeOtherTasksOrTypes() {
        rewriteRun(
          buildGradle(
            """
              task updateWrapper(type: Wrapper) {
                  gradleVersion = '8.14.4'
              }
              task wrapper(type: MyWrapper) {
              }
              task init(type: Copy) {
              }
              tasks.register('foo', Wrapper)
              tasks.named('wrapper', Wrapper) {
                  gradleVersion = '8.14.4'
              }
              wrapper {
                  gradleVersion = '8.14.4'
              }
              """
          ),
          buildGradleKts(
            """
              tasks.register<Wrapper>("updateWrapper") {
                  gradleVersion = "8.14.4"
              }
              tasks.wrapper {
                  gradleVersion = "8.14.4"
              }
              tasks.named<Wrapper>("wrapper") {
                  gradleVersion = "8.14.4"
              }
              """
          )
        );
    }

    @Test
    void doNotChangeWhenImportedFromElsewhere() {
        rewriteRun(
          buildGradle(
            """
              import com.acme.Wrapper

              task wrapper(type: Wrapper)
              """
          )
        );
    }
}
