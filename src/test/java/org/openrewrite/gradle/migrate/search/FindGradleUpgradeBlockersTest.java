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
package org.openrewrite.gradle.migrate.search;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;
import static org.openrewrite.gradle.Assertions.settingsGradle;

class FindGradleUpgradeBlockersTest {

    @Nested
    class Composite implements RewriteTest {
        @DocumentExample
        @Test
        void marksEachWithoutChangingCode() {
            rewriteRun(
              spec -> spec.recipeFromResources("org.openrewrite.gradle.migrate.search.FindGradleUpgradeBlockers"),
              buildGradle(
                """
                  plugins {
                      id 'findbugs'
                  }
                  dependencies {
                      compile files('x')
                  }
                  tasks.register('otherTest', Test)
                  project.exec {
                      commandLine 'true'
                  }
                  println 'git status'.execute().text
                  """,
                """
                  plugins {
                      /*~~(`findbugs` was removed in Gradle 6: the guide suggests the `com.github.spotbugs` plugin)~~>*/id 'findbugs'
                  }
                  dependencies {
                      /*~~(The `compile` configuration no longer exists: use `api` or `implementation`)~~>*/compile files('x')
                  }
                  /*~~(Custom Test task without both testClassesDirs and classpath: on Gradle 9 it runs no tests and still succeeds)~~>*/tasks.register('otherTest', Test)
                  /*~~(`exec` is removed in Gradle 9: use an `Exec` or `JavaExec` task, an injected `ExecOperations`, or `providers.exec`)~~>*/project.exec {
                      commandLine 'true'
                  }
                  println /*~~(Starts a process at configuration time, deprecated since Gradle 9 and an error in Gradle 11: move it into a task or use `providers.exec`)~~>*/'git status'.execute().text
                  """
              )
            );
        }

        @Test
        void leavesCleanBuildAlone() {
            rewriteRun(
              spec -> spec.recipeFromResources("org.openrewrite.gradle.migrate.search.FindGradleUpgradeBlockers"),
              buildGradle(
                """
                  plugins {
                      id 'java'
                  }
                  dependencies {
                      implementation files('x')
                  }
                  tasks.register('otherTest', Test) {
                      testClassesDirs = sourceSets.test.output.classesDirs
                      classpath = sourceSets.test.runtimeClasspath
                  }
                  """
              )
            );
        }
    }

    @Nested
    class CustomTestTasks implements RewriteTest {
        private static final String MARK = "/*~~(Custom Test task without both testClassesDirs and classpath: on Gradle 9 it runs no tests and still succeeds)~~>*/";

        @Override
        public void defaults(RecipeSpec spec) {
            spec.recipe(new FindCustomTestTasksWithoutTestClasses());
        }

        @Test
        void groovyForms() {
            rewriteRun(
              buildGradle(
                """
                  tasks.register('a', Test) {
                      useJUnit()
                  }
                  tasks.create('b', Test) {
                      testClassesDirs = files('x')
                  }
                  """,
                """
                  %stasks.register('a', Test) {
                      useJUnit()
                  }
                  %stasks.create('b', Test) {
                      testClassesDirs = files('x')
                  }
                  """.formatted(MARK, MARK)
              )
            );
        }

        @Test
        void oldStyleTask() {
            rewriteRun(
              buildGradle(
                """
                  task integration(type: Test) {
                      useJUnit()
                  }
                  task('other', type: Test) {
                      useJUnit()
                  }
                  """,
                """
                  task %sintegration(type: Test) {
                      useJUnit()
                  }
                  %stask('other', type: Test) {
                      useJUnit()
                  }
                  """.formatted(MARK, MARK)
              )
            );
        }

        @Test
        void kotlinForms() {
            rewriteRun(
              buildGradleKts(
                """
                  tasks.register<Test>("a")
                  tasks.register("b", Test::class) {
                      classpath = files("x")
                  }
                  """,
                """
                  %stasks.register<Test>("a")
                  %stasks.register("b", Test::class) {
                      classpath = files("x")
                  }
                  """.formatted(MARK, MARK)
              )
            );
        }

