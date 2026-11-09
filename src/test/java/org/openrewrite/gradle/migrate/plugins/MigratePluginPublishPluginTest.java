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
package org.openrewrite.gradle.migrate.plugins;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;
import static org.openrewrite.properties.Assertions.properties;

class MigratePluginPublishPluginTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigratePluginPublishPlugin());
    }

    @DocumentExample
    @Test
    void moveWebsiteVcsUrlAndTags() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java-gradle-plugin'
                  id 'com.gradle.plugin-publish' version '0.12.0'
              }

              pluginBundle {
                  website = 'https://github.com/acme/widget'
                  vcsUrl = 'https://github.com/acme/widget.git'
                  tags = ['acme', 'widget']
              }

              gradlePlugin {
                  plugins {
                      widget {
                          id = 'com.acme.widget'
                          implementationClass = 'com.acme.WidgetPlugin'
                      }
                  }
              }
              """,
            """
              plugins {
                  id 'java-gradle-plugin'
                  id 'com.gradle.plugin-publish' version '1.3.1'
              }

              gradlePlugin {
                  website = 'https://github.com/acme/widget'
                  vcsUrl = 'https://github.com/acme/widget.git'
                  plugins {
                      widget {
                          id = 'com.acme.widget'
                          implementationClass = 'com.acme.WidgetPlugin'
                          tags = ['acme', 'widget']
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void moveDescriptionOntoEveryDeclaration() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'com.gradle.plugin-publish' version '0.18.0'
              }

              pluginBundle {
                  website = 'https://example.com'
                  vcsUrl = 'https://example.com.git'
                  description = 'Does a thing'
                  tags = ['thing']
              }

              gradlePlugin {
                  plugins {
                      first {
                          id = 'com.acme.first'
                          implementationClass = 'com.acme.First'
                      }
                      second {
                          id = 'com.acme.second'
                          implementationClass = 'com.acme.Second'
                      }
                  }
              }
              """,
            """
              plugins {
                  id 'com.gradle.plugin-publish' version '1.3.1'
              }

              gradlePlugin {
                  website = 'https://example.com'
                  vcsUrl = 'https://example.com.git'
                  plugins {
                      first {
                          id = 'com.acme.first'
                          implementationClass = 'com.acme.First'
                          description = 'Does a thing'
                          tags = ['thing']
                      }
                      second {
                          id = 'com.acme.second'
                          implementationClass = 'com.acme.Second'
                          description = 'Does a thing'
                          tags = ['thing']
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void theBundleValuesWinOverTheDeclarationsOwn() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'com.gradle.plugin-publish' version '0.12.0'
              }

              pluginBundle {
                  website = 'https://example.com'
                  vcsUrl = 'https://example.com.git'
                  displayName = 'The thing'
                  description = 'What the portal shows today'
                  tags = ['thing']
              }

              gradlePlugin {
                  plugins {
                      first {
                          id = 'com.acme.first'
                          implementationClass = 'com.acme.First'
                          displayName = 'first'
                          description = 'What the portal has never shown'
                          tags = ['stale']
                      }
                  }
              }
              """,
            """
              plugins {
                  id 'com.gradle.plugin-publish' version '1.3.1'
              }

              gradlePlugin {
                  website = 'https://example.com'
                  vcsUrl = 'https://example.com.git'
                  plugins {
                      first {
                          id = 'com.acme.first'
                          implementationClass = 'com.acme.First'
                          displayName = 'The thing'
                          description = 'What the portal shows today'
                          tags = ['thing']
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void aPerPluginValueWinsOverTheBundleWideOneAndTheDeclarationsOwn() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'com.gradle.plugin-publish' version '0.12.0'
              }

              pluginBundle {
                  website = 'https://example.com'
                  vcsUrl = 'https://example.com.git'
                  description = 'Does a thing'
                  tags = ['thing']
                  plugins {
                      first {
                          displayName = 'First'
                          description = 'Does the first thing'
                      }
                  }
              }

              gradlePlugin {
                  plugins {
                      first {
                          id = 'com.acme.first'
                          implementationClass = 'com.acme.First'
                          description = 'Something else again'
                      }
                  }
              }
              """,
            """
              plugins {
                  id 'com.gradle.plugin-publish' version '1.3.1'
              }

              gradlePlugin {
                  website = 'https://example.com'
                  vcsUrl = 'https://example.com.git'
                  plugins {
                      first {
                          id = 'com.acme.first'
                          implementationClass = 'com.acme.First'
                          description = 'Does the first thing'
                          tags = ['thing']
                          displayName = 'First'
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void keepWhatTheDeclarationHoldsAndTheBundleDoesNotName() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'com.gradle.plugin-publish' version '0.12.0'
              }

              pluginBundle {
                  website = 'https://example.com'
                  vcsUrl = 'https://example.com.git'
                  tags = ['thing']
              }

              gradlePlugin {
                  plugins {
                      first {
                          id = 'com.acme.first'
                          implementationClass = 'com.acme.First'
                          displayName = 'First'
                          description = 'Does a thing'
                      }
                  }
              }
              """,
            """
              plugins {
                  id 'com.gradle.plugin-publish' version '1.3.1'
              }

              gradlePlugin {
                  website = 'https://example.com'
                  vcsUrl = 'https://example.com.git'
                  plugins {
                      first {
                          id = 'com.acme.first'
                          implementationClass = 'com.acme.First'
                          displayName = 'First'
                          description = 'Does a thing'
                          tags = ['thing']
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void mergeNestedPluginDeclarations() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'com.gradle.plugin-publish' version '0.16.0'
              }

              pluginBundle {
                  website = 'https://example.com'
                  vcsUrl = 'https://example.com'
                  description = 'Shared description'
                  tags = ['shared']

                  plugins {
                      widget {
                          id = 'com.acme.widget'
                          displayName = 'Widget Plugin'
                      }
                  }
              }

              gradlePlugin {
                  plugins {
                      widget {
                          id = 'com.acme.widget'
                          implementationClass = 'com.acme.WidgetPlugin'
                      }
                  }
              }
              """,
            """
              plugins {
                  id 'com.gradle.plugin-publish' version '1.3.1'
              }

              gradlePlugin {
                  website = 'https://example.com'
                  vcsUrl = 'https://example.com'
                  plugins {
                      widget {
                          id = 'com.acme.widget'
                          implementationClass = 'com.acme.WidgetPlugin'
                          description = 'Shared description'
                          tags = ['shared']
                          displayName = 'Widget Plugin'
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void keepExpressionValues() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'com.gradle.plugin-publish' version '0.10.1'
              }

              pluginBundle {
                  website = project.githubUrl
                  vcsUrl = project.githubUrl
                  description = project.description
                  tags = ['acme']
                  version = project.version
              }

              gradlePlugin {
                  plugins {
                      widget {
                          id = 'com.acme.widget'
                          implementationClass = 'com.acme.WidgetPlugin'
                      }
                  }
              }
              """,
            """
              plugins {
                  id 'com.gradle.plugin-publish' version '1.3.1'
              }

              gradlePlugin {
                  website = project.githubUrl
                  vcsUrl = project.githubUrl
                  plugins {
                      widget {
                          id = 'com.acme.widget'
                          implementationClass = 'com.acme.WidgetPlugin'
                          description = project.description
                          tags = ['acme']
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void upgradeTheVersionWhenThereIsNoPluginBundle() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'com.gradle.plugin-publish' version '0.21.0'
              }
              """,
            """
              plugins {
                  id 'com.gradle.plugin-publish' version '1.3.1'
              }
              """
          )
        );
    }

    @Test
    void declareThePluginsOnlyPluginBundleNames() {
        rewriteRun(
          spec -> spec.afterRecipe(run -> assertThat(run.getChangeset().getAllResults())
            .anySatisfy(result -> {
                assertThat(result.getBefore()).isNotNull();
                assertThat(result.getBefore().getSourcePath()).hasToString(
                  "src/main/resources/META-INF/gradle-plugins/com.acme.widget.properties");
                assertThat(result.getAfter()).as("the descriptor Gradle now generates").isNull();
            })),
          properties(
            "implementation-class=com.acme.WidgetPlugin\n",
            null,
            spec -> spec.path("src/main/resources/META-INF/gradle-plugins/com.acme.widget.properties")
          ),
          buildGradle(
            """
              plugins {
                  id 'java-gradle-plugin'
                  id 'com.gradle.plugin-publish' version '0.16.0'
              }

              pluginBundle {
                  website = 'https://example.com'
                  vcsUrl = 'https://example.com.git'
                  description = 'Does a thing'
                  tags = ['acme']
                  version = project.version

                  plugins {
                      widget {
                          id = 'com.acme.widget'
                          displayName = 'Widget'
                      }
                  }
              }
              """,
            """
              plugins {
                  id 'java-gradle-plugin'
                  id 'com.gradle.plugin-publish' version '1.3.1'
              }

              gradlePlugin {
                  website = 'https://example.com'
                  vcsUrl = 'https://example.com.git'
                  plugins {
                      widget {
                          id = 'com.acme.widget'
                          implementationClass = 'com.acme.WidgetPlugin'
                          description = 'Does a thing'
                          tags = ['acme']
                          displayName = 'Widget'
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void deleteTheDescriptorTheDeclarationMakesRedundant() {
        rewriteRun(
          spec -> spec.afterRecipe(run -> assertThat(run.getChangeset().getAllResults())
            .anySatisfy(result -> {
                assertThat(result.getBefore()).isNotNull();
                assertThat(result.getBefore().getSourcePath()).hasToString(
                  "src/main/resources/META-INF/gradle-plugins/com.acme.widget.properties");
                assertThat(result.getAfter()).as("the descriptor Gradle now generates").isNull();
            })),
          properties(
            "implementation-class=com.acme.WidgetPlugin\n",
            null,
            spec -> spec.path("src/main/resources/META-INF/gradle-plugins/com.acme.widget.properties")
          ),
          buildGradle(
            """
              plugins {
                  id 'java-gradle-plugin'
                  id 'com.gradle.plugin-publish' version '0.12.0'
              }

              pluginBundle {
                  website = 'https://example.com'
                  vcsUrl = 'https://example.com.git'
                  tags = ['acme']
              }

              gradlePlugin {
                  plugins {
                      widget {
                          id = 'com.acme.widget'
                          implementationClass = 'com.acme.WidgetPlugin'
                      }
                  }
              }
              """,
            """
              plugins {
                  id 'java-gradle-plugin'
                  id 'com.gradle.plugin-publish' version '1.3.1'
              }

              gradlePlugin {
                  website = 'https://example.com'
                  vcsUrl = 'https://example.com.git'
                  plugins {
                      widget {
                          id = 'com.acme.widget'
                          implementationClass = 'com.acme.WidgetPlugin'
                          tags = ['acme']
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void keepADescriptorForADifferentImplementationClass() {
        rewriteRun(
          properties(
            "implementation-class=com.acme.OtherPlugin\n",
            spec -> spec.path("src/main/resources/META-INF/gradle-plugins/com.acme.widget.properties")
          ),
          buildGradle(
            """
              plugins {
                  id 'java-gradle-plugin'
                  id 'com.gradle.plugin-publish' version '0.12.0'
              }

              pluginBundle {
                  website = 'https://example.com'
                  vcsUrl = 'https://example.com.git'
              }

              gradlePlugin {
                  plugins {
                      widget {
                          id = 'com.acme.widget'
                          implementationClass = 'com.acme.WidgetPlugin'
                      }
                  }
              }
              """,
            """
              plugins {
                  id 'java-gradle-plugin'
                  id 'com.gradle.plugin-publish' version '1.3.1'
              }

              gradlePlugin {
                  website = 'https://example.com'
                  vcsUrl = 'https://example.com.git'
                  plugins {
                      widget {
                          id = 'com.acme.widget'
                          implementationClass = 'com.acme.WidgetPlugin'
                      }
                  }
              }
              """
          )
        );
    }

    @Nested
    class NoChange implements RewriteTest {

        @Override
        public void defaults(RecipeSpec spec) {
            spec.recipe(new MigratePluginPublishPlugin());
        }

        @Test
        void doNotChangeWhenAlreadyOnOneDotX() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'com.gradle.plugin-publish' version '1.2.1'
                  }

                  gradlePlugin {
                      website = 'https://example.com'
                      plugins {
                          widget {
                              id = 'com.acme.widget'
                          }
                      }
                  }
                  """
              )
            );
        }

        @Test
        void doNotChangeWhenPluginBundleHoldsMavenCoordinates() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'com.gradle.plugin-publish' version '0.18.0'
                  }

                  pluginBundle {
                      website = 'https://example.com'
                      vcsUrl = 'https://example.com'
                      tags = ['acme']

                      mavenCoordinates {
                          groupId = 'com.acme'
                          artifactId = 'widget'
                      }
                  }

                  gradlePlugin {
                      plugins {
                          widget {
                              id = 'com.acme.widget'
                          }
                      }
                  }
                  """
              )
            );
        }

        @Test
        void doNotChangeWhenPluginBundleVersionIsNotTheProjectVersion() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'com.gradle.plugin-publish' version '0.18.0'
                  }

                  pluginBundle {
                      website = 'https://example.com'
                      vcsUrl = 'https://example.com'
                      tags = ['acme']
                      version = '1.2.3'
                  }

                  gradlePlugin {
                      plugins {
                          widget {
                              id = 'com.acme.widget'
                          }
                      }
                  }
                  """
              )
            );
        }

        @Test
        void doNotChangeWhenThereIsNoGradlePluginBlock() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'com.gradle.plugin-publish' version '0.18.0'
                  }

                  pluginBundle {
                      website = 'https://example.com'
                      vcsUrl = 'https://example.com'
                      tags = ['acme']
                  }
                  """
              )
            );
        }

        @Test
        void doNotChangeWhenPluginBundleNamesAnUndeclaredPlugin() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'com.gradle.plugin-publish' version '0.18.0'
                  }

                  pluginBundle {
                      website = 'https://example.com'
                      vcsUrl = 'https://example.com'

                      plugins {
                          other {
                              id = 'com.acme.other'
                              displayName = 'Other'
                          }
                      }
                  }

                  gradlePlugin {
                      plugins {
                          widget {
                              id = 'com.acme.widget'
                          }
                      }
                  }
                  """
              )
            );
        }

        @Test
        void doNotChangeAPluginBundleOfAnotherPlugin() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'java'
                  }

                  pluginBundle {
                      website = 'https://example.com'
                  }
                  """
              )
            );
        }

        @Test
        void doNotChangeKotlinScripts() {
            rewriteRun(
              buildGradleKts(
                """
                  plugins {
                      id("com.gradle.plugin-publish") version "0.18.0"
                  }

                  pluginBundle {
                      website = "https://example.com"
                      vcsUrl = "https://example.com"
                      tags = listOf("acme")
                  }

                  gradlePlugin {
                      plugins {
                          create("widget") {
                              id = "com.acme.widget"
                              implementationClass = "com.acme.WidgetPlugin"
                          }
                      }
                  }
                  """
              )
            );
        }
        @Test
        void doNotDeclareWithoutADescriptor() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'java-gradle-plugin'
                      id 'com.gradle.plugin-publish' version '0.16.0'
                  }

                  pluginBundle {
                      website = 'https://example.com'
                      vcsUrl = 'https://example.com.git'

                      plugins {
                          widget {
                              id = 'com.acme.widget'
                              displayName = 'Widget'
                          }
                      }
                  }
                  """
              )
            );
        }

        @Test
        void doNotDeclareWhenTheDescriptorBelongsToAnotherModule() {
            rewriteRun(
              properties(
                "implementation-class=com.acme.WidgetPlugin\n",
                spec -> spec.path("widget/src/main/resources/META-INF/gradle-plugins/com.acme.widget.properties")
              ),
              buildGradle(
                """
                  plugins {
                      id 'java-gradle-plugin'
                      id 'com.gradle.plugin-publish' version '0.16.0'
                  }

                  pluginBundle {
                      website = 'https://example.com'
                      vcsUrl = 'https://example.com.git'

                      plugins {
                          widget {
                              id = 'com.acme.widget'
                              displayName = 'Widget'
                          }
                      }
                  }
                  """
              )
            );
        }
    }
}
