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
package org.openrewrite.gradle.migrate.model;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;
import static org.openrewrite.groovy.Assertions.groovy;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.kotlin.Assertions.kotlin;

class UseObjectFactoryDomainObjectContainerTest implements RewriteTest {

    private static final String[] GRADLE_API = {
      """
        package org.gradle.api;
        public interface NamedDomainObjectContainer<T> {
        }
        """,
      """
        package org.gradle.api.model;
        public interface ObjectFactory {
            <T> org.gradle.api.NamedDomainObjectContainer<T> domainObjectContainer(Class<T> elementType);
        }
        """,
      """
        package org.gradle.api;
        public interface Project {
            org.gradle.api.model.ObjectFactory getObjects();
            <T> NamedDomainObjectContainer<T> container(Class<T> type);
            <T> NamedDomainObjectContainer<T> container(Class<T> type, groovy.lang.Closure factory);
        }
        """,
    };

    private static final String KOTLIN_GRADLE_API = """
      package org.gradle.api
      interface NamedDomainObjectContainer<T>
      interface ObjectFactory {
          fun <T> domainObjectContainer(elementType: Class<T>): NamedDomainObjectContainer<T>
      }
      interface Project {
          val objects: ObjectFactory
          fun <T> container(type: Class<T>): NamedDomainObjectContainer<T>
      }
      """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseObjectFactoryDomainObjectContainer());
    }

    @DocumentExample
    @Test
    void groovyScript() {
        rewriteRun(
          buildGradle(
            """
              class Foo {
                  String name
                  Foo(String name) { this.name = name }
              }
              def foos = container(Foo)
              def bars = project.container(Foo)
              """,
            """
              class Foo {
                  String name
                  Foo(String name) { this.name = name }
              }
              def foos = objects.domainObjectContainer(Foo)
              def bars = project.objects.domainObjectContainer(Foo)
              """
          )
        );
    }

    @Test
    void groovyScriptWithAClassFromElsewhere() {
        rewriteRun(
          buildGradle(
            """
              extensions.add('foos', container(com.acme.Foo))
              """,
            """
              extensions.add('foos', objects.domainObjectContainer(com.acme.Foo))
              """
          )
        );
    }

    @Test
    void kotlinScript() {
        rewriteRun(
          buildGradleKts(
            """
              abstract class Foo @javax.inject.Inject constructor(val name: String)
              val foos = container(Foo::class.java)
              val bars = project.container(Foo::class.java)
              """,
            """
              abstract class Foo @javax.inject.Inject constructor(val name: String)
              val foos = objects.domainObjectContainer(Foo::class.java)
              val bars = project.objects.domainObjectContainer(Foo::class.java)
              """
          )
        );
    }

    @Test
    void kotlinScriptWithAnOpenClass() {
        rewriteRun(
          buildGradleKts(
            """
              open class Foo(val name: String)
              val foos = container(Foo::class.java)
              """,
            """
              open class Foo(val name: String)
              val foos = objects.domainObjectContainer(Foo::class.java)
              """
          )
        );
    }

    @Test
    void javaPluginSource() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion().dependsOn(GRADLE_API)),
          java(
            """
              import org.gradle.api.NamedDomainObjectContainer;
              import org.gradle.api.Project;

              class Plugin {
                  public static class Foo {
                      public Foo(String name) {
                      }
                  }

                  NamedDomainObjectContainer<Foo> foos(Project project) {
                      return project.container(Foo.class);
                  }
              }
              """,
            """
              import org.gradle.api.NamedDomainObjectContainer;
              import org.gradle.api.Project;

              class Plugin {
                  public static class Foo {
                      public Foo(String name) {
                      }
                  }

                  NamedDomainObjectContainer<Foo> foos(Project project) {
                      return project.getObjects().domainObjectContainer(Foo.class);
                  }
              }
              """
          )
        );
    }

    @Test
    void kotlinPluginSource() {
        rewriteRun(
          spec -> spec.parser(KotlinParser.builder().dependsOn(KOTLIN_GRADLE_API)),
          kotlin(
            """
              import org.gradle.api.Project

              open class Foo(val name: String)

              fun foos(project: Project) = project.container(Foo::class.java)
              """,
            """
              import org.gradle.api.Project

              open class Foo(val name: String)

              fun foos(project: Project) = project.objects.domainObjectContainer(Foo::class.java)
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
                  def foos(project) {
                      project.container(com.acme.Foo)
                  }
              }
              """,
            """
              class Plugin {
                  def foos(project) {
                      project.objects.domainObjectContainer(com.acme.Foo)
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeAFinalGroovyClass() {
        rewriteRun(
          buildGradle(
            """
              final class Foo {
                  String name
                  Foo(String name) { this.name = name }
              }
              def foos = container(Foo)
              """
          )
        );
    }

    @Test
    void doNotChangeAKotlinClassThatIsFinalByDefault() {
        rewriteRun(
          buildGradleKts(
            """
              class Foo(val name: String)
              val foos = container(Foo::class.java)
              """
          )
        );
    }

    @Test
    void doNotChangeAKotlinClassFromElsewhere() {
        rewriteRun(
          buildGradleKts(
            """
              val foos = container(com.acme.Foo::class.java)
              """
          )
        );
    }

    @Test
    void doNotChangeAFinalJavaClass() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion().dependsOn(GRADLE_API)),
          java(
            """
              import org.gradle.api.NamedDomainObjectContainer;
              import org.gradle.api.Project;

              class Plugin {
                  public static final class Foo {
                      public Foo(String name) {
                      }
                  }

                  NamedDomainObjectContainer<Foo> foos(Project project) {
                      return project.container(Foo.class);
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeAJavaClassWithoutAPublicConstructor() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion().dependsOn(GRADLE_API)),
          java(
            """
              import org.gradle.api.NamedDomainObjectContainer;
              import org.gradle.api.Project;

              class Plugin {
                  public static class Foo {
                      Foo(String name) {
                      }
                  }

                  NamedDomainObjectContainer<Foo> foos(Project project) {
                      return project.container(Foo.class);
                  }
              }
              """
          )
        );
    }

    @Test
    void javaInterface() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion().dependsOn(GRADLE_API)),
          java(
            """
              import org.gradle.api.NamedDomainObjectContainer;
              import org.gradle.api.Project;

              class Plugin {
                  public interface Foo {
                      String getName();
                  }

                  NamedDomainObjectContainer<Foo> foos(Project project) {
                      return project.container(Foo.class);
                  }
              }
              """,
            """
              import org.gradle.api.NamedDomainObjectContainer;
              import org.gradle.api.Project;

              class Plugin {
                  public interface Foo {
                      String getName();
                  }

                  NamedDomainObjectContainer<Foo> foos(Project project) {
                      return project.getObjects().domainObjectContainer(Foo.class);
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeAVariableHoldingAClass() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion().dependsOn(GRADLE_API)),
          java(
            """
              import org.gradle.api.NamedDomainObjectContainer;
              import org.gradle.api.Project;

              class Plugin {
                  <T> NamedDomainObjectContainer<T> foos(Project project, Class<T> type) {
                      return project.container(type);
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeTheClosureOverload() {
        rewriteRun(
          buildGradle(
            """
              class Foo {
                  String name
              }
              def foos = container(Foo) { name -> new Foo(name: name) }
              def bars = project.container(Foo, { name -> new Foo(name: name) })
              """
          )
        );
    }

    @Test
    void doNotChangeTheKotlinReifiedExtension() {
        rewriteRun(
          buildGradleKts(
            """
              abstract class Foo @javax.inject.Inject constructor(val name: String)
              val foos = container<Foo>()
              """
          )
        );
    }

    @Test
    void doNotChangeAMethodOfTheSameNameOnAnotherType() {
        rewriteRun(
          buildGradle(
            """
              docker.container(Foo)
              def container(type) { type }
              container('foo')
              """
          ),
          java(
            """
              class Other {
                  Object container(Class<?> type) {
                      return type;
                  }

                  Object read() {
                      return container(String.class);
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotChangeAScriptClassesOwnMethod() {
        rewriteRun(
          buildGradle(
            """
              class Helper {
                  def container(type) { type }
                  def read() { container(String) }
              }
              """
          )
        );
    }
}
