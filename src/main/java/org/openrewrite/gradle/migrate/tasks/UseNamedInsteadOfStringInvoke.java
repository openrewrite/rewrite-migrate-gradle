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

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;
import org.openrewrite.kotlin.KotlinTemplate;
import org.openrewrite.kotlin.tree.K;

import java.util.Iterator;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseNamedInsteadOfStringInvoke extends Recipe {

    String displayName = "Use `named(\"x\")` instead of `\"x\"()` in the Kotlin DSL";

    String description = "Looking up a domain object with `\"x\"()` in the Kotlin DSL is deprecated and does not compile on Gradle 9. " +
            "`named(\"x\")` returns the same provider. `\"x\"(Type::class)` and `\"x\" { }` are still supported, so only the form " +
            "with no arguments and no lambda changes. A call inside `withGroovyBuilder { }` is a Groovy-builder " +
            "invocation on the receiver rather than a container lookup, so it is left alone. Groovy is not affected. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/8.14/userguide/upgrading_version_8.html#string_invoke) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
                Expression select = m.getSelect();
                if (isInsideGroovyBuilder() ||
                        getCursor().firstEnclosing(K.CompilationUnit.class) == null ||
                        !"<empty>".equals(m.getSimpleName()) ||
                        !(select instanceof J.Literal) || !(((J.Literal) select).getValue() instanceof String) ||
                        !(m.getArguments().isEmpty() || m.getArguments().get(0) instanceof J.Empty)) {
                    return m;
                }
                return KotlinTemplate.builder("named(#{any()})").build()
                        .apply(getCursor(), m.getCoordinates().replace(), select.withPrefix(Space.EMPTY));
            }

            /**
             * Inside {@code withGroovyBuilder { }} the receiver is a Groovy builder, so {@code "x"()} invokes
             * {@code x} on it rather than looking up a domain object, and {@code named} is not in scope.
             */
            private boolean isInsideGroovyBuilder() {
                Iterator<Object> enclosing = getCursor().getPath(J.MethodInvocation.class::isInstance);
                while (enclosing.hasNext()) {
                    if ("withGroovyBuilder".equals(((J.MethodInvocation) enclosing.next()).getSimpleName())) {
                        return true;
                    }
                }
                return false;
            }
        });
    }
}
