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
package org.openrewrite.gradle.migrate.artifacts;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.settingsGradle;

class MigrateHttpRepositoriesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateHttpRepositories());
    }

    @DocumentExample
    @Test
    void useHttpsForPublicHostAndAllowInsecureProtocolOtherwise() {
        rewriteRun(
          buildGradle(
            """
              buildscript {
                  repositories {
                      maven { url "http://maven.aliyun.com/nexus/content/groups/public/" }
                  }
              }

              repositories {
                  maven { url = 'http://repo1.maven.org/maven2' }
                  maven {
                      url "http://nexus.example.com/repository/maven-public/"
                      credentials {
                          username = nexusUser
                          password = nexusPassword
                      }
                  }
              }
              """,
            """
              buildscript {
                  repositories {
                      maven { url "https://maven.aliyun.com/nexus/content/groups/public/" }
                  }
              }

              repositories {
                  maven { url = 'https://repo1.maven.org/maven2' }
                  maven {
                      url "http://nexus.example.com/repository/maven-public/"
                      allowInsecureProtocol = true
                      credentials {
                          username = nexusUser
                          password = nexusPassword
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void allowInsecureProtocolInClosureOnOneLine() {
        rewriteRun(
          buildGradle(
            """
              repositories {
                  maven { url 'http://nexus.example.com/repository/maven-public/' }
              }
              """,
            """
              repositories {
                  maven {
                      url 'http://nexus.example.com/repository/maven-public/'
                      allowInsecureProtocol = true
                  }
              }
              """
          )
        );
    }

    @Test
    void useHttpsInsideUri() {
        rewriteRun(
          buildGradle(
            """
              repositories {
                  maven { url uri('http://repo.spring.io/milestone') }
                  maven { url = uri("http://plugins.gradle.org/m2/") }
              }
              """,
            """
              repositories {
                  maven { url uri('https://repo.spring.io/milestone') }
                  maven { url = uri("https://plugins.gradle.org/m2/") }
              }
              """
          )
        );
    }

    @Test
    void useHttpsForInterpolatedPathOnPublicHost() {
        rewriteRun(
          buildGradle(
            """
              def group = 'public'
              repositories {
                  maven { url "http://maven.aliyun.com/repository/${group}" }
              }
              """,
            """
              def group = 'public'
              repositories {
                  maven { url "https://maven.aliyun.com/repository/${group}" }
              }
              """
          )
        );
    }

    @Test
    void allowInsecureProtocolForInterpolatedHost() {
        rewriteRun(
          buildGradle(
            """
              def nexusHost = 'nexus.example.com'
              repositories {
                  maven {
                      url "http://${nexusHost}/repository/maven-public/"
                  }
              }
              """,
            """
              def nexusHost = 'nexus.example.com'
              repositories {
                  maven {
                      url "http://${nexusHost}/repository/maven-public/"
                      allowInsecureProtocol = true
                  }
              }
              """
          )
        );
    }

    @Test
    void allowInsecureProtocolForPort() {
        rewriteRun(
          buildGradle(
            """
              repositories {
                  maven {
                      url 'http://localhost:8081/repository/maven-snapshots/'
                  }
              }
              """,
            """
              repositories {
                  maven {
                      url 'http://localhost:8081/repository/maven-snapshots/'
                      allowInsecureProtocol = true
                  }
              }
              """
          )
        );
    }

    @Test
    void allowInsecureProtocolForPublishingRepository() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'maven-publish'
              }

              publishing {
                  repositories {
                      maven {
                          url = 'http://nexus.example.com/repository/maven-releases/'
                      }
                  }
              }
              """,
            """
              plugins {
                  id 'maven-publish'
              }

              publishing {
                  repositories {
                      maven {
                          url = 'http://nexus.example.com/repository/maven-releases/'
                          allowInsecureProtocol = true
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void useHttpsInSettings() {
        rewriteRun(
          settingsGradle(
            """
              pluginManagement {
                  repositories {
                      maven { url 'http://maven.aliyun.com/repository/gradle-plugin' }
                  }
              }
              """,
            """
              pluginManagement {
                  repositories {
                      maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeWhenInsecureProtocolIsAllowed() {
        rewriteRun(
          buildGradle(
            """
              repositories {
                  maven {
                      url 'http://nexus.example.com/repository/maven-public/'
                      allowInsecureProtocol true
                  }
                  maven {
                      allowInsecureProtocol = true
                      url 'http://maven.aliyun.com/repository/public'
                  }
              }
              """
          )
        );
    }

    @Test
    void allowInsecureProtocolForJcenterWithUrl() {
        rewriteRun(
          buildGradle(
            """
              repositories {
                  jcenter { url "http://jcenter.example.com/" }
              }
              """,
            """
              repositories {
                  jcenter {
                      url "http://jcenter.example.com/"
                      allowInsecureProtocol = true
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeHttpsRepository() {
        rewriteRun(
          buildGradle(
            """
              repositories {
                  maven { url 'https://nexus.example.com/repository/maven-public/' }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeUrlOutsideRepositories() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'maven-publish'
              }

              publishing {
                  publications {
                      maven(MavenPublication) {
                          pom {
                              url = 'http://www.example.com'
                          }
                      }
                  }
              }
              """
          )
        );
    }
}
