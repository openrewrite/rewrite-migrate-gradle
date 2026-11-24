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
package org.openrewrite.gradle.migrate.plugins;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openrewrite.DocumentExample;
import org.openrewrite.Tree;
import org.openrewrite.gradle.marker.GradlePluginDescriptor;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.gradle.migrate.GradleProjectMarkers;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.util.List;

import static java.util.regex.Pattern.quote;
import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;
import static org.openrewrite.gradle.Assertions.settingsGradle;

class UpgradePluginsForGradle9Test implements RewriteTest {
    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("org.openrewrite.gradle.migrate.plugins.UpgradePluginsForGradle9")
          .allSources(source -> source.markers(GradleProjectMarkers.project()));
    }

    @DocumentExample
    @Test
    void upgradePluginsBrokenOnGradle9() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
                  id 'com.diffplug.spotless' version '6.17.0'
                  id 'com.github.ben-manes.versions' version '0.51.0'
                  id 'org.sonarqube' version '4.4.1.3373'
              }
              """,
            spec -> spec.after(actual -> assertThat(actual)
              .containsPattern("id 'com.diffplug.spotless' version '6\\.25\\.\\d+'")
              .containsPattern("id 'com.github.ben-manes.versions' version '0\\.52\\.\\d+'")
              .containsPattern("id 'org.sonarqube' version '6\\.2\\.[\\d.]+'")
              .actual())
          )
        );
    }

    @ParameterizedTest
    @CsvSource({
      "org.gradle.toolchains.foojay-resolver-convention, 0.5.0, 1.0.",
      "nebula.netflixoss, 11.6.0, 12.0.",
      "com.netflix.nebula.netflixoss, 11.6.0, 12.0.",
      "com.netflix.nebula.optional-base, 8.0.0, 9.0.",
      "com.diffplug.spotless, 6.17.0, 6.25.",
      "com.github.spotbugs, 4.8.0, 5.2.",
      "org.sonarqube, 4.4.1.3373, 6.2.",
      "com.github.ben-manes.versions, 0.51.0, 0.52.",
      "com.adarshr.test-logger, 3.2.0, 4.0.",
      "info.solidsoft.pitest, 1.15.0, 1.19.",
      "org.checkerframework, 0.6.30, 0.6.",
      "com.github.jk1.dependency-license-report, 2.1, 2.",
      "io.qameta.allure, 2.12.0, 3.0.",
      "me.champeau.jmh, 0.7.0, 0.7.",
      "io.freefair.aspectj.post-compile-weaving, 8.13.1, 8.14.",
      "com.google.protobuf, 0.9.2, 0.9.4",
      "com.gorylenko.gradle-git-properties, 2.4.1, 2.5.",
      "com.google.cloud.tools.jib, 3.4.5, 3.5.",
      "org.asciidoctor.jvm.convert, 3.3.2, 4.0.",
      "io.github.gradle-nexus.publish-plugin, 1.3.0, 2.0.",
      "com.vanniktech.maven.publish, 0.29.0, 0.32.",
      "org.unbroken-dome.test-sets, 4.0.0, 4.1.",
      "com.github.davidmc24.gradle.plugin.avro, 1.2.0, 1.2.",
      "org.graalvm.buildtools.native, 0.9.20, 0.9.",
      "nu.studer.jooq, 8.2, 8.2.",
      "com.bmuschko.docker-java-application, 8.1.0, 9.",
    })
    void upgradePluginBrokenOnGradle9(String pluginId, String oldVersion, String newVersionPrefix) {
        rewriteRun(
          buildGradle(
            "plugins { id '%s' version '%s' }".formatted(pluginId, oldVersion),
            spec -> spec.after(actual -> assertThat(actual)
              .containsPattern("id '%s' version '%s[\\d.]*'".formatted(quote(pluginId), quote(newVersionPrefix)))
              .doesNotContain("version '%s'".formatted(oldVersion))
              .actual())
          )
        );
    }

    @Test
    void upgradeSettingsPlugin() {
        rewriteRun(
          settingsGradle(
            "plugins { id 'org.gradle.toolchains.foojay-resolver-convention' version '0.5.0' }",
            spec -> spec.after(actual -> assertThat(actual)
              .matches("plugins \\{ id 'org.gradle.toolchains.foojay-resolver-convention' version '1\\.0\\.\\d+' }")
              .actual())
          )
        );
    }

    @Test
    void migrateLegacyNebulaOptionalBase() {
        rewriteRun(
          buildGradle(
            "plugins { id 'nebula.optional-base' version '7.0.0' }",
            spec -> spec.markers(GradleProject.builder().id(Tree.randomId())
                .plugins(List.of(new GradlePluginDescriptor("nebula.plugin.extraconfigurations.OptionalBasePlugin", "nebula.optional-base")))
                .buildscript(GradleProjectMarkers.buildscript()).build())
              .after(actual -> assertThat(actual)
                .matches("plugins \\{ id 'com.netflix.nebula.optional-base' version '9\\.0\\.\\d+' }")
                .actual())
          )
        );
    }

    @Test
    void upgradeKotlinDsl() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  id("com.google.protobuf") version "0.9.2"
                  id("com.adarshr.test-logger") version "3.2.0"
              }
              """,
            spec -> spec.after(actual -> assertThat(actual)
              .contains("id(\"com.google.protobuf\") version \"0.9.4\"")
              .containsPattern("id\\(\"com.adarshr.test-logger\"\\) version \"4\\.0\\.\\d+\"")
              .actual())
          )
        );
    }

    @Nested
    class NoChange {
        @Test
        void doNotDowngradeNewerPlugins() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'com.diffplug.spotless' version '7.0.0'
                      id 'com.github.spotbugs' version '6.2.4'
                      id 'com.google.protobuf' version '0.9.5'
                      id 'io.freefair.lombok' version '9.0.0'
                      id 'nebula.netflixoss' version '13.0.0'
                  }
                  """
              )
            );
        }

        @Test
        void unchangedPluginsThatWorkOnGradle9() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'com.diffplug.spotless-changelog' version '3.0.0'
                      id 'com.bmuschko.docker-remote-api' version '9.4.0'
                      id 'com.palantir.git-version' version '3.0.0'
                  }
                  """
              )
            );
        }
    }
}
