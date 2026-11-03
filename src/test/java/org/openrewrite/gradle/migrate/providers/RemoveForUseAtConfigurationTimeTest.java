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
package org.openrewrite.gradle.migrate.providers;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;
import static org.openrewrite.gradle.Assertions.settingsGradle;
import static org.openrewrite.groovy.Assertions.groovy;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.kotlin.Assertions.kotlin;

class RemoveForUseAtConfigurationTimeTest implements RewriteTest {

    private static final String PROVIDER = """
      package org.gradle.api.provider;
      public interface Provider<T> {
          Provider<T> forUseAtConfigurationTime();
          T get();
      }
      """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RemoveForUseAtConfigurationTime());
    }

    @DocumentExample
    @Test
    void groovyBuildScript() {
        rewriteRun(
          buildGradle(
            """
              def foo = providers.systemProperty('foo').forUseAtConfigurationTime().get()
              """,
            """
              def foo = providers.systemProperty('foo').get()
              """
          )
        );
    }

    @Test
    void kotlinBuildScript() {
        rewriteRun(
          buildGradleKts(
            """
              val foo = providers.systemProperty("foo").forUseAtConfigurationTime().get()
              """,
            """
              val foo = providers.systemProperty("foo").get()
              """
          )
        );
    }

    @Test
    void groovySettingsScript() {
        rewriteRun(
          settingsGradle(
            """
              def foo = providers.gradleProperty('foo').forUseAtConfigurationTime()
              """,
            """
              def foo = providers.gradleProperty('foo')
              """
          )
        );
    }

    @Test
    void keepsTheFormattingOfAChain() {
        rewriteRun(
          buildGradle(
            """
              def foo = providers.environmentVariable('FOO')
                  .forUseAtConfigurationTime()
                  .orElse('bar')
                  .get()
              """,
            """
              def foo = providers.environmentVariable('FOO')
                  .orElse('bar')
                  .get()
              """
          )
        );
    }

    @Test
    void lastStatementOfAClosure() {
        rewriteRun(
          buildGradle(
            """
              def foo = { providers.systemProperty('foo').forUseAtConfigurationTime() }
              """,
            """
              def foo = { providers.systemProperty('foo') }
              """
          )
        );
    }

    @Test
    void javaPluginSource() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion().dependsOn(PROVIDER)),
          java(
            """
              import org.gradle.api.provider.Provider;

              class Plugin {
                  String read(Provider<String> provider) {
                      return provider.forUseAtConfigurationTime().get();
                  }
              }
              """,
            """
              import org.gradle.api.provider.Provider;

              class Plugin {
                  String read(Provider<String> provider) {
                      return provider.get();
                  }
              }
              """
          )
        );
    }

    @Test
    void javaCallAsAStatementLeavesNoBareExpression() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion().dependsOn(PROVIDER)),
          java(
            """
              import org.gradle.api.provider.Provider;

              class Plugin {
                  void read(Provider<String> provider) {
                      provider.forUseAtConfigurationTime();
                      provider.get();
                  }
              }
              """,
            """
              import org.gradle.api.provider.Provider;

              class Plugin {
                  void read(Provider<String> provider) {
                      provider.get();
                  }
              }
              """
          )
        );
    }

    @Test
    void kotlinPluginSource() {
        rewriteRun(
          spec -> spec.parser(KotlinParser.builder().dependsOn(
            """
              package org.gradle.api.provider
              interface Provider<T> {
                  fun forUseAtConfigurationTime(): Provider<T>
                  fun get(): T
              }
              """)),
          kotlin(
            """
              import org.gradle.api.provider.Provider

              fun read(provider: Provider<String>): String =
                  provider.forUseAtConfigurationTime().get()
              """,
            """
              import org.gradle.api.provider.Provider

              fun read(provider: Provider<String>): String =
                  provider.get()
              """
          )
        );
    }

    @Test
    void groovyPluginSource() {
        rewriteRun(
          groovy(
            """
              class Plugin {
                  def read(provider) {
                      provider.forUseAtConfigurationTime().get()
                  }
              }
              """,
            """
              class Plugin {
                  def read(provider) {
                      provider.get()
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeAMethodOfTheSameNameOnAnotherType() {
        rewriteRun(
          java(
            """
              class Other {
                  Other forUseAtConfigurationTime() {
                      return this;
                  }

                  Other read() {
                      return forUseAtConfigurationTime().forUseAtConfigurationTime();
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeAScriptsOwnMethodCalledWithoutAReceiver() {
        rewriteRun(
          buildGradle(
            """
              def forUseAtConfigurationTime() { 'foo' }
              def foo = forUseAtConfigurationTime()
              """
          )
        );
    }

    @Test
    void doNotChangeACallWithArguments() {
        rewriteRun(
          buildGradle(
            """
              def foo = helper.forUseAtConfigurationTime('foo')
              """
          )
        );
    }
}
