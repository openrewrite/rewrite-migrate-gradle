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

class UseExtendsFromForConfigurationDependenciesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseExtendsFromForConfigurationDependencies());
    }

    @DocumentExample
    @Test
    void groovy() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
              }

              configurations {
                  foo
              }

              dependencies {
                  foo files('a.jar')
                  implementation configurations.foo
              }
              """,
            """
              plugins {
                  id 'java'
              }

              configurations {
                  foo
              }

              configurations {
                  implementation.extendsFrom(foo)
              }

              dependencies {
                  foo files('a.jar')
              }
              """
          )
        );
    }

    @Test
    void replacesEmptiedDependenciesBlock() {
        rewriteRun(
          buildGradle(
            """
              configurations {
                  foo
              }

              dependencies {
                  implementation(configurations.foo)
              }
              """,
            """
              configurations {
                  foo
              }

              configurations {
                  implementation.extendsFrom(foo)
              }
              """
          )
        );
    }

    @Test
    void severalParents() {
        rewriteRun(
          buildGradle(
            """
              dependencies {
                  implementation configurations.foo
                  implementation 'a:b:1'
                  implementation configurations.bar
                  testImplementation configurations.foo
              }
              """,
            """
              configurations {
                  implementation.extendsFrom(foo, bar)
                  testImplementation.extendsFrom(foo)
              }

              dependencies {
                  implementation 'a:b:1'
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
                  apply plugin: 'java'
                  configurations {
                      foo
                  }
                  dependencies {
                      implementation configurations.foo
                      implementation 'a:b:1'
                  }
              }
              """,
            """
              subprojects {
                  apply plugin: 'java'
                  configurations {
                      foo
                  }
                  configurations {
                      implementation.extendsFrom(foo)
                  }

                  dependencies {
                      implementation 'a:b:1'
                  }
              }
              """
          )
        );
    }

    @Test
    void replacesEmptiedDependenciesBlockInsideSubprojects() {
        rewriteRun(
          buildGradle(
            """
              subprojects {
                  apply plugin: 'java'
                  dependencies {
                      implementation configurations.foo
                  }
              }
              """,
            """
              subprojects {
                  apply plugin: 'java'
                  configurations {
                      implementation.extendsFrom(foo)
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

              val foo by configurations.creating

              dependencies {
                  foo(files("a.jar"))
                  implementation(foo)
              }
              """,
            """
              plugins {
                  java
              }

              val foo by configurations.creating

              configurations {
                  implementation {
                      extendsFrom(foo)
                  }
              }

              dependencies {
                  foo(files("a.jar"))
              }
              """
          )
        );
    }

    @Test
    void kotlinCreate() {
        rewriteRun(
          buildGradleKts(
            """
              val foo = configurations.create("foo")

              dependencies {
                  implementation(foo)
              }
              """,
            """
              val foo = configurations.create("foo")

              configurations {
                  implementation {
                      extendsFrom(foo)
                  }
              }
              """
          )
        );
    }

    @Test
    void unchangedOtherNotations() {
        rewriteRun(
          buildGradle(
            """
              dependencies {
                  implementation 'a:b:1'
                  implementation files('a.jar')
                  implementation configurations.foo.files
                  implementation project(':x').configurations.foo
                  implementation rootProject.configurations.foo
                  implementation files(configurations.foo)
                  add('implementation', configurations.foo)
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
              dependencies {
                  implementation(configurations.foo) {
                      transitive = false
                  }
              }
              """
          )
        );
    }

    @Test
    void unchangedBuildscript() {
        rewriteRun(
          buildGradle(
            """
              buildscript {
                  dependencies {
                      classpath configurations.foo
                  }
              }
              """
          )
        );
    }

    @Test
    void unchangedVariableSharingTheName() {
        rewriteRun(
          buildGradle(
            """
              def foo = 'x'
              dependencies {
                  implementation configurations.foo
              }
              """
          )
        );
    }

    @Test
    void unchangedOtherProjectsDependencies() {
        rewriteRun(
          buildGradle(
            """
              project(':x').dependencies {
                  implementation configurations.foo
              }
              """
          )
        );
    }

    @Test
    void unchangedKotlinNotProvenConfiguration() {
        rewriteRun(
          buildGradleKts(
            """
              val foo = files("a.jar")
              val bar by configurations.registering

              dependencies {
                  implementation(foo)
                  implementation(bar)
              }
              """
          )
        );
    }
}
