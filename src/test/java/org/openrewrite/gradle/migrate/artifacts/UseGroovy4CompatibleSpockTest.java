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

class UseGroovy4CompatibleSpockTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseGroovy4CompatibleSpock());
    }

    @DocumentExample
    @Test
    void switchToTheGroovy4Variant() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'groovy'
                  id 'java-gradle-plugin'
              }

              dependencies {
                  testImplementation('org.spockframework:spock-core:2.0-groovy-3.0') {
                      exclude group: 'org.codehaus.groovy'
                  }
              }
              """,
            """
              plugins {
                  id 'groovy'
                  id 'java-gradle-plugin'
              }

              dependencies {
                  testImplementation('org.spockframework:spock-core:2.5-groovy-4.0') {
                      exclude group: 'org.codehaus.groovy'
                  }
              }
              """
          )
        );
    }

    @Test
    void keepTheSpockVersionThatHasAGroovy4Variant() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java-gradle-plugin'
              }

              dependencies {
                  testImplementation 'org.spockframework:spock-core:2.3-groovy-3.0'
                  testImplementation "org.spockframework:spock-junit4:2.3-groovy-3.0"
              }
              """,
            """
              plugins {
                  id 'java-gradle-plugin'
              }

              dependencies {
                  testImplementation 'org.spockframework:spock-core:2.3-groovy-4.0'
                  testImplementation "org.spockframework:spock-junit4:2.3-groovy-4.0"
              }
              """
          )
        );
    }

    @Test
    void switchAMilestoneToItsOwnGroovy4Variant() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java-gradle-plugin'
              }

              dependencies {
                  testImplementation 'org.spockframework:spock-core:2.4-M6-groovy-3.0'
              }
              """,
            """
              plugins {
                  id 'java-gradle-plugin'
              }

              dependencies {
                  testImplementation 'org.spockframework:spock-core:2.4-M6-groovy-4.0'
              }
              """
          )
        );
    }

    @Nested
    class NoChange implements RewriteTest {

        @Override
        public void defaults(RecipeSpec spec) {
            spec.recipe(new UseGroovy4CompatibleSpock());
        }

        @Test
        void alreadyOnGroovy4() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'java-gradle-plugin'
                  }

                  dependencies {
                      testImplementation 'org.spockframework:spock-core:2.3-groovy-4.0'
                  }
                  """
              )
            );
        }

        @Test
        void spock1HasNoGroovy4Variant() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'java-gradle-plugin'
                  }

                  dependencies {
                      testImplementation 'org.spockframework:spock-core:1.3-groovy-2.5'
                  }
                  """
              )
            );
        }

        @Test
        void theProjectDeclaresItsOwnGroovy() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'groovy'
                      id 'java-gradle-plugin'
                  }

                  dependencies {
                      implementation 'org.codehaus.groovy:groovy:3.0.9'
                      testImplementation 'org.spockframework:spock-core:2.0-groovy-3.0'
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
                      testImplementation 'org.spockframework:spock-core:2.0-groovy-3.0'
                  }
                  """
              )
            );
        }

        @Test
        void theVersionIsNotWrittenInTheNotation() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'java-gradle-plugin'
                  }

                  ext {
                      spockVersion = '2.0-groovy-3.0'
                  }

                  dependencies {
                      testImplementation "org.spockframework:spock-core:$spockVersion"
                  }
                  """
              )
            );
        }
        @Test
        void mapNotationIsLeftToDependencyUseStringNotation() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'java-gradle-plugin'
                  }

                  dependencies {
                      testImplementation group: 'org.spockframework', name: 'spock-core', version: '2.0-groovy-3.0'
                  }
                  """
              )
            );
        }
    }
}
