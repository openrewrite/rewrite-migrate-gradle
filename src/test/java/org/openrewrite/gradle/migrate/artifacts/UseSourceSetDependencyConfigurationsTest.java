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
import org.openrewrite.gradle.marker.GradleDependencyConfiguration;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.emptyList;
import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;

class UseSourceSetDependencyConfigurationsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseSourceSetDependencyConfigurations());
    }

    @DocumentExample
    @Test
    void renamesConfigurationsOfDeclaredSourceSet() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
              }

              sourceSets {
                  intTest {
                      java.srcDir 'src/intTest/java'
                  }
              }

              dependencies {
                  intTestCompile 'org.junit.jupiter:junit-jupiter-api:5.10.0'
                  intTestCompile files('libs/a.jar')
                  intTestCompile fileTree('libs')
                  intTestCompile gradleTestKit()
                  intTestRuntime 'org.junit.jupiter:junit-jupiter-engine:5.10.0'
              }
              """,
            """
              plugins {
                  id 'java'
              }

              sourceSets {
                  intTest {
                      java.srcDir 'src/intTest/java'
                  }
              }

              dependencies {
                  intTestImplementation 'org.junit.jupiter:junit-jupiter-api:5.10.0'
                  intTestImplementation files('libs/a.jar')
                  intTestImplementation fileTree('libs')
                  intTestImplementation gradleTestKit()
                  intTestRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine:5.10.0'
              }
              """
          )
        );
    }

    @Test
    void bareSourceSetEntry() {
        rewriteRun(
          buildGradle(
            """
              sourceSets {
                  intTest
              }

              dependencies {
                  intTestCompile files('a.jar')
              }
              """,
            """
              sourceSets {
                  intTest
              }

              dependencies {
                  intTestImplementation files('a.jar')
              }
              """
          )
        );
    }

    @Test
    void sourceSetsCreateAndAddForm() {
        rewriteRun(
          buildGradle(
            """
              sourceSets.create('intTest')

              dependencies {
                  add('intTestCompile', 'a:b:1')
              }
              """,
            """
              sourceSets.create('intTest')

              dependencies {
                  add('intTestImplementation', 'a:b:1')
              }
              """
          )
        );
    }

    @Test
    void testSuite() {
        rewriteRun(
          buildGradle(
            """
              testing {
                  suites {
                      intTest(JvmTestSuite)
                  }
              }

              dependencies {
                  intTestCompile files('a.jar')
              }
              """,
            """
              testing {
                  suites {
                      intTest(JvmTestSuite)
                  }
              }

              dependencies {
                  intTestImplementation files('a.jar')
              }
              """
          )
        );
    }

    @Test
    void extendsFromInConfigurationsBlock() {
        rewriteRun(
          buildGradle(
            """
              sourceSets {
                  intTest
              }

              configurations {
                  intTestCompile.extendsFrom testCompile
                  intTestRuntime.extendsFrom testRuntime
              }
              """,
            """
              sourceSets {
                  intTest
              }

              configurations {
                  intTestImplementation.extendsFrom testImplementation
                  intTestRuntimeOnly.extendsFrom testRuntimeOnly
              }
              """
          )
        );
    }

    @Test
    void kotlinStringInvokeAndAdd() {
        rewriteRun(
          buildGradleKts(
            """
              sourceSets {
                  create("intTest")
              }

              dependencies {
                  "intTestCompile"(files("a.jar"))
                  add("intTestRuntime", files("b.jar"))
              }
              """,
            """
              sourceSets {
                  create("intTest")
              }

              dependencies {
                  "intTestImplementation"(files("a.jar"))
                  add("intTestRuntimeOnly", files("b.jar"))
              }
              """
          )
        );
    }

    @Test
    void kotlinDelegatedSourceSet() {
        rewriteRun(
          buildGradleKts(
            """
              val intTest by sourceSets.creating

              dependencies {
                  "intTestCompile"(files("a.jar"))
              }
              """,
            """
              val intTest by sourceSets.creating

              dependencies {
                  "intTestImplementation"(files("a.jar"))
              }
              """
          )
        );
    }

    @Test
    void kotlinTestSuiteAndQualifiedForms() {
        rewriteRun(
          buildGradleKts(
            """
              testing {
                  suites {
                      register<JvmTestSuite>("intTest")
                  }
              }

              dependencies.add("intTestCompile", files("a.jar"))
              """,
            """
              testing {
                  suites {
                      register<JvmTestSuite>("intTest")
                  }
              }

              dependencies.add("intTestImplementation", files("a.jar"))
              """
          )
        );
    }

    @Test
    void qualifiedExtendsFrom() {
        rewriteRun(
          buildGradle(
            """
              sourceSets {
                  intTest {
                  }
              }

              configurations.intTestCompile.extendsFrom(configurations.testCompile)
              """,
            """
              sourceSets {
                  intTest {
                  }
              }

              configurations.intTestImplementation.extendsFrom(configurations.testImplementation)
              """
          )
        );
    }

    @Test
    void unchangedWithoutSourceSet() {
        rewriteRun(
          buildGradle(
            """
              dependencies {
                  fooCompile files('a.jar')
              }
              """
          )
        );
    }

    @Test
    void unchangedCustomConfiguration() {
        rewriteRun(
          buildGradle(
            """
              configurations {
                  fooCompile
              }

              dependencies {
                  fooCompile files('a.jar')
              }
              """
          )
        );
    }

    @Test
    void unchangedWhenConfigurationIsDeclaredBesideSourceSet() {
        rewriteRun(
          buildGradle(
            """
              sourceSets {
                  foo
              }

              configurations {
                  fooCompile
              }

              dependencies {
                  fooCompile files('a.jar')
              }
              """
          )
        );
    }

    @Test
    void unchangedOtherSuffixes() {
        rewriteRun(
          buildGradle(
            """
              sourceSets {
                  intTest
              }

              dependencies {
                  intTestCompileOnly files('a.jar')
                  intTestRuntimeOnly files('b.jar')
                  intTestAnnotationProcessor files('c.jar')
              }
              """
          )
        );
    }

    @Test
    void unchangedMainAndTest() {
        rewriteRun(
          buildGradle(
            """
              sourceSets {
                  main
                  test
              }

              dependencies {
                  mainCompile files('a.jar')
                  testCompile files('b.jar')
              }
              """
          )
        );
    }

    @Test
    void unchangedKotlinCustomConfiguration() {
        rewriteRun(
          buildGradleKts(
            """
              val fooCompile by configurations.creating
              sourceSets {
                  create("foo")
              }

              dependencies {
                  fooCompile(files("a.jar"))
              }
              """
          )
        );
    }

    @Test
    void sourceSetFromTheModel() {
        rewriteRun(
          buildGradle(
            """
              apply plugin: 'my-conventions'

              dependencies {
                  intTestCompile files('a.jar')
                  fooCompile files('b.jar')
              }
              """,
            """
              apply plugin: 'my-conventions'

              dependencies {
                  intTestImplementation files('a.jar')
                  fooCompile files('b.jar')
              }
              """,
            spec -> spec.markers(gradleProject("intTestCompile", "intTestImplementation", "intTestCompileClasspath",
              "fooCompile", "fooImplementation"))
          )
        );
    }

    private static GradleProject gradleProject(String... configurations) {
        Map<String, GradleDependencyConfiguration> nameToConfiguration = new HashMap<>();
        for (String configuration : configurations) {
            nameToConfiguration.put(configuration, GradleDependencyConfiguration.builder()
              .name(configuration)
              .extendsFrom(emptyList())
              .requested(emptyList())
              .directResolved(emptyList())
              .build());
        }
        return GradleProject.builder().name("p").path(":").nameToConfiguration(nameToConfiguration).build();
    }

    @Test
    void unchangedOutsideDependencies() {
        rewriteRun(
          buildGradle(
            """
              sourceSets {
                  intTest
              }

              def intTestCompile(x) { x }
              intTestCompile('a')
              """
          )
        );
    }
}
