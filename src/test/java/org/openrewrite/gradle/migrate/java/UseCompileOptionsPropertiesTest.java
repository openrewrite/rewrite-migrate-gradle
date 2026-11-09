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
package org.openrewrite.gradle.migrate.java;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;

class UseCompileOptionsPropertiesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseCompileOptionsProperties());
    }

    @DocumentExample
    @Test
    void guideExample() {
        rewriteRun(
          buildGradle(
            """
              tasks.withType(JavaCompile) {
                  options.define(encoding: 'UTF-8')
                  options.fork(memoryMaximumSize: '1G')
                  options.debug(debugLevel: 'lines')
              }
              """,
            """
              tasks.withType(JavaCompile) {
                  options.encoding = 'UTF-8'
                  options.fork = true
                  options.forkOptions.memoryMaximumSize = '1G'
                  options.debug = true
                  options.debugOptions.debugLevel = 'lines'
              }
              """
          )
        );
    }

    @Test
    void severalEntries() {
        rewriteRun(
          buildGradle(
            """
              compileJava {
                  options.fork(memoryInitialSize: '256m', memoryMaximumSize: '1G')
                  println 'after'
              }
              """,
            """
              compileJava {
                  options.fork = true
                  options.forkOptions.memoryInitialSize = '256m'
                  options.forkOptions.memoryMaximumSize = '1G'
                  println 'after'
              }
              """
          )
        );
    }

    @Test
    void topLevelQualifiedReceiver() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
              }

              compileJava.options.fork(memoryMaximumSize: '1G')
              tasks.compileTestJava.options.define(encoding: 'UTF-8')
              """,
            """
              plugins {
                  id 'java'
              }

              compileJava.options.fork = true
              compileJava.options.forkOptions.memoryMaximumSize = '1G'
              tasks.compileTestJava.options.encoding = 'UTF-8'
              """
          )
        );
    }

    @Test
    void groovyCompileOptions() {
        rewriteRun(
          buildGradle(
            """
              tasks.withType(GroovyCompile).configureEach {
                  groovyOptions.fork(memoryMaximumSize: '1G')
              }
              """,
            """
              tasks.withType(GroovyCompile).configureEach {
                  groovyOptions.fork = true
                  groovyOptions.forkOptions.memoryMaximumSize = '1G'
              }
              """
          )
        );
    }

    @Test
    void singleLineBlockStaysOnOneLine() {
        rewriteRun(
          buildGradle(
            """
              compileJava { options.fork(memoryMaximumSize: '256m') }
              """,
            """
              compileJava { options.fork = true; options.forkOptions.memoryMaximumSize = '256m' }
              """
          )
        );
    }

    @Test
    void namedTaskAndMapLiteral() {
        rewriteRun(
          buildGradle(
            """
              tasks.named('compileJava') {
                  options.debug([debugLevel: 'lines'])
                  println 'after'
              }
              """,
            """
              tasks.named('compileJava') {
                  options.debug = true
                  options.debugOptions.debugLevel = 'lines'
                  println 'after'
              }
              """
          )
        );
    }

    @Test
    void doNotChangeMapInVariable() {
        rewriteRun(
          buildGradle(
            """
              def forkArgs = [memoryMaximumSize: '1G']
              tasks.withType(JavaCompile) {
                  options.fork(forkArgs)
              }
              """
          )
        );
    }

    @Test
    void doNotChangeOtherOwners() {
        rewriteRun(
          buildGradle(
            """
              options.fork(memoryMaximumSize: '1G')
              custom {
                  options.define(encoding: 'UTF-8')
              }
              tasks.withType(Javadoc) {
                  options.define(encoding: 'UTF-8')
              }
              someOtherTask.options.fork(a: 1)
              """
          )
        );
    }

    @Test
    void doNotChangeAlreadyMigratedOrOtherMethods() {
        rewriteRun(
          buildGradle(
            """
              tasks.withType(JavaCompile) {
                  options.fork = true
                  options.forkOptions.memoryMaximumSize = '1G'
                  options.compilerArgs.add('-parameters')
                  fork(memoryMaximumSize: '1G')
              }
              """
          )
        );
    }

    @Test
    void doNotChangeKotlin() {
        rewriteRun(
          buildGradleKts(
            """
              tasks.withType<JavaCompile> {
                  options.fork(mapOf("memoryMaximumSize" to "1G"))
              }
              """
          )
        );
    }
}
