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
import static org.openrewrite.gradle.Assertions.settingsGradle;

class UseAssignmentForRepositoryUrlTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseAssignmentForRepositoryUrl());
    }

    @DocumentExample
    @Test
    void mavenAndIvyRepositories() {
        rewriteRun(
          buildGradle(
            """
              repositories {
                  maven {
                      url 'https://example.org/m2'
                  }
                  ivy {
                      name = 'ivy'
                      url 'https://example.org/ivy'
                      patternLayout {
                          artifact '[module]/[revision]/[artifact].[ext]'
                      }
                  }
              }
              """,
            """
              repositories {
                  maven {
                      url = 'https://example.org/m2'
                  }
                  ivy {
                      name = 'ivy'
                      url = 'https://example.org/ivy'
                      patternLayout {
                          artifact '[module]/[revision]/[artifact].[ext]'
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void parenthesizedAndComputedValues() {
        rewriteRun(
          buildGradle(
            """
              repositories {
                  maven { url('https://example.org/a') }
                  maven { url uri('https://example.org/b') }
                  maven { url "https://$host/c" }
              }
              """,
            """
              repositories {
                  maven { url = 'https://example.org/a' }
                  maven { url = uri('https://example.org/b') }
                  maven { url = "https://$host/c" }
              }
              """
          )
        );
    }

    @Test
    void publishingAndBuildscriptRepositories() {
        rewriteRun(
          buildGradle(
            """
              buildscript {
                  repositories {
                      maven { url 'https://example.org/plugins' }
                  }
              }
              publishing {
                  repositories {
                      maven {
                          url 'https://example.org/publish'
                      }
                  }
              }
              """,
            """
              buildscript {
                  repositories {
                      maven { url = 'https://example.org/plugins' }
                  }
              }
              publishing {
                  repositories {
                      maven {
                          url = 'https://example.org/publish'
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void settingsRepositories() {
        rewriteRun(
          settingsGradle(
            """
              pluginManagement {
                  repositories {
                      maven { url 'https://example.org/plugins' }
                  }
              }
              dependencyResolutionManagement {
                  repositories {
                      maven { url 'https://example.org/m2' }
                  }
              }
              """,
            """
              pluginManagement {
                  repositories {
                      maven { url = 'https://example.org/plugins' }
                  }
              }
              dependencyResolutionManagement {
                  repositories {
                      maven { url = 'https://example.org/m2' }
                  }
              }
              """
          )
        );
    }

    @Test
    void repositoriesAsAReceiver() {
        rewriteRun(
          buildGradle(
            """
              repositories.maven { url 'https://example.org/m2' }
              project.repositories.maven { url 'https://example.org/m2' }
              """,
            """
              repositories.maven { url = 'https://example.org/m2' }
              project.repositories.maven { url = 'https://example.org/m2' }
              """
          )
        );
    }

    @Test
    void doNotChangeAnAssignment() {
        rewriteRun(
          buildGradle(
            """
              repositories {
                  maven { url = 'https://example.org/m2' }
              }
              """
          )
        );
    }

    @Test
    void doNotChangePomUrl() {
        rewriteRun(
          buildGradle(
            """
              publishing {
                  publications {
                      mavenJava(MavenPublication) {
                          pom {
                              url 'https://example.org'
                              licenses {
                                  license {
                                      url 'https://example.org/license'
                                  }
                              }
                              scm {
                                  url 'https://example.org/scm'
                              }
                          }
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeLegacyPomProjectClosure() {
        rewriteRun(
          buildGradle(
            """
              pom.project {
                  url 'https://example.org'
              }
              """
          )
        );
    }

    @Test
    void doNotChangeBuilderClosure() {
        rewriteRun(
          buildGradle(
            """
              publishing {
                  publications {
                      mavenJava(MavenPublication) {
                          pom.withXml {
                              asNode().children().last() + {
                                  url 'https://example.org'
                              }
                          }
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeUploadArchives() {
        rewriteRun(
          buildGradle(
            """
              uploadArchives {
                  repositories {
                      maven {
                          url 'https://example.org/m2'
                      }
                      mavenDeployer {
                          pom.project {
                              url 'https://example.org'
                          }
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeBlocksNestedInARepository() {
        rewriteRun(
          buildGradle(
            """
              repositories {
                  maven {
                      credentials {
                          url 'https://example.org/token'
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeMavenOutsideRepositories() {
        rewriteRun(
          buildGradle(
            """
              maven {
                  url 'https://example.org/m2'
              }
              download {
                  url 'https://example.org/file'
              }
              """
          )
        );
    }

    @Test
    void doNotChangeKotlin() {
        rewriteRun(
          buildGradleKts(
            """
              repositories {
                  maven {
                      url = uri("https://example.org/m2")
                  }
              }
              """
          )
        );
    }
}