        @Test
        void doNotMarkConfiguredOrOtherTasks() {
            rewriteRun(
              buildGradle(
                """
                  tasks.register('a', Test) {
                      testClassesDirs = sourceSets.test.output.classesDirs
                      classpath = sourceSets.test.runtimeClasspath
                  }
                  tasks.register('b', Test) {
                      setTestClassesDirs(files('x'))
                      classpath += files('y')
                  }
                  tasks.withType(Test) {
                      useJUnit()
                  }
                  tasks.named('test', Test) {
                      useJUnit()
                  }
                  test {
                      useJUnit()
                  }
                  tasks.register('c', Copy)
                  """
              ),
              buildGradleKts(
                """
                  tasks.register<Test>("a") {
                      testClassesDirs = files("x")
                      classpath = files("y")
                  }
                  tasks.withType<Test> {
                  }
                  """
              )
            );
        }
    }

    @Nested
    class RemovedConfigurations implements RewriteTest {
        @Override
        public void defaults(RecipeSpec spec) {
            spec.recipe(new FindRemovedDependencyConfigurations());
        }

        @Test
        void groovy() {
            rewriteRun(
              buildGradle(
                """
                  dependencies {
                      compile files('a')
                      runtime 'g:a:1'
                      testCompile project(':b')
                      testRuntime('g:c:1') {
                          transitive = false
                      }
                  }
                  """,
                """
                  dependencies {
                      /*~~(The `compile` configuration no longer exists: use `api` or `implementation`)~~>*/compile files('a')
                      /*~~(The `runtime` configuration no longer exists: use `runtimeOnly`)~~>*/runtime 'g:a:1'
                      /*~~(The `testCompile` configuration no longer exists: use `testImplementation`)~~>*/testCompile project(':b')
                      /*~~(The `testRuntime` configuration no longer exists: use `testRuntimeOnly`)~~>*/testRuntime('g:c:1') {
                          transitive = false
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
                  dependencies {
                      compile(files("a"))
                      "runtime"(files("b"))
                  }
                  """,
                """
                  dependencies {
                      /*~~(The `compile` configuration no longer exists: use `api` or `implementation`)~~>*/compile(files("a"))
                      /*~~(The `runtime` configuration no longer exists: use `runtimeOnly`)~~>*/"runtime"(files("b"))
                  }
                  """
              )
            );
        }

        @Test
        void doNotMarkOwnConfigurationOrOutsideDependencies() {
            rewriteRun(
              buildGradle(
                """
                  configurations {
                      compile
                  }
                  dependencies {
                      compile files('a')
                      implementation files('b')
                  }
                  runtime('x')
                  """
              ),
              buildGradleKts(
                """
                  configurations.create("runtime")
                  dependencies {
                      "runtime"(files("a"))
                  }
                  """
              )
            );
        }
    }

    @Nested
    class ProjectExec implements RewriteTest {
        private static final String EXEC = "/*~~(`exec` is removed in Gradle 9: use an `Exec` or `JavaExec` task, an injected `ExecOperations`, or `providers.exec`)~~>*/";
        private static final String JAVAEXEC = "/*~~(`javaexec` is removed in Gradle 9: use an `Exec` or `JavaExec` task, an injected `ExecOperations`, or `providers.javaexec`)~~>*/";

        @Override
        public void defaults(RecipeSpec spec) {
            spec.recipe(new FindProjectExecCalls());
        }

        @Test
        void groovy() {
            rewriteRun(
              buildGradle(
                """
                  project.exec {
                      commandLine 'true'
                  }
                  exec {
                      commandLine 'true'
                  }
                  tasks.register('t') {
                      doLast {
                          project.javaexec {
                              mainClass = 'x'
                          }
                      }
                  }
                  """,
                """
                  %sproject.exec {
                      commandLine 'true'
                  }
                  %sexec {
                      commandLine 'true'
                  }
                  tasks.register('t') {
                      doLast {
                          %sproject.javaexec {
                              mainClass = 'x'
                          }
                      }
                  }
                  """.formatted(EXEC, EXEC, JAVAEXEC)
              )
            );
        }

        @Test
        void kotlin() {
            rewriteRun(
              buildGradleKts(
                """
                  exec {
                      commandLine("true")
                  }
                  project.javaexec {
                      mainClass.set("x")
                  }
                  """,
                """
                  %sexec {
                      commandLine("true")
                  }
                  %sproject.javaexec {
                      mainClass.set("x")
                  }
                  """.formatted(EXEC, JAVAEXEC)
              )
            );
        }

        @Test
        void doNotMarkSupportedForms() {
            rewriteRun(
              buildGradle(
                """
                  providers.exec {
                      commandLine 'true'
                  }
                  tasks.register('a', Exec) {
                      commandLine 'true'
                  }
                  abstract class MyTask extends DefaultTask {
                      @Inject abstract ExecOperations getOps()
                      @TaskAction void run() {
                          ops.exec {
                              commandLine 'true'
                          }
                      }
                  }
                  """
              )
            );
        }
    }

