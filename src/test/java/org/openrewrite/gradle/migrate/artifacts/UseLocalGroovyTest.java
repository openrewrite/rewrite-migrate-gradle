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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;

class UseLocalGroovyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseLocalGroovy());
    }

    @DocumentExample
    @Test
    void useLocalGroovyInsteadOfGroovyAll() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'groovy'
                  id 'java-gradle-plugin'
              }

              dependencies {
                  implementation 'org.codehaus.groovy:groovy-all:2.5.4'
              }
              """,
            """
              plugins {
                  id 'groovy'
                  id 'java-gradle-plugin'
              }

              dependencies {
                  implementation localGroovy()
              }
              """
          )
        );
    }

    @Test
    void useLocalGroovyInsteadOfGroovy3() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java-gradle-plugin'
              }

              dependencies {
                  api "org.codehaus.groovy:groovy:3.0.7"
                  testImplementation 'org.spockframework:spock-core:2.3-groovy-3.0'
              }
              """,
            """
              plugins {
                  id 'java-gradle-plugin'
              }

              dependencies {
                  api localGroovy()
                  testImplementation 'org.spockframework:spock-core:2.3-groovy-3.0'
              }
              """
          )
        );
    }

    @Nested
    class NoChange implements RewriteTest {

        @Override
        public void defaults(RecipeSpec spec) {
            spec.recipe(new UseLocalGroovy());
        }

        @Test
        void groovy4DoesNotConflict() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'java-gradle-plugin'
                  }

                  dependencies {
                      implementation 'org.apache.groovy:groovy:4.0.33'
                  }
                  """
              )
            );
        }

        @Test
        void notAPluginProject() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'groovy'
                  }

                  dependencies {
                      implementation 'org.codehaus.groovy:groovy-all:2.5.4'
                  }
                  """
              )
            );
        }

        @Test
        void aModuleOfTheDistribution() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'java-gradle-plugin'
                  }

                  dependencies {
                      implementation 'org.codehaus.groovy:groovy-json:2.5.4'
                  }
                  """
              )
            );
        }

        @Test
        void aBuildscriptClasspath() {
            rewriteRun(
              buildGradle(
                """
                  buildscript {
                      repositories {
                          mavenCentral()
                      }
                      dependencies {
                          classpath 'org.codehaus.groovy:groovy-all:2.5.4'
                      }
                  }

                  plugins {
                      id 'java-gradle-plugin'
                  }
                  """
              )
            );
        }

        @Test
        void aVersionThatIsNotWrittenInTheNotation() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'java-gradle-plugin'
                  }

                  ext {
                      groovyVersion = '2.5.4'
                  }

                  dependencies {
                      implementation "org.codehaus.groovy:groovy-all:$groovyVersion"
                  }
                  """
              )
            );
        }
    }
}
