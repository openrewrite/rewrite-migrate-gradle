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
package org.openrewrite.gradle.migrate.application;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.groovy.GroovyTemplate;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.kotlin.KotlinTemplate;
import org.openrewrite.kotlin.tree.K;

import java.util.List;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseMainClassPropertyForApplication extends Recipe {

    private static final String IN_APPLICATION = "IN_APPLICATION";

    String displayName = "Use `application { mainClass }` instead of `mainClassName`";

    String description = "The `mainClassName` property on the `application` extension was deprecated in Gradle 6.4. Kotlin scripts stop compiling on it in Gradle 8.0 and Groovy scripts fail on it in Gradle 9.0. " +
            "Use `application { mainClass = ... }` instead. Top-level `mainClassName` assignments are wrapped in an `application` block. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/8.14/userguide/upgrading_version_7.html#v7_java_application_api_cleanup) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                if ("application".equals(method.getSimpleName())) {
                    getCursor().putMessage(IN_APPLICATION, true);
                } else if (isMainClassNameCall(method)) {
                    // `mainClassName 'com.Foo'` is the method Gradle generates for the property, and goes the same way
                    Expression mainClass = method.getArguments().get(0).withPrefix(Space.EMPTY);
                    if (getCursor().getNearestMessage(IN_APPLICATION) != null) {
                        return GroovyTemplate.builder("mainClass = #{any()}").build()
                                .apply(getCursor(), method.getCoordinates().replace(), mainClass);
                    }
                    if (getCursor().firstEnclosing(J.Lambda.class) == null && mainClass instanceof J.Literal) {
                        return applicationBlock(method, mainClass);
                    }
                }
                return super.visitMethodInvocation(method, ctx);
            }

            @Override
            public J visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
                if (!isMainClassName(assignment.getVariable())) {
                    return assignment;
                }
                if (getCursor().getNearestMessage(IN_APPLICATION) != null) {
                    Expression variable = assignment.getVariable();
                    if (variable instanceof J.Identifier) {
                        return assignment.withVariable(((J.Identifier) variable).withSimpleName("mainClass"));
                    } else if (variable instanceof J.FieldAccess) {
                        J.FieldAccess fieldAccess = (J.FieldAccess) variable;
                        return assignment.withVariable(
                                fieldAccess.withName(fieldAccess.getName().withSimpleName("mainClass"))
                        );
                    }
                    return assignment;
                }

                if (getCursor().firstEnclosing(J.Lambda.class) == null) {
                    Expression rhs = assignment.getAssignment();
                    if (rhs instanceof J.Literal && ((J.Literal) rhs).getValueSource() != null) {
                        return applicationBlock(assignment, rhs);
                    }
                }
                return assignment;
            }

            @Override
            public J visitVariableDeclarations(J.VariableDeclarations multiVariable, ExecutionContext ctx) {
                if (getCursor().getNearestMessage(IN_APPLICATION) != null ||
                        getCursor().firstEnclosing(J.Lambda.class) != null) {
                    return super.visitVariableDeclarations(multiVariable, ctx);
                }
                List<J.VariableDeclarations.NamedVariable> variables = multiVariable.getVariables();
                if (variables.size() != 1) {
                    return super.visitVariableDeclarations(multiVariable, ctx);
                }
                J.VariableDeclarations.NamedVariable variable = variables.get(0);
                if (!isMainClassName(variable)) {
                    return super.visitVariableDeclarations(multiVariable, ctx);
                }
                Expression initializer = variable.getInitializer();
                if (!(initializer instanceof J.Literal) || ((J.Literal) initializer).getValueSource() == null) {
                    return super.visitVariableDeclarations(multiVariable, ctx);
                }
                return applicationBlock(multiVariable, initializer);
            }

            private boolean isMainClassNameCall(J.MethodInvocation method) {
                return "mainClassName".equals(method.getSimpleName()) && method.getSelect() == null &&
                       method.getArguments().size() == 1 && !(method.getArguments().get(0) instanceof J.Empty) &&
                       !(method.getArguments().get(0) instanceof J.Lambda) &&
                       getCursor().firstEnclosing(JavaSourceFile.class) instanceof G.CompilationUnit;
            }

            // The main class travels as a parameter rather than as text, so a `#{` inside the literal cannot be
            // mistaken for a template placeholder
            private J applicationBlock(Statement original, Expression mainClass) {
                String snippet = "application {\n    mainClass = #{any()}\n}";
                JavaTemplate template = getCursor().firstEnclosing(JavaSourceFile.class) instanceof K.CompilationUnit ?
                        KotlinTemplate.builder(snippet).build() :
                        GroovyTemplate.builder(snippet).build();
                return template.apply(getCursor(), original.getCoordinates().replace(), mainClass);
            }

            private boolean isMainClassName(Tree variable) {
                if (variable instanceof J.Identifier) {
                    return "mainClassName".equals(((J.Identifier) variable).getSimpleName());
                } else if (variable instanceof J.FieldAccess) {
                    return "mainClassName".equals(((J.FieldAccess) variable).getSimpleName());
                } else if (variable instanceof J.VariableDeclarations.NamedVariable) {
                    return "mainClassName".equals(((J.VariableDeclarations.NamedVariable) variable).getSimpleName());
                }
                return false;
            }
        });
    }
}
