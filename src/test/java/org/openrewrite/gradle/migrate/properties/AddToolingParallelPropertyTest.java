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
package org.openrewrite.gradle.migrate.properties;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.properties.Assertions.properties;

class AddToolingParallelPropertyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddToolingParallelProperty());
    }

    @DocumentExample
    @Test
    void addsNextToParallel() {
        rewriteRun(
          properties(
            """
              org.gradle.caching=true
              org.gradle.parallel=true
              org.gradle.jvmargs=-Xmx2g
              """,
            """
              org.gradle.caching=true
              org.gradle.parallel=true
              org.gradle.tooling.parallel=true
              org.gradle.jvmargs=-Xmx2g
              """,
            spec -> spec.path("gradle.properties")
          )
        );
    }

    @Test
    void addsWhenParallelIsTheOnlyEntry() {
        rewriteRun(
          properties(
            """
              org.gradle.parallel=true
              """,
            """
              org.gradle.parallel=true
              org.gradle.tooling.parallel=true
              """,
            spec -> spec.path("gradle.properties")
          )
        );
    }

    @Test
    void gradleReadsTheValueIgnoringCase() {
        rewriteRun(
          properties(
            """
              org.gradle.parallel=True
              """,
            """
              org.gradle.parallel=True
              org.gradle.tooling.parallel=true
              """,
            spec -> spec.path("gradle.properties")
          )
        );
    }

    @Test
    void lastValueOfARepeatedKeyDecides() {
        rewriteRun(
          properties(
            """
              org.gradle.parallel=true
              org.gradle.parallel=false
              """,
            spec -> spec.path("gradle.properties")
          )
        );
    }

    @Test
    void doNotChangeWhenParallelIsFalse() {
        rewriteRun(
          properties(
            """
              org.gradle.parallel=false
              """,
            spec -> spec.path("gradle.properties")
          )
        );
    }

    @Test
    void doNotChangeWhenParallelIsAbsent() {
        rewriteRun(
          properties(
            """
              org.gradle.caching=true
              """,
            spec -> spec.path("gradle.properties")
          )
        );
    }

    @Test
    void doNotOverrideAnExplicitTrue() {
        rewriteRun(
          properties(
            """
              org.gradle.parallel=true
              org.gradle.tooling.parallel=true
              """,
            spec -> spec.path("gradle.properties")
          )
        );
    }

    @Test
    void doNotOverrideAnExplicitFalse() {
        rewriteRun(
          properties(
            """
              org.gradle.tooling.parallel=false
              org.gradle.parallel=true
              """,
            spec -> spec.path("gradle.properties")
          )
        );
    }

    @Test
    void doNotCreateAFile() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
              }
              """
          )
        );
    }

    @Test
    void onlyAppliesToGradleProperties() {
        rewriteRun(
          properties(
            """
              org.gradle.parallel=true
              """,
            spec -> spec.path("src/main/resources/application.properties")
          )
        );
    }
}
