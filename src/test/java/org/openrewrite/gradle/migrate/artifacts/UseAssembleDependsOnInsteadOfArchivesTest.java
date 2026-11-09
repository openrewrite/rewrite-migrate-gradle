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

class UseAssembleDependsOnInsteadOfArchivesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseAssembleDependsOnInsteadOfArchives());
    }

    @DocumentExample
    @Test
    void declaredTask() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
              }

              task sourcesJar(type: Jar) {
                  from sourceSets.main.allSource
              }

              artifacts {
                  archives sourcesJar
              }
              """,
            """
              plugins {
                  id 'java'
              }

              task sourcesJar(type: Jar) {
                  from sourceSets.main.allSource
              }

              tasks.named("assemble") {
                  dependsOn(sourcesJar)
              }
              """
          )
        );
    }

    @Test
    void taskProvidersAndJar() {
        rewriteRun(
          buildGradle(
            """
              def a = tasks.register('a', Jar)
              tasks.register('b', Jar)
              artifacts {
                  archives a
                  archives b
                  archives(jar)
                  archives tasks.named('c')
              }
              """,
            """
              def a = tasks.register('a', Jar)
              tasks.register('b', Jar)
              tasks.named("assemble") {
                  dependsOn(a, b, jar, tasks.named('c'))
              }
              """
          )
        );
    }

    @Test
    void addForm() {
        rewriteRun(
          buildGradle(
            """
              task a(type: Jar)
              artifacts {
                  add('archives', a)
              }
              """,
            """
              task a(type: Jar)
              tasks.named("assemble") {
                  dependsOn(a)
              }
              """
          )
        );
    }

    @Test
    void keepsTheRestOfTheBlock() {
        rewriteRun(
          buildGradle(
            """
              task a(type: Jar)
              artifacts {
                  archives a
                  archives file('x.txt')
                  other a
              }
              """,
            """
              task a(type: Jar)
              tasks.named("assemble") {
                  dependsOn(a)
              }

              artifacts {
                  archives file('x.txt')
                  other a
              }
              """
          )
        );
    }

    @Test
    void insideSubprojects() {
        rewriteRun(
          buildGradle(
            """
              subprojects {
                  task a(type: Jar)
                  artifacts {
                      archives a
                  }
              }
              """,
            """
              subprojects {
                  task a(type: Jar)
                  tasks.named("assemble") {
                      dependsOn(a)
                  }
              }
              """
          )
        );
    }

    @Test
    void kotlin() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  java
              }

              val specialJar = tasks.register<Jar>("specialJar") {
                  archiveClassifier.set("special")
              }
              val otherJar by tasks.registering(Jar::class)

              artifacts {
                  archives(specialJar)
                  add("archives", otherJar)
                  "archives"(tasks.jar)
              }
              """,
            """
              plugins {
                  java
              }

              val specialJar = tasks.register<Jar>("specialJar") {
                  archiveClassifier.set("special")
              }
              val otherJar by tasks.registering(Jar::class)

              tasks.named("assemble") {
                  dependsOn(specialJar, otherJar, tasks.jar)
              }
              """
          )
        );
    }

    @Test
    void unchangedFilesAndMaps() {
        rewriteRun(
          buildGradle(
            """
              def f = file('x.txt')
              artifacts {
                  archives f
                  archives file('y.txt')
                  archives file: file('z.txt'), name: 'z'
                  archives(layout.buildDirectory.file('w'))
              }
              """
          )
        );
    }

    @Test
    void unchangedUnprovenName() {
        rewriteRun(
          buildGradle(
            """
              artifacts {
                  archives somethingFromAPlugin
              }
              """
          )
        );
    }

    @Test
    void unchangedWithClosure() {
        rewriteRun(
          buildGradle(
            """
              task a(type: Jar)
              artifacts {
                  archives(a) {
                      classifier = 'x'
                  }
              }
              """
          )
        );
    }

    @Test
    void unchangedOtherConfiguration() {
        rewriteRun(
          buildGradle(
            """
              task a(type: Jar)
              artifacts {
                  other a
                  add('other', a)
              }
              """
          )
        );
    }

    @Test
    void unchangedWithUploadArchives() {
        rewriteRun(
          buildGradle(
            """
              task a(type: Jar)
              artifacts {
                  archives a
              }
              uploadArchives {
                  repositories {
                      mavenDeployer {
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void unchangedWhenTheConfigurationIsRead() {
        rewriteRun(
          buildGradle(
            """
              task a(type: Jar)
              artifacts {
                  archives a
              }
              signing {
                  sign configurations.archives
              }
              """
          )
        );
    }

    @Test
    void unchangedWhenTheConfigurationIsLookedUp() {
        rewriteRun(
          buildGradleKts(
            """
              val a = tasks.register<Jar>("a")
              artifacts {
                  archives(a)
              }
              println(configurations.getByName("archives"))
              """
          )
        );
    }

    @Test
    void unchangedKotlinFile() {
        rewriteRun(
          buildGradleKts(
            """
              val f = file("x.txt")
              artifacts {
                  archives(f)
                  add("archives", file("y.txt"))
              }
              """
          )
        );
    }
}
