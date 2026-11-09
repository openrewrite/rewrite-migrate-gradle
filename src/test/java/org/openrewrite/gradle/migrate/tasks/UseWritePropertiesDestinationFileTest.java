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

class UseWritePropertiesDestinationFileTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseWritePropertiesDestinationFile());
    }

    @DocumentExample
    @Test
    void registerGroovy() {
        rewriteRun(
          buildGradle(
            """
              tasks.register('writeProps', WriteProperties) {
                  outputFile = file('a.properties')
                  property('a', 'b')
              }
              """,
            """
              tasks.register('writeProps', WriteProperties) {
                  destinationFile = file('a.properties')
                  property('a', 'b')
              }
              """
          )
        );
    }

    @Test
    void stringValueIsWrappedInFile() {
        rewriteRun(
          buildGradle(
            """
              tasks.register('a', WriteProperties) {
                  outputFile = 'a.properties'
              }
              task b(type: WriteProperties) {
                  outputFile = "$buildDir/b.properties"
              }
              """,
            """
              tasks.register('a', WriteProperties) {
                  destinationFile = file('a.properties')
              }
              task b(type: WriteProperties) {
                  destinationFile = file("$buildDir/b.properties")
              }
              """
          )
        );
    }

    @Test
    void withTypeGroovy() {
        rewriteRun(
          buildGradle(
            """
              tasks.withType(WriteProperties).configureEach {
                  outputFile = layout.buildDirectory.file('a.properties').get().asFile
              }
              """,
            """
              tasks.withType(WriteProperties).configureEach {
                  destinationFile = layout.buildDirectory.file('a.properties').get().asFile
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
              tasks.register<WriteProperties>("writeProps") {
                  outputFile = layout.buildDirectory.file("a.properties").get().asFile
              }
              tasks.named("other", WriteProperties::class) {
                  outputFile = file("b.properties")
              }
              """,
            """
              tasks.register<WriteProperties>("writeProps") {
                  destinationFile = layout.buildDirectory.file("a.properties").get().asFile
              }
              tasks.named("other", WriteProperties::class) {
                  destinationFile = file("b.properties")
              }
              """
          )
        );
    }

    @Test
    void doNotChangeOtherTaskTypes() {
        rewriteRun(
          buildGradle(
            """
              def outputFile = file('x')
              outputFile = file('y')
              tasks.register('a', DependencyReportTask) {
                  outputFile = file('a')
              }
              tasks.named('b') {
                  outputFile = file('b')
              }
              custom {
                  outputFile = file('c')
              }
              """
          ),
          buildGradleKts(
            """
              tasks.withType<DependencyReportTask>().configureEach {
                  outputFile = file("a")
              }
              """
          )
        );
    }

    @Test
    void doNotChangeReads() {
        rewriteRun(
          buildGradle(
            """
              tasks.register('a', WriteProperties) {
                  destinationFile = file('a.properties')
                  doLast {
                      println outputFile
                  }
              }
              """
          )
        );
    }
}
