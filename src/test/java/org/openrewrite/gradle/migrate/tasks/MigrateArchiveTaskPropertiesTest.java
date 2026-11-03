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

class MigrateArchiveTaskPropertiesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateArchiveTaskProperties());
    }

    @DocumentExample
    @Test
    void classifierAlongsideOtherProperties() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
              }

              jar {
                  baseName = 'app'
                  classifier = 'all'
              }
              """,
            """
              plugins {
                  id 'java'
              }

              jar {
                  archiveBaseName = 'app'
                  archiveClassifier = 'all'
              }
              """
          ),
          buildGradleKts(
            """
              plugins {
                  java
              }

              tasks.jar {
                  classifier = "all"
              }
              """,
            """
              plugins {
                  java
              }

              tasks.jar {
                  archiveClassifier = "all"
              }
              """
          )
        );
    }

    @Test
    void allPropertiesInJarBlock() {
        rewriteRun(
          buildGradle(
            """
              plugins {
                  id 'java'
              }

              jar {
                  baseName = 'app'
                  appendix = 'core'
                  version = '1.0'
                  extension = 'zip'
                  destinationDir = file('out')
              }
              """,
            """
              plugins {
                  id 'java'
              }

              jar {
                  archiveBaseName = 'app'
                  archiveAppendix = 'core'
                  archiveVersion = '1.0'
                  archiveExtension = 'zip'
                  destinationDirectory = file('out')
              }
              """
          )
        );
    }

    @Test
    void archiveName() {
        rewriteRun(
          buildGradle(
            """
              jar {
                  archiveName = 'app.jar'
              }
              """,
            """
              jar {
                  archiveFileName = 'app.jar'
              }
              """
          )
        );
    }

    @Test
    void methodForms() {
        rewriteRun(
          buildGradle(
            """
              jar {
                  baseName 'app'
                  setAppendix('core')
                  version('1.0')
                  extension 'zip'
                  destinationDir file('out')
              }
              """,
            """
              jar {
                  archiveBaseName = 'app'
                  archiveAppendix = 'core'
                  archiveVersion = '1.0'
                  archiveExtension = 'zip'
                  destinationDirectory = file('out')
              }
              """
          )
        );
    }

    @Test
    void provenTaskOwners() {
        rewriteRun(
          buildGradle(
            """
              task docs(type: Jar) {
                  baseName = 'docs'
              }
              task('dist', type: Zip) {
                  baseName = 'dist'
              }
              tasks.register('reg', Tar) { baseName = 'reg' }
              tasks.create('created', org.gradle.api.tasks.bundling.Jar) { baseName = 'created' }
              tasks.withType(Zip) { baseName = 'withType' }
              tasks.withType(Jar).configureEach { baseName = 'configureEach' }
              tasks.named('docs') { baseName = 'named' }
              tasks.named('other', Jar) { baseName = 'namedTyped' }
              tasks.named('jar').configure { baseName = 'configure' }
              tasks.jar { baseName = 'tasksDot' }
              docs { baseName = 'declared' }
              bootJar { baseName = 'boot' }
              shadowJar { version = '1.0' }
              war { baseName = 'war' }
              """,
            """
              task docs(type: Jar) {
                  archiveBaseName = 'docs'
              }
              task('dist', type: Zip) {
                  archiveBaseName = 'dist'
              }
              tasks.register('reg', Tar) { archiveBaseName = 'reg' }
              tasks.create('created', org.gradle.api.tasks.bundling.Jar) { archiveBaseName = 'created' }
              tasks.withType(Zip) { archiveBaseName = 'withType' }
              tasks.withType(Jar).configureEach { archiveBaseName = 'configureEach' }
              tasks.named('docs') { archiveBaseName = 'named' }
              tasks.named('other', Jar) { archiveBaseName = 'namedTyped' }
              tasks.named('jar').configure { archiveBaseName = 'configure' }
              tasks.jar { archiveBaseName = 'tasksDot' }
              docs { archiveBaseName = 'declared' }
              bootJar { archiveBaseName = 'boot' }
              shadowJar { archiveVersion = '1.0' }
              war { archiveBaseName = 'war' }
              """
          )
        );
    }

    @Test
    void insideProjectBlocks() {
        rewriteRun(
          buildGradle(
            """
              subprojects {
                  jar {
                      baseName = 'a'
                  }
              }
              allprojects {
                  afterEvaluate {
                      tasks.withType(Jar) {
                          version = '1.0'
                      }
                  }
              }
              plugins.withId('java') {
                  jar {
                      extension = 'zip'
                  }
              }
              """,
            """
              subprojects {
                  jar {
                      archiveBaseName = 'a'
                  }
              }
              allprojects {
                  afterEvaluate {
                      tasks.withType(Jar) {
                          archiveVersion = '1.0'
                      }
                  }
              }
              plugins.withId('java') {
                  jar {
                      archiveExtension = 'zip'
                  }
              }
              """
          )
        );
    }

    @Test
    void writesThroughTaskReference() {
        rewriteRun(
          buildGradle(
            """
              task docs(type: Jar)
              jar.baseName = 'app'
              tasks.jar.version = '1.0'
              docs.setBaseName('docs')
              """,
            """
              task docs(type: Jar)
              jar.archiveBaseName = 'app'
              tasks.jar.archiveVersion = '1.0'
              docs.archiveBaseName = 'docs'
              """
          )
        );
    }

    @Test
    void stringDestinationIsWrappedInFile() {
        rewriteRun(
          buildGradle(
            """
              jar {
                  destinationDir = "$buildDir/out"
              }
              tasks.withType(Zip) {
                  destinationDir = 'out'
              }
              tasks.withType(Tar) {
                  destinationDir 'tar'
              }
              """,
            """
              jar {
                  destinationDirectory = file("$buildDir/out")
              }
              tasks.withType(Zip) {
                  destinationDirectory = file('out')
              }
              tasks.withType(Tar) {
                  destinationDirectory = file('tar')
              }
              """
          )
        );
    }

    @Test
    void lastStatementOfClosure() {
        rewriteRun(
          buildGradle(
            """
              jar { baseName 'app' }
              tasks.withType(Zip) { setVersion('1.0') }
              """,
            """
              jar { archiveBaseName = 'app' }
              tasks.withType(Zip) { archiveVersion = '1.0' }
              """
          )
        );
    }

    @Test
    void runsBeforeUseAssignmentForPropertySyntaxInGradle8Migration() {
        rewriteRun(
          spec -> spec.recipeFromResources("org.openrewrite.gradle.migrate.MigrateToGradle8"),
          buildGradle(
            """
              jar {
                  version '1.0'
                  baseName 'app'
              }
              """,
            """
              jar {
                  archiveVersion = '1.0'
                  archiveBaseName = 'app'
              }
              """
          )
        );
    }

    @Test
    void kotlinTaskOwners() {
        rewriteRun(
          buildGradleKts(
            """
              plugins {
                  java
              }

              tasks.jar {
                  baseName = "app"
                  appendix = "core"
                  version = "1.0"
                  extension = "zip"
                  destinationDir = file("out")
                  archiveName = "x.jar"
              }
              tasks.named<Jar>("jar") {
                  baseName = "named"
              }
              tasks.withType<Zip> {
                  baseName = "withType"
              }
              tasks.withType<Zip>().configureEach {
                  baseName = "configureEach"
              }
              tasks.withType(Jar::class) {
                  baseName = "class"
              }
              tasks.withType(Jar::class.java) {
                  baseName = "javaClass"
              }
              tasks.register<Jar>("docs") {
                  baseName = "registered"
              }
              tasks.create<Jar>("docs2") {
                  baseName = "created"
              }
              task<Jar>("docs3") {
                  baseName = "task"
              }
              val docs4 by tasks.registering(Jar::class) {
                  baseName = "delegate"
              }
              tasks {
                  jar {
                      baseName = "inTasks"
                  }
                  withType<Jar> {
                      baseName = "withTypeInTasks"
                  }
              }
              """,
            """
              plugins {
                  java
              }

              tasks.jar {
                  archiveBaseName = "app"
                  archiveAppendix = "core"
                  archiveVersion = "1.0"
                  archiveExtension = "zip"
                  destinationDirectory = file("out")
                  archiveFileName = "x.jar"
              }
              tasks.named<Jar>("jar") {
                  archiveBaseName = "named"
              }
              tasks.withType<Zip> {
                  archiveBaseName = "withType"
              }
              tasks.withType<Zip>().configureEach {
                  archiveBaseName = "configureEach"
              }
              tasks.withType(Jar::class) {
                  archiveBaseName = "class"
              }
              tasks.withType(Jar::class.java) {
                  archiveBaseName = "javaClass"
              }
              tasks.register<Jar>("docs") {
                  archiveBaseName = "registered"
              }
              tasks.create<Jar>("docs2") {
                  archiveBaseName = "created"
              }
              task<Jar>("docs3") {
                  archiveBaseName = "task"
              }
              val docs4 by tasks.registering(Jar::class) {
                  archiveBaseName = "delegate"
              }
              tasks {
                  jar {
                      archiveBaseName = "inTasks"
                  }
                  withType<Jar> {
                      archiveBaseName = "withTypeInTasks"
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeProjectProperties() {
        rewriteRun(
          buildGradle(
            """
              version = '1.0'
              ext.extension = 'x'
              project.version = '2.0'
              subprojects {
                  version = '3.0'
              }
              """
          )
        );
    }

    @Test
    void doNotChangeProjectPropertiesInKotlin() {
        rewriteRun(
          buildGradleKts(
            """
              version = "1.0"
              subprojects {
                  version = "3.0"
              }
              """
          )
        );
    }

    @Test
    void doNotChangeNestedDelegates() {
        rewriteRun(
          buildGradle(
            """
              jar {
                  manifest {
                      version = '1.0'
                  }
                  doLast {
                      baseName = 'x'
                  }
                  from('x') {
                      extension = 'y'
                  }
              }
              publishing {
                  publications {
                      mavenJava(MavenPublication) {
                          version = '1.0'
                          jar { baseName = 'x' }
                      }
                  }
              }
              android {
                  jar {
                      baseName = 'x'
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeUnprovenTasks() {
        rewriteRun(
          buildGradle(
            """
              task plain {
                  version = '1.0'
              }
              task custom(type: Foo) {
                  baseName = 'x'
              }
              custom {
                  baseName = 'x'
              }
              tasks.register('jar', Foo) {
                  baseName = 'x'
              }
              tasks.named('unknown') {
                  baseName = 'x'
              }
              tasks.withType(Foo) {
                  extension = 'x'
              }
              unknown {
                  baseName = 'x'
              }
              """
          )
        );
    }

    @Test
    void doNotChangeTaskRedeclaredWithAnotherType() {
        rewriteRun(
          buildGradle(
            """
              task jar(type: Foo)
              jar {
                  baseName = 'x'
              }
              """
          )
        );
    }

    @Test
    void doNotChangeLocalJarClass() {
        rewriteRun(
          buildGradle(
            """
              import com.acme.Jar

              tasks.withType(Jar) {
                  baseName = 'x'
              }
              """
          )
        );
    }

    @Test
    void doNotChangeScriptVariables() {
        rewriteRun(
          buildGradle(
            """
              def baseName = 'x'
              jar {
                  baseName = 'y'
              }
              """
          ),
          buildGradleKts(
            """
              val version = "x"
              """
          )
        );
    }

    @Test
    void doNotChangeReadsOrModernForms() {
        rewriteRun(
          buildGradle(
            """
              def path = jar.archivePath
              def a = jar.baseName
              jar {
                  archiveBaseName = 'x'
                  archiveVersion.set('1')
                  from(destinationDir)
                  println(version)
              }
              """
          )
        );
    }

    @Test
    void doNotChangeNonStringVersionLiteral() {
        rewriteRun(
          buildGradle(
            """
              jar {
                  version = 9
              }
              """
          )
        );
    }

    @Test
    void doNotChangeClosureWithParameter() {
        rewriteRun(
          buildGradle(
            """
              tasks.withType(Jar) { t ->
                  baseName = 'x'
              }
              """
          )
        );
    }
}
