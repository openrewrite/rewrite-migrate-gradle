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
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;

class MigrateMavenPluginToMavenPublishTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateMavenPluginToMavenPublish())
          .allSources(source -> source.markers(GradleProject.builder().name("example").path(":").build()));
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
                          url = project.version.endsWith('-SNAPSHOT') ? snapshotsUrl : releasesUrl
                          credentials {
                              username = project.version.endsWith('-SNAPSHOT') ? snapshotsUser : releasesUser
                              password = project.version.endsWith('-SNAPSHOT') ? snapshotsPassword : releasesPassword
                          }
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void repositoryChosenByOtherCondition() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'java'
              apply plugin: 'maven'

              uploadArchives {
                  repositories {
                      mavenDeployer {
                          if (project.hasProperty('internal')) {
                              repository(url: internalUrl)
                          } else {
                              snapshotRepository(url: publicUrl)
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
                          url = project.hasProperty('internal') ? internalUrl : publicUrl
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
    void doNotChangeWarProject() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'war'
              apply plugin: 'maven'

              uploadArchives {
                  repositories {
                      mavenDeployer {
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

    @Test
    void removeMavenAndKeepPublicationPom() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'java'
              apply plugin: 'maven'
              apply plugin: 'maven-publish'

              publishing {
                  publications {
                      mavenJava(MavenPublication) {
                          from components.java
                          pom {
                              name = 'example'
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
                          pom {
                              name = 'example'
                          }
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeWithoutJavaPlugin() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'base'
              apply plugin: 'maven'

              task dist(type: Zip) {
                  from 'src/dist'
              }

              artifacts {
                  archives dist
              }

              uploadArchives {
                  repositories {
                      mavenDeployer {
                          repository(url: "https://repo.example.org/releases")
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotRemovePluginWhenPomFactoryIsUsed() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'java'
              apply plugin: 'maven'

              task writePom {
                  doLast {
                      pom {
                          project {
                              name 'example'
                          }
                      }.writeTo("$buildDir/pom.xml")
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotRemovePluginWhenInstallTaskIsNamed() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'java'
              apply plugin: 'maven'

              defaultTasks 'clean', 'install'
              """
          ),
          buildGradle(
            """
              apply plugin: 'java'
              apply plugin: 'maven'

              tasks.named('install') {
                  enabled = false
              }
              """,
            spec -> spec.path("library/build.gradle")
          )
        );
    }

    @Test
    void doNotConvertWhenAnotherBuildScriptUsesThePlugin() {
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
              """
          ),
          buildGradle(
            """
              uploadArchives {
                  repositories {
                      mavenDeployer {
                          pom.project {
                              name 'library'
                          }
                      }
                  }
              }
              """,
            spec -> spec.path("library/build.gradle")
          )
        );
    }

    @Test
    void doNotChooseSnapshotRepositoryByVersionInSharedConfiguration() {
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
                              snapshotRepository(url: "https://repo.example.org/snapshots")
                          }
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void migrateDespiteUnrelatedInstallString() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'java'
              apply plugin: 'maven'

              task dist(type: Zip) {
                  from('src/dist') {
                      exclude 'install'
                  }
              }

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

              task dist(type: Zip) {
                  from('src/dist') {
                      exclude 'install'
                  }
              }

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
    void removeMavenAndKeepPomOfPublicationsByType() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'java'
              apply plugin: 'maven'
              apply plugin: 'maven-publish'

              publishing.publications.withType(MavenPublication) {
                  pom {
                      name = 'example'
                  }
              }
              """,
            """
              apply plugin: 'java'
              apply plugin: 'maven-publish'

              publishing.publications.withType(MavenPublication) {
                  pom {
                      name = 'example'
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChooseSnapshotRepositoryByVersionSetLater() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'java'
              apply plugin: 'maven'

              uploadArchives {
                  repositories {
                      mavenDeployer {
                          repository(url: "https://repo.example.org/releases")
                          snapshotRepository(url: "https://repo.example.org/snapshots")
                      }
                  }
              }

              version = '1.0-SNAPSHOT'
              """
          )
        );
    }
    @Test
    void removeUnusedPluginKeepsLicenseHeader() {
        rewriteRun(
          buildGradle(
            """
              /*
               * Licensed under the Apache License, Version 2.0
               */

              apply plugin: 'maven'
              apply plugin: 'java'
              """,
            """
              /*
               * Licensed under the Apache License, Version 2.0
               */

              apply plugin: 'java'
              """
          )
        );
    }

    @Test
    void severalArchivesArtifactsInOneCall() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'java'
              apply plugin: 'maven'

              artifacts {
                  archives javadocJar, sourcesJar
              }

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

              artifacts {
                  archives javadocJar, sourcesJar
              }

              publishing {
                  publications {
                      mavenJava(MavenPublication) {
                          from components.java
                          artifact javadocJar
                          artifact sourcesJar
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
    void groovyPluginProvidesJavaComponent() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'org.jetbrains.kotlin.jvm' version '1.4.0'
                  id 'maven'
              }

              uploadArchives {
                  repositories {
                      mavenDeployer {
                          repository(url: uri('repo'))
                      }
                  }
              }
              """,
            """
              plugins {
                  id 'org.jetbrains.kotlin.jvm' version '1.4.0'
                  id 'maven-publish'
              }

              publishing {
                  publications {
                      mavenJava(MavenPublication) {
                          from components.java
                      }
                  }
                  repositories {
                      maven {
                          url = uri('repo')
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void gradlePluginProjectPublishesPluginMaven() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'java-gradle-plugin'
              apply plugin: 'maven'

              uploadArchives {
                  repositories {
                      mavenDeployer {
                          repository(url: uri('../repo'))
                      }
                  }
              }
              """,
            """
              apply plugin: 'java-gradle-plugin'
              apply plugin: 'maven-publish'

              publishing {
                  repositories {
                      maven {
                          url = uri('../repo')
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void gradlePluginProjectConfiguresPluginMavenPom() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'groovy'
              apply plugin: 'java-gradle-plugin'
              apply plugin: 'maven'

              uploadArchives {
                  repositories {
                      mavenDeployer {
                          repository(url: uri('../repo'))
                          pom.project {
                              name 'example-plugin'
                          }
                      }
                  }
              }
              """,
            """
              apply plugin: 'groovy'
              apply plugin: 'java-gradle-plugin'
              apply plugin: 'maven-publish'

              publishing {
                  publications {
                      pluginMaven(MavenPublication) {
                          pom {
                              name = 'example-plugin'
                          }
                      }
                  }
                  repositories {
                      maven {
                          url = uri('../repo')
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void keepCommentsOfDeployer() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'java'
              apply plugin: 'maven'

              uploadArchives {
                  repositories {
                      mavenDeployer {
                          /*
                           * the staging repository of Sonatype
                           */
                          repository(url: "https://repo.example.org/releases")
                          //mavenLocal()
                          // describes the library
                          pom.project {
                              name 'example'
                              // more to come
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
                          //mavenLocal()
                          // describes the library
                          pom {
                              name = 'example'
                              // more to come
                          }
                      }
                  }
                  repositories {
                      /*
                       * the staging repository of Sonatype
                       */
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
    void sameCredentialsForBothRepositories() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'java'
              apply plugin: 'maven'

              uploadArchives {
                  repositories {
                      mavenDeployer {
                          repository(url: "https://repo.example.org/releases") {
                              authentication(userName: project.properties["repoUser"], password: project.properties["repoPassword"])
                          }
                          snapshotRepository(url: "https://repo.example.org/snapshots") {
                              authentication(userName: project.properties["repoUser"], password: project.properties["repoPassword"])
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
                          url = version.toString().endsWith('SNAPSHOT') ? "https://repo.example.org/snapshots" : "https://repo.example.org/releases"
                          credentials {
                              username = project.properties["repoUser"]
                              password = project.properties["repoPassword"]
                          }
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeWhenSignArchivesTaskIsConfigured() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'java'
              apply plugin: 'maven'
              apply plugin: 'signing'

              signing {
                  sign configurations.archives
              }

              signArchives.enabled = gradle.startParameter.taskNames.contains("publish")

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
    void doNotChangeWhenSigningPrecedesPlugin() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'java'
              apply plugin: 'signing'

              signing {
                  sign configurations.archives
              }

              apply plugin: 'maven'

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
    void switchPluginAppliedAfterImport() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id "com.gradle.plugin-publish" version "0.21.0"
              }

              apply plugin: 'java-library'
              apply plugin: 'maven'

              uploadArchives {
                  repositories {
                      mavenDeployer {
                          repository(url: uri('../repo'))
                      }
                  }
              }

              apply plugin: 'eclipse'
              import org.gradle.plugins.ide.eclipse.model.*
              """,
            """
              plugins {
                  id "com.gradle.plugin-publish" version "0.21.0"
              }

              apply plugin: 'java-library'
              apply plugin: 'maven-publish'

              publishing {
                  publications {
                      mavenJava(MavenPublication) {
                          from components.java
                      }
                  }
                  repositories {
                      maven {
                          url = uri('../repo')
                      }
                  }
              }

              apply plugin: 'eclipse'
              import org.gradle.plugins.ide.eclipse.model.*
              """
          )
        );
    }
    @Test
    void publishUnderArchivesBaseName() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'java'
              apply plugin: 'maven'

              archivesBaseName = 'example-api'

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

              archivesBaseName = 'example-api'

              publishing {
                  publications {
                      mavenJava(MavenPublication) {
                          from components.java
                          artifactId = archivesBaseName
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
    void doNotChangeWhenArchivesBaseNameIsSetLater() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'java'
              apply plugin: 'maven'

              uploadArchives {
                  repositories {
                      mavenDeployer {
                          repository(url: "https://repo.example.org/releases")
                      }
                  }
              }

              archivesBaseName = 'example-api'
              """
          )
        );
    }

    @Test
    void doNotChangeWhenJarIsRenamed() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'java'
              apply plugin: 'maven'

              jar {
                  baseName = 'example-api'
              }

              uploadArchives {
                  repositories {
                      mavenDeployer {
                          repository(url: "https://repo.example.org/releases")
                      }
                  }
              }
              """
          )
        );
    }
}