    @Nested
    class ConfigurationTimeExecute implements RewriteTest {
        private static final String MARK = "/*~~(Starts a process at configuration time, deprecated since Gradle 9 and an error in Gradle 11: move it into a task or use `providers.exec`)~~>*/";

        @Override
        public void defaults(RecipeSpec spec) {
            spec.recipe(new FindConfigurationTimeExecute());
        }

        @Test
        void literalGStringAndList() {
            rewriteRun(
              buildGradle(
                """
                  def a = 'git status'.execute().text
                  def b = "git log ${version}".execute()
                  def c = ['git', 'status'].execute()
                  tasks.register('t') {
                      println 'date'.execute().text
                  }
                  """,
                """
                  def a = %s'git status'.execute().text
                  def b = %s"git log ${version}".execute()
                  def c = %s['git', 'status'].execute()
                  tasks.register('t') {
                      println %s'date'.execute().text
                  }
                  """.formatted(MARK, MARK, MARK, MARK)
              )
            );
        }

        @Test
        void settingsScript() {
            rewriteRun(
              settingsGradle(
                """
                  def a = 'git status'.execute().text
                  """,
                """
                  def a = %s'git status'.execute().text
                  """.formatted(MARK)
              )
            );
        }

        @Test
        void doNotMarkExecutionTimeOrOtherCalls() {
            rewriteRun(
              buildGradle(
                """
                  tasks.register('t') {
                      doLast {
                          println 'date'.execute().text
                      }
                      doFirst {
                          println 'date'.execute().text
                      }
                  }
                  class Action {
                      def run() {
                          'date'.execute()
                      }
                  }
                  def cmd = 'date'
                  someTask.execute()
                  cmd.execute()
                  """
              ),
              buildGradleKts(
                """
                  fun String.execute() = this
                  val a = "date".execute()
                  """
              )
            );
        }
    }

    @Nested
    class Impldep implements RewriteTest {
        private static final String MARK = "/*~~(Gradle's relocated internal dependencies are not visible to Kotlin scripts on Gradle 9: add the original library to the build logic)~~>*/";

        @Override
        public void defaults(RecipeSpec spec) {
            spec.recipe(new FindInternalImpldepImports());
        }

        @Test
        void kotlinImport() {
            rewriteRun(
              buildGradleKts(
                """
                  import org.gradle.internal.impldep.com.google.common.collect.ImmutableList

                  println(ImmutableList.of("x"))
                  """,
                """
                  %simport org.gradle.internal.impldep.com.google.common.collect.ImmutableList

                  println(ImmutableList.of("x"))
                  """.formatted(MARK)
              )
            );
        }

        @Test
        void doNotMarkOtherImportsOrGroovy() {
            rewriteRun(
              buildGradleKts(
                """
                  import org.gradle.api.tasks.bundling.Zip

                  println(Zip::class)
                  """
              ),
              buildGradle(
                """
                  import org.gradle.internal.impldep.com.google.common.collect.ImmutableList
                  """
              )
            );
        }
    }

    @Nested
    class RemovedPlugins implements RewriteTest {
        @Override
        public void defaults(RecipeSpec spec) {
            spec.recipe(new FindRemovedPlugins());
        }

        @Test
        void groovy() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'jdepend'
                  }
                  apply plugin: 'osgi'
                  """,
                """
                  plugins {
                      /*~~(`jdepend` was removed in Gradle 6: the guide suggests a community analysis plugin)~~>*/id 'jdepend'
                  }
                  /*~~(`osgi` was removed in Gradle 6: the guide suggests a community OSGi plugin)~~>*/apply plugin: 'osgi'
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
                      id("play")
                  }
                  apply(plugin = "findbugs")
                  """,
                """
                  plugins {
                      /*~~(`play` was removed in Gradle 6: the guide suggests the Play Framework plugin on the plugin portal)~~>*/id("play")
                  }
                  /*~~(`findbugs` was removed in Gradle 6: the guide suggests the `com.github.spotbugs` plugin)~~>*/apply(plugin = "findbugs")
                  """
              )
            );
        }

        @Test
        void doNotMarkOtherPlugins() {
            rewriteRun(
              buildGradle(
                """
                  plugins {
                      id 'com.github.spotbugs' version '6.0.0'
                      id 'java'
                  }
                  apply plugin: 'application'
                  apply from: 'findbugs'
                  someExtension {
                      id 'findbugs'
                  }
                  """
              )
            );
        }
    }
}
