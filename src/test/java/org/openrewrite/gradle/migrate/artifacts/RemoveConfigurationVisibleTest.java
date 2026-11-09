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

class RemoveConfigurationVisibleTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RemoveConfigurationVisible());
    }

    @DocumentExample
    @Test
    void groovyConfigurationsBlock() {
        rewriteRun(
          buildGradle(
            """
              configurations {
                  foo {
                      visible = false
                      canBeConsumed = false
                  }
                  bar {
                      canBeConsumed = false
                      visible true
                  }
              }
              """,
            """
              configurations {
                  foo {
                      canBeConsumed = false
                  }
                  bar {
                      canBeConsumed = false
                  }
              }
              """
          )
        );
    }

    @Test
    void groovyKeepsAConfigurationBlockThatDeclaresTheConfiguration() {
        rewriteRun(
          buildGradle(
            """
              configurations {
                  foo {
                      visible = false
                  }
              }
              """,
            """
              configurations {
                  foo {
                  }
              }
              """
          )
        );
    }

    @Test
    void groovyKeepsCreate() {
        rewriteRun(
          buildGradle(
            """
              configurations.create('foo') {
                  visible = false
              }
              """,
            """
              configurations.create('foo') {
              }
              """
          )
        );
    }

    @Test
    void groovyExistingConfiguration() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
              }
              configurations.compileClasspath.visible = false
              configurations.runtimeClasspath.setVisible(false)
              configurations.all {
                  it.visible = true
                  transitive = false
              }
              """,
            """
              plugins {
                  id 'java'
              }
              configurations.all {
                  transitive = false
              }
              """
          )
        );
    }

    @Test
    void groovyDropsABlockLeftEmptyOnAnExistingConfiguration() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
              }

              configurations.named('runtimeClasspath') {
                  visible = false
              }
              """,
            """
              plugins {
                  id 'java'
              }
              """
          )
        );
    }

    @Test
    void kotlinConfigurationsBlock() {
        rewriteRun(
          buildGradleKts(
            """
              configurations {
                  create("foo") {
                      isVisible = false
                      isCanBeConsumed = false
                  }
              }
              """,
            """
              configurations {
                  create("foo") {
                      isCanBeConsumed = false
                  }
              }
              """
          )
        );
    }

    @Test
    void kotlinRegisterAndCreating() {
        rewriteRun(
          buildGradleKts(
            """
              configurations.register("foo") {
                  isCanBeConsumed = false
                  isVisible = false
              }
              val bar by configurations.creating {
                  isVisible = false
                  isCanBeConsumed = false
              }
              """,
            """
              configurations.register("foo") {
                  isCanBeConsumed = false
              }
              val bar by configurations.creating {
                  isCanBeConsumed = false
              }
              """
          )
        );
    }

    @Test
    void kotlinExistingConfiguration() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  java
              }
              configurations.getByName("compileClasspath").isVisible = false
              configurations.all {
                  isVisible = true
                  isTransitive = false
              }
              """,
            """
              plugins {
                  java
              }
              configurations.all {
                  isTransitive = false
              }
              """
          )
        );
    }

    @Test
    void doNotDeleteTheCreationOfAConfiguration() {
        rewriteRun(
          buildGradle(
            """
              configurations.create('foo').visible = false
              configurations.maybeCreate('bar').setVisible(false)
              """
          )
        );
    }

    @Test
    void kotlinDoNotDeleteTheCreationOfAConfiguration() {
        rewriteRun(
          buildGradleKts(
            """
              configurations.maybeCreate("foo").apply {
                  isVisible = false
              }
              """,
            """
              configurations.maybeCreate("foo").apply {
              }
              """
          )
        );
    }

    @Test
    void doNotChangeAScriptsOwnVisible() {
        rewriteRun(
          buildGradle(
            """
              def visible = true
              visible = false
              ext.visible = false
              """
          )
        );
    }

    @Test
    void doNotChangeVisibleOutsideAConfiguration() {
        rewriteRun(
          buildGradle(
            """
              tasks.register('dialog') {
                  visible = false
              }
              splash {
                  visible = false
                  visible true
              }
              window.visible = false
              window.setVisible(false)
              """
          )
        );
    }

    @Test
    void doNotChangeAKotlinIsVisibleOutsideAConfiguration() {
        rewriteRun(
          buildGradleKts(
            """
              var isVisible = true
              isVisible = false
              window.isVisible = false
              splash {
                  isVisible = false
              }
              """
          )
        );
    }

    @Test
    void doNotChangeAReadOfVisible() {
        rewriteRun(
          buildGradle(
            """
              def shown = configurations.compileClasspath.visible
              println(configurations.runtimeClasspath.visible)
              """
          )
        );
    }

    @Test
    void doNotChangeSomeOtherPropertyOfAConfiguration() {
        rewriteRun(
          buildGradle(
            """
              configurations {
                  foo {
                      transitive = false
                  }
              }
              """
          )
        );
    }
}
