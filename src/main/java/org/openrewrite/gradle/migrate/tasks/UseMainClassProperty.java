/*
 * Copyright 2026 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
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
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.groovy.GroovyTemplate;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.Space;

import java.util.List;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseMainClassProperty extends Recipe {

    private static final String IN_JAVA_EXEC = "IN_JAVA_EXEC";

    String displayName = "Use `mainClass` instead of `main` for `JavaExec` tasks";

    String description = "The `main` property on `JavaExec` tasks was deprecated in Gradle 7.1. Kotlin scripts stop compiling on it in Gradle 8.0 and Groovy scripts fail on it in Gradle 9.0. " +
            "Use the `mainClass` property instead. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/8.14/userguide/upgrading_version_7.html#v7_java_exec_api_cleanup) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                if (hasJavaExecType(method) || isRunTask(method)) {
                    getCursor().putMessage(IN_JAVA_EXEC, true);
                } else if (isMainCall(method) && getCursor().getNearestMessage(IN_JAVA_EXEC) != null) {
                    // `main 'com.Foo'` is the method Gradle generates for the property, and goes the same way.
                    // The class travels as a parameter rather than as text, so a `#{` inside it cannot be mistaken
                    // for a placeholder
                    return GroovyTemplate.builder("mainClass = #{any()}").build()
                            .apply(getCursor(), method.getCoordinates().replace(), method.getArguments().get(0).withPrefix(Space.EMPTY));
                }
                return super.visitMethodInvocation(method, ctx);
            }

            @Override
            public J.Assignment visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
                Expression variable = assignment.getVariable();
                if (variable instanceof J.Identifier) {
                    J.Identifier id = (J.Identifier) variable;
                    if ("main".equals(id.getSimpleName()) && getCursor().getNearestMessage(IN_JAVA_EXEC) != null) {
                        return assignment.withVariable(id.withSimpleName("mainClass"));
                    }
                } else if (variable instanceof J.FieldAccess) {
                    J.FieldAccess fieldAccess = (J.FieldAccess) variable;
                    if ("main".equals(fieldAccess.getSimpleName()) && getCursor().getNearestMessage(IN_JAVA_EXEC) != null) {
                        return assignment.withVariable(
                                fieldAccess.withName(fieldAccess.getName().withSimpleName("mainClass"))
                        );
                    }
                }
                return assignment;
            }

            private boolean isMainCall(J.MethodInvocation method) {
                return "main".equals(method.getSimpleName()) && method.getSelect() == null &&
                       method.getArguments().size() == 1 && !(method.getArguments().get(0) instanceof J.Lambda) &&
                       !(method.getArguments().get(0) instanceof J.Empty) &&
                       getCursor().firstEnclosing(JavaSourceFile.class) instanceof G.CompilationUnit;
            }

            /**
             * The application plugin's `run` task is a `JavaExec`, configured without naming its type. Groovy only:
             * in Kotlin a bare `run { }` is the standard library's scope function.
             */
            private boolean isRunTask(J.MethodInvocation method) {
                if (!(getCursor().firstEnclosing(JavaSourceFile.class) instanceof G.CompilationUnit)) {
                    return false;
                }
                List<Expression> arguments = method.getArguments();
                if ("run".equals(method.getSimpleName()) && arguments.size() == 1 && arguments.get(0) instanceof J.Lambda) {
                    return method.getSelect() == null || isTasks(method.getSelect());
                }
                return "named".equals(method.getSimpleName()) && isTasks(method.getSelect()) && !arguments.isEmpty() &&
                       arguments.get(0) instanceof J.Literal && "run".equals(((J.Literal) arguments.get(0)).getValue());
            }

            private boolean isTasks(@Nullable Expression select) {
                return select instanceof J.Identifier && "tasks".equals(((J.Identifier) select).getSimpleName());
            }

            private boolean hasJavaExecType(J.MethodInvocation method) {
                // Groovy DSL: task foo(type: JavaExec) { ... }
                for (Expression arg : method.getArguments()) {
                    if (arg instanceof G.MapEntry) {
                        G.MapEntry entry = (G.MapEntry) arg;
                        if (isTypeKey(entry.getKey()) && isJavaExecValue(entry.getValue())) {
                            return true;
                        }
                    } else if (isJavaExecValue(arg)) {
                        return true;
                    }
                }
                // Kotlin DSL: tasks.register<JavaExec>("foo") { ... }
                if (method.getTypeParameters() != null) {
                    for (Expression typeParameter : method.getTypeParameters()) {
                        if (isJavaExecValue(typeParameter)) {
                            return true;
                        }
                    }
                }
                return false;
            }

            private boolean isTypeKey(Expression key) {
                return key instanceof G.Literal && "type".equals(((G.Literal) key).getValue());
            }

            private boolean isJavaExecValue(Expression value) {
                return value instanceof J.Identifier && "JavaExec".equals(((J.Identifier) value).getSimpleName());
            }
        });
    }
}
