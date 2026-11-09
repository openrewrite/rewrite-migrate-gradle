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
package org.openrewrite.gradle.migrate.kotlin;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.kotlin.KotlinTemplate;
import org.openrewrite.kotlin.tree.K;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseCompilerOptionsWithKotlinDsl extends Recipe {

    private static final String ASSIGNMENT_TO_REPLACE = "assignmentToReplace";

    String displayName = "Use `compilerOptions` instead of `kotlinOptions` with the `kotlin-dsl` plugin";

    String description = "With the `kotlin-dsl` plugin 6.7.6 that Gradle 9.8 applies, `kotlinOptions { }` does not compile. " +
            "`kotlinOptions { freeCompilerArgs = listOf(\"x\") }` becomes `compilerOptions { freeCompilerArgs.addAll(\"x\") }`, " +
            "which appends to the arguments the plugin needs, as the upgrade guide says. Only a block holding just that assignment, " +
            "with literal arguments, in a script whose own `plugins { }` block applies `kotlin-dsl`, changes. Other options in the " +
            "block have different types under `compilerOptions`, so they are left alone. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/8.14/userguide/upgrading_version_8.html#v8_1_configuring_kotlin_compiler_options_with_kotlin_dsl_plugin) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaVisitor<ExecutionContext>() {
            private boolean kotlinDsl;

            @Override
            public J preVisit(J tree, ExecutionContext ctx) {
                if (tree instanceof K.CompilationUnit) {
                    kotlinDsl = appliesKotlinDsl(tree);
                }
                return tree;
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.Assignment assignment = kotlinDsl && "kotlinOptions".equals(method.getSimpleName()) && method.getSelect() == null ?
                        onlyFreeCompilerArgsAssignment(method) : null;
                if (assignment == null) {
                    return super.visitMethodInvocation(method, ctx);
                }
                getCursor().putMessage(ASSIGNMENT_TO_REPLACE, assignment.getId());
                J.MethodInvocation m = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
                return m.withName(m.getName().withSimpleName("compilerOptions"));
            }

            @Override
            public J visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
                if (!assignment.getId().equals(getCursor().getNearestMessage(ASSIGNMENT_TO_REPLACE))) {
                    return super.visitAssignment(assignment, ctx);
                }
                List<Expression> values = ((J.MethodInvocation) assignment.getAssignment()).getArguments();
                StringJoiner placeholders = new StringJoiner(", ", "freeCompilerArgs.addAll(", ")");
                List<Expression> parameters = new ArrayList<>(values.size());
                for (Expression value : values) {
                    placeholders.add("#{any()}");
                    parameters.add(value.withPrefix(Space.EMPTY));
                }
                return KotlinTemplate.builder(placeholders.toString()).build()
                        .apply(getCursor(), assignment.getCoordinates().replace(), parameters.toArray());
            }
        });
    }

    private static J.@Nullable Assignment onlyFreeCompilerArgsAssignment(J.MethodInvocation kotlinOptions) {
        if (kotlinOptions.getArguments().size() != 1 || !(kotlinOptions.getArguments().get(0) instanceof J.Lambda)) {
            return null;
        }
        J body = ((J.Lambda) kotlinOptions.getArguments().get(0)).getBody();
        if (!(body instanceof J.Block) || ((J.Block) body).getStatements().size() != 1) {
            return null;
        }
        Statement statement = ((J.Block) body).getStatements().get(0);
        J expression = statement instanceof J.Return ? ((J.Return) statement).getExpression() : statement;
        if (!(expression instanceof J.Assignment)) {
            return null;
        }
        J.Assignment assignment = (J.Assignment) expression;
        if (!(assignment.getVariable() instanceof J.Identifier) ||
                !"freeCompilerArgs".equals(((J.Identifier) assignment.getVariable()).getSimpleName()) ||
                !(assignment.getAssignment() instanceof J.MethodInvocation)) {
            return null;
        }
        J.MethodInvocation value = (J.MethodInvocation) assignment.getAssignment();
        if (!"listOf".equals(value.getSimpleName()) || value.getSelect() != null || value.getArguments().isEmpty()) {
            return null;
        }
        for (Expression argument : value.getArguments()) {
            if (!(argument instanceof J.Literal)) {
                return null;
            }
        }
        return assignment;
    }

    private static boolean appliesKotlinDsl(J tree) {
        boolean[] found = {false};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, Integer p) {
                if ("plugins".equals(method.getSimpleName()) && method.getSelect() == null) {
                    new JavaIsoVisitor<Integer>() {
                        @Override
                        public J.Identifier visitIdentifier(J.Identifier identifier, Integer q) {
                            found[0] |= "kotlin-dsl".equals(identifier.getSimpleName().replace("`", ""));
                            return identifier;
                        }

                        @Override
                        public J.Literal visitLiteral(J.Literal literal, Integer q) {
                            found[0] |= "org.gradle.kotlin.kotlin-dsl".equals(literal.getValue());
                            return literal;
                        }
                    }.visit(method, 0);
                }
                return super.visitMethodInvocation(method, p);
            }
        }.visit(tree, 0);
        return found[0];
    }
}
