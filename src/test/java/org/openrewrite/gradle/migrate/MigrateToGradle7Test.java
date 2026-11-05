/*
 * Copyright 2026 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.gradle.migrate;

import org.junit.jupiter.api.Test;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;

class MigrateToGradle7Test implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("org.openrewrite.gradle.migrate.MigrateToGradle7")
          .allSources(source -> source.markers(GradleProject.builder().name("example").path(":").build()));
    }

    @Test
    void allowInsecureProtocolForMigratedDeployerRepository() {
        rewriteRun(
          spec -> spec.expectedCyclesThatMakeChanges(1),
          buildGradle(
            """
              apply plugin: 'java'
              apply plugin: 'maven'

              uploadArchives {
                  repositories {
                      mavenDeployer {
                          repository(url: "http://nexus.example.com/repository/releases")
                      }
                  }
              }
              """,
            """
              apply plugin: 'java'
              apply plugin: 'maven-publish'

              publishing {
                  publications {
                      mavenJava(MavenPublication) {
                          from components.java
                      }
                  }
                  repositories {
                      maven {
                          url = "http://nexus.example.com/repository/releases"
                          allowInsecureProtocol = true
                      }
                  }
              }
              """
          )
        );
    }
}
