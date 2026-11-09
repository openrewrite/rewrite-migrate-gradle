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

class UseFilePermissionsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseFilePermissions());
    }

    @DocumentExample
    @Test
    void copyTaskKeepsOctal() {
        rewriteRun(
          buildGradle(
            """
              tasks.register('dist', Copy) {
                  from 'in'
                  into 'out'
                  fileMode = 0644
                  dirMode = 0755
              }
              """,
            """
              tasks.register('dist', Copy) {
                  from 'in'
                  into 'out'
                  filePermissions {
                      unix(0644)
                  }
                  dirPermissions {
                      unix(0755)
                  }
              }
              """
          )
        );
    }

    @Test
    void methodCallForms() {
        rewriteRun(
          buildGradle(
            """
              tasks.withType(Zip).configureEach {
                  fileMode 0640
                  dirMode(0750)
              }
              tasks.named('jar') {
                  setFileMode(0600)
              }
              """,
            """
              tasks.withType(Zip).configureEach {
                  filePermissions {
                      unix(0640)
                  }
                  dirPermissions {
                      unix(0750)
                  }
              }
              tasks.named('jar') {
                  filePermissions {
                      unix(0600)
                  }
              }
              """
          )
        );
    }

    @Test
    void oldStyleTaskDeclaration() {
        rewriteRun(
          buildGradle(
            """
              task bundle(type: Tar) {
                  fileMode = 0444
              }
              """,
            """
              task bundle(type: Tar) {
                  filePermissions {
                      unix(0444)
                  }
              }
              """
          )
        );
    }

    @Test
    void nestedCopySpecs() {
        rewriteRun(
          buildGradle(
            """
              copy {
                  from('bin') {
                      fileMode = 0755
                  }
                  into 'out'
              }
              def spec = copySpec {
                  fileMode = 0644
              }
              application.applicationDistribution.from('scripts') {
                  fileMode = 0750
              }
              distributions {
                  main {
                      contents {
                          dirMode = 0700
                      }
                  }
              }
              jar {
                  metaInf {
                      from('m') {
                          fileMode = 0600
                      }
                  }
              }
              """,
            """
              copy {
                  from('bin') {
                      filePermissions {
                          unix(0755)
                      }
                  }
                  into 'out'
              }
              def spec = copySpec {
                  filePermissions {
                      unix(0644)
                  }
              }
              application.applicationDistribution.from('scripts') {
                  filePermissions {
                      unix(0750)
                  }
              }
              distributions {
                  main {
                      contents {
                          dirPermissions {
                              unix(0700)
                          }
                      }
                  }
              }
              jar {
                  metaInf {
                      from('m') {
                          filePermissions {
                              unix(0600)
                          }
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void kotlinLiterals() {
        rewriteRun(
          buildGradleKts(
            """
              tasks.register<Copy>("dist") {
                  fileMode = 420
                  dirMode = 0b111101101
              }
              tasks.withType<Zip>().configureEach {
                  from("in") {
                      fileMode = 0x1ED
                  }
              }
              """,
            """
              tasks.register<Copy>("dist") {
                  filePermissions {
                      unix(420)
                  }
                  dirPermissions {
                      unix(0b111101101)
                  }
              }
              tasks.withType<Zip>().configureEach {
                  from("in") {
                      filePermissions {
                          unix(0x1ED)
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void kotlinClassArgumentAndTasksBlock() {
        rewriteRun(
          buildGradleKts(
            """
              tasks.register("dist", Sync::class) {
                  fileMode = 420
              }
              tasks {
                  jar {
                      dirMode = 493
                  }
              }
              """,
            """
              tasks.register("dist", Sync::class) {
                  filePermissions {
                      unix(420)
                  }
              }
              tasks {
                  jar {
                      dirPermissions {
                          unix(493)
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeComputedValues() {
        rewriteRun(
          buildGradle(
            """
              def mode = 0644
              tasks.register('dist', Copy) {
                  fileMode = mode
                  dirMode = Integer.parseInt('755', 8)
              }
              """
          ),
          buildGradleKts(
            """
              tasks.register<Copy>("dist") {
                  fileMode = "644".toInt(8)
              }
              """
          )
        );
    }

    @Test
    void doNotChangeReadsAndDetailsMode() {
        rewriteRun(
          buildGradle(
            """
              tasks.register('dist', Copy) {
                  eachFile { details ->
                      details.mode = 0644
                      println details.mode
                  }
                  filesMatching('*.sh') {
                      mode = 0755
                  }
                  println fileMode
              }
              """
          )
        );
    }

    @Test
    void doNotChangeInsideEachFile() {
        rewriteRun(
          buildGradle(
            """
              tasks.register('dist', Copy) {
                  eachFile {
                      fileMode = 0644
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeUnknownOwners() {
        rewriteRun(
          buildGradle(
            """
              fileMode = 0644
              custom {
                  fileMode = 0644
              }
              tasks.register('x', MyTask) {
                  dirMode = 0755
              }
              from('in') {
                  fileMode = 0644
              }
              ext.fileMode = 0644
              """
          )
        );
    }

    @Test
    void doNotChangeNebulaPackaging() {
        rewriteRun(
          buildGradle(
            """
              ospackage {
                  fileMode = 0644
                  from('in') {
                      fileMode = 0755
                  }
              }
              tasks.register('rpm', Rpm) {
                  from('in') {
                      dirMode = 0755
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeOwnVariable() {
        rewriteRun(
          buildGradle(
            """
              def fileMode = 0644
              tasks.register('dist', Copy) {
                  fileMode = 0640
              }
              """
          )
        );
    }

    @Test
    void doNotChangeAlreadyMigrated() {
        rewriteRun(
          buildGradle(
            """
              tasks.register('dist', Copy) {
                  filePermissions {
                      unix(0644)
                  }
              }
              """
          )
        );
    }
}
