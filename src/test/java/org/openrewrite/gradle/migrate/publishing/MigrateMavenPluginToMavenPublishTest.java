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
package org.openrewrite.gradle.migrate.publishing;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;

class MigrateMavenPluginToMavenPublishTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateMavenPluginToMavenPublish());
    }

    @DocumentExample
    @Test
    void migrateUploadArchives() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'java'
              apply plugin: 'maven'
              apply plugin: 'signing'

              signing {
                  required { gradle.taskGraph.hasTask("uploadArchives") }
                  sign configurations.archives
              }

              uploadArchives {
                  repositories {
                      mavenDeployer {
                          beforeDeployment { MavenDeployment deployment -> signing.signPom(deployment) }
                          repository(url: "https://repo.example.org/releases") {
                              authentication(userName: repoUser, password: repoPassword)
                          }
                          pom.project {
                              name 'example'
                              description 'An example library'
                          }
                      }
                  }
              }
              """,
            """
              apply plugin: 'java'
              apply plugin: 'maven-publish'
              apply plugin: 'signing'

              signing {
                  required { gradle.taskGraph.hasTask("publish") }
                  sign publishing.publications
              }

              publishing {
                  publications {
                      mavenJava(MavenPublication) {
                          from components.java
                          pom {
                              name = 'example'
                              description = 'An example library'
                          }
                      }
                  }
                  repositories {
                      maven {
                          url = "https://repo.example.org/releases"
                          credentials {
                              username = repoUser
                              password = repoPassword
                          }
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void snapshotRepositoryAndArchivesArtifacts() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
                  id 'maven'
              }

              task sourcesJar(type: Jar) {
                  archiveClassifier = 'sources'
                  from sourceSets.main.allSource
              }

              artifacts {
                  archives jar
                  archives sourcesJar
              }

              uploadArchives {
                  repositories {
                      mavenDeployer {
                          repository(url: "https://repo.example.org/releases") {
                              authentication(userName: repoUser, password: repoPassword)
                          }
                          snapshotRepository(url: "https://repo.example.org/snapshots") {
                              authentication(userName: repoUser, password: repoPassword)
                          }
                          pom.project {
                              name project.name
                              packaging 'jar'
                              url 'https://example.org'
                              licenses {
                                  license {
                                      name 'The Apache License, Version 2.0'
                                      url 'https://www.apache.org/licenses/LICENSE-2.0.txt'
                                  }
                              }
                              developers {
                                  developer {
                                      id 'jdoe'
                                      // the maintainer
                                      name 'Jane Doe'
                                  }
                              }
                          }
                      }
                  }
              }
              """,
            """
              plugins {
                  id 'java'
                  id 'maven-publish'
              }

              task sourcesJar(type: Jar) {
                  archiveClassifier = 'sources'
                  from sourceSets.main.allSource
              }

              artifacts {
                  archives jar
                  archives sourcesJar
              }

              publishing {
                  publications {
                      mavenJava(MavenPublication) {
                          from components.java
                          artifact sourcesJar
                          pom {
                              name = project.name
                              packaging = 'jar'
                              url = 'https://example.org'
                              licenses {
                                  license {
                                      name = 'The Apache License, Version 2.0'
                                      url = 'https://www.apache.org/licenses/LICENSE-2.0.txt'
                                  }
                              }
                              developers {
                                  developer {
                                      id = 'jdoe'
                                      // the maintainer
                                      name = 'Jane Doe'
                                  }
                              }
                          }
                      }
                  }
                  repositories {
                      maven {
                          url = version.toString().endsWith('SNAPSHOT') ? "https://repo.example.org/snapshots" : "https://repo.example.org/releases"
                          credentials {
                              username = repoUser
                              password = repoPassword
                          }
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void repositoryChosenByVersionWithDifferentCredentials() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'java'
              apply plugin: 'maven'

              uploadArchives {
                  repositories {
                      mavenDeployer {
                          if (project.version.endsWith('-SNAPSHOT')) {
                              snapshotRepository(url: snapshotsUrl) {
                                  authentication(userName: snapshotsUser, password: snapshotsPassword)
                              }
                          } else {
                              repository(url: releasesUrl) {
                                  authentication(userName: releasesUser, password: releasesPassword)
                              }
                          }
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
                          url = version.toString().endsWith('SNAPSHOT') ? snapshotsUrl : releasesUrl
                          credentials {
                              username = version.toString().endsWith('SNAPSHOT') ? snapshotsUser : releasesUser
                              password = version.toString().endsWith('SNAPSHOT') ? snapshotsPassword : releasesPassword
                          }
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void migrateInSubprojectsBlock() {
        rewriteRun(
          buildGradle(
            """
              subprojects {
                apply plugin: 'java'
                apply plugin: 'maven'

                uploadArchives {
                  repositories {
                    mavenDeployer {
                      repository(url: "https://repo.example.org/releases")
                    }
                  }
                }
              }
              """,
            """
              subprojects {
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
                      url = "https://repo.example.org/releases"
                    }
                  }
                }
              }
              """
          )
        );
    }

    @Test
    void removeMavenWhenMavenPublishIsAlsoApplied() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'java'
              apply plugin: 'maven'
              apply plugin: 'maven-publish'

              uploadArchives {
                  repositories {
                      mavenDeployer {
                          repository(url: "https://repo.example.org/releases")
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
                          url = "https://repo.example.org/releases"
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void removeUnusedPlugin() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'java'
              apply plugin: 'maven'

              group = 'org.example'
              """,
            """
              apply plugin: 'java'

              group = 'org.example'
              """
          )
        );
    }

    @Test
    void removeUnusedPluginKeepsBlankLine() {
        rewriteRun(
          buildGradle(
            """
              group = 'org.example'

              apply plugin: 'maven'
              apply plugin: 'java'
              """,
            """
              group = 'org.example'

              apply plugin: 'java'
              """
          )
        );
    }

    @Test
    void removeUnusedPluginFromPluginsBlock() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
                  id 'maven'
              }
              """,
            """
              plugins {
                  id 'java'
              }
              """
          )
        );
    }

    @Test
    void doNotChangeWhenInstallIsConfigured() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'java'
              apply plugin: 'maven'

              install {
                  repositories.mavenInstaller {
                      pom.project {
                          name 'example'
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeUnrecognizedDeployerConfiguration() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'java'
              apply plugin: 'maven'

              uploadArchives {
                  repositories {
                      mavenDeployer {
                          repository(url: "https://repo.example.org/releases")
                          pom.whenConfigured { pom -> pom.dependencies.clear() }
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeWhenPomIsSignedButArchivesAreNot() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'java'
              apply plugin: 'maven'
              apply plugin: 'signing'

              uploadArchives {
                  repositories {
                      mavenDeployer {
                          beforeDeployment { MavenDeployment deployment -> signing.signPom(deployment) }
                          repository(url: "https://repo.example.org/releases")
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotRemovePluginUsedByAnotherBuildScript() {
        rewriteRun(
          buildGradle(
            """
              allprojects {
                  apply plugin: 'java'
                  apply plugin: 'maven'
              }
              """
          ),
          buildGradle(
            """
              uploadArchives {
                  repositories {
                      mavenDeployer {
                          repository(url: "https://repo.example.org/releases")
                      }
                  }
              }
              """,
            spec -> spec.path("library/build.gradle")
          )
        );
    }

    @Test
    void doNotChangeWithoutMavenPlugin() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
                  id 'maven-publish'
              }
              """
          )
        );
    }
}
