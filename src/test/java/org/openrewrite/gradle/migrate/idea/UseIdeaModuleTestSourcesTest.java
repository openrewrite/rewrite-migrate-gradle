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
package org.openrewrite.gradle.migrate.idea;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;

class UseIdeaModuleTestSourcesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseIdeaModuleTestSources());
    }

    @DocumentExample
    @Test
    void addingGroovy() {
        rewriteRun(
          buildGradle(
            """
              idea {
                  module {
                      testSourceDirs += file('src/it/java')
                      testResourceDirs += file('src/it/resources')
                  }
              }
              """,
            """
              idea {
                  module {
                      testSources.from(file('src/it/java'))
                      testResources.from(file('src/it/resources'))
                  }
              }
              """
          )
        );
    }

    @Test
    void replacingGroovy() {
        rewriteRun(
          buildGradle(
            """
              idea {
                  module {
                      testSourceDirs = [file('s')] as Set
                      testResourceDirs = testResourceDirs + file('r')
                  }
              }
              """,
            """
              idea {
                  module {
                      testSources.setFrom([file('s')] as Set)
                      testResources.from(file('r'))
                  }
              }
              """
          )
        );
    }

    @Test
    void methodCalls() {
        rewriteRun(
          buildGradle(
            """
              idea {
                  module {
                      testSourceDirs.add(file('a'))
                      testResourceDirs.addAll(files('b'))
                  }
              }
              """,
            """
              idea {
                  module {
                      testSources.from(file('a'))
                      testResources.from(files('b'))
                  }
              }
              """
          )
        );
    }

    @Test
    void receivers() {
        rewriteRun(
          buildGradle(
            """
              idea.module.testSourceDirs += file('a')
              idea {
                  module.testResourceDirs += file('b')
              }
              """,
            """
              idea.module.testSources.from(file('a'))
              idea {
                  module.testResources.from(file('b'))
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
              idea {
                  module {
                      testSourceDirs = testSourceDirs + file("a")
                      testResourceDirs = setOf(file("b"))
                      testSourceDirs.add(file("c"))
                  }
              }
              """,
            """
              idea {
                  module {
                      testSources.from(file("a"))
                      testResources.setFrom(setOf(file("b")))
                      testSources.from(file("c"))
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeOutsideIdeaModule() {
        rewriteRun(
          buildGradle(
            """
              testSourceDirs += file('a')
              module {
                  testSourceDirs += file('a')
              }
              custom {
                  module {
                      testSourceDirs += file('a')
                  }
              }
              other.module.testResourceDirs += file('b')
              """
          )
        );
    }

    @Test
    void doNotChangeReadsOrOtherUsesOfOldValue() {
        rewriteRun(
          buildGradle(
            """
              idea {
                  module {
                      sourceDirs -= testSourceDirs
                      testResourceDirs = testResourceDirs.findAll { it.exists() }
                      println testSourceDirs
                  }
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
              idea {
                  module {
                      testSources.from(file('a'))
                      testResources.from(file('b'))
                  }
              }
              """
          )
        );
    }
}
