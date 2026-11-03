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

import static org.openrewrite.properties.Assertions.properties;

class MigrateConfigurationCachePropertiesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResource("/META-INF/rewrite/configuration-cache.yml",
          "org.openrewrite.gradle.migrate.properties.MigrateConfigurationCacheProperties");
    }

    @DocumentExample
    @Test
    void renameConfigurationCacheProperties() {
        rewriteRun(
          properties(
            """
              org.gradle.caching=true
              org.gradle.unsafe.configuration-cache=true
              org.gradle.unsafe.configuration-cache-problems=warn
              org.gradle.unsafe.configuration-cache.max-problems=5
              """,
            """
              org.gradle.caching=true
              org.gradle.configuration-cache=true
              org.gradle.configuration-cache.problems=warn
              org.gradle.configuration-cache.max-problems=5
              """,
            spec -> spec.path("gradle.properties")
          )
        );
    }

    @Test
    void problemsKeyGainsADotRatherThanKeepingItsHyphen() {
        rewriteRun(
          properties(
            """
              org.gradle.unsafe.configuration-cache-problems=warn
              """,
            """
              org.gradle.configuration-cache.problems=warn
              """,
            spec -> spec.path("gradle.properties")
          )
        );
    }

    @Test
    void renamesInASubprojectDirectory() {
        rewriteRun(
          properties(
            """
              org.gradle.unsafe.configuration-cache=true
              """,
            """
              org.gradle.configuration-cache=true
              """,
            spec -> spec.path("included/gradle.properties")
          )
        );
    }

    @Test
    void leavesFinalizedPropertiesAlone() {
        rewriteRun(
          properties(
            """
              org.gradle.configuration-cache=true
              org.gradle.configuration-cache.problems=warn
              org.gradle.configuration-cache.max-problems=5
              """,
            spec -> spec.path("gradle.properties")
          )
        );
    }

    @Test
    void leavesAnUnlistedUnsafeKeyAlone() {
        rewriteRun(
          properties(
            """
              org.gradle.unsafe.configuration-cache-debug=true
              """,
            spec -> spec.path("gradle.properties")
          )
        );
    }

    @Test
    void aFileWithBothNamesEndsUpWithADuplicateKey() {
        rewriteRun(
          properties(
            """
              org.gradle.unsafe.configuration-cache=false
              org.gradle.configuration-cache=true
              """,
            """
              org.gradle.configuration-cache=false
              org.gradle.configuration-cache=true
              """,
            spec -> spec.path("gradle.properties")
          )
        );
    }

    @Test
    void onlyAppliesToGradleProperties() {
        rewriteRun(
          properties(
            """
              org.gradle.unsafe.configuration-cache=true
              """,
            spec -> spec.path("src/main/resources/application.properties")
          )
        );
    }
}
