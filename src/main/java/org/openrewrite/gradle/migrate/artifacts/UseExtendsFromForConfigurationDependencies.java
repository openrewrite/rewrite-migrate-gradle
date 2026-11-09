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

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.GradleParser;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.kotlin.marker.By;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.marker.Markers;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Collections.singletonList;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseExtendsFromForConfigurationDependencies extends Recipe {

    String displayName = "Use `extendsFrom` instead of a `Configuration` as a dependency";

    String description = "Gradle 8.0 no longer allows a `Configuration` in the `dependencies { }` block, failing with " +
            "`Adding a Configuration as a dependency is no longer allowed as of Gradle 8.0`. What such a declaration did was " +
            "make the configuration extend the other one, so this recipe writes that in a `configurations { }` block in front of " +
            "the `dependencies { }` block. It acts on `implementation configurations.foo` in Groovy, and on `implementation(foo)` " +
            "in Kotlin where `foo` is declared by `configurations.creating` or `configurations.create`. A declaration with a " +
            "configuration closure, a configuration of another project, and a name the script also uses for a variable are left alone. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/current/userguide/upgrading_version_7.html#v7_configurations_no_longer_allowed_as_dependencies) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaIsoVisitor<ExecutionContext>() {
            private Scripts.Variables variables = new Scripts.Variables();

            @Override
            public @Nullable J visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof JavaSourceFile) {
                    variables = Scripts.variables((JavaSourceFile) tree);
                }
                J visited = super.visit(tree, ctx);
                if (visited instanceof G.CompilationUnit) {
                    G.CompilationUnit cu = (G.CompilationUnit) visited;
                    return cu.withStatements(moveOut(cu.getStatements(), ctx));
                }
                return visited;
            }

            @Override
            public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block b = super.visitBlock(block, ctx);
                return b.withStatements(moveOut(b.getStatements(), ctx));
            }

            private List<Statement> moveOut(List<Statement> statements, ExecutionContext ctx) {
                List<Statement> result = null;
                for (int i = 0; i < statements.size(); i++) {
                    Statement statement = statements.get(i);
                    J.MethodInvocation dependencies = asDependenciesBlock(statement);
                    Map<String, List<String>> parents = new LinkedHashMap<>();
                    J.Block body = dependencies == null ? null : (J.Block) ((J.Lambda) dependencies.getArguments().get(0)).getBody();
                    List<Statement> remaining = new ArrayList<>();
                    if (body != null) {
                        for (Statement declaration : body.getStatements()) {
                            if (!recordIfConfigurationDependency(declaration, parents)) {
                                remaining.add(declaration);
                            }
                        }
                    }
                    if (parents.isEmpty()) {
                        if (result != null) {
                            result.add(statement);
                        }
                        continue;
                    }
                    if (result == null) {
                        result = new ArrayList<>(statements.subList(0, i));
                    }
                    Space prefix = statement.getPrefix();
                    Statement configurations = configurationsBlock(parents, prefix, ctx);
                    if (remaining.isEmpty()) {
                        result.add(statement instanceof J.Return ?
                                ((J.Return) statement).withExpression(((Expression) configurations).withPrefix(Space.EMPTY)) :
                                configurations);
                    } else {
                        result.add(configurations);
                        J.MethodInvocation shortened = dependencies.withArguments(singletonList(
                                ((J.Lambda) dependencies.getArguments().get(0)).withBody(body.withStatements(remaining))));
                        String indent = indent(prefix);
                        Space separated = Space.format("\n\n" + indent);
                        result.add(statement instanceof J.Return ?
                                ((J.Return) statement).withExpression(shortened).withPrefix(separated) :
                                shortened.withPrefix(separated));
                    }
                }
                return result == null ? statements : result;
            }

            private J.@Nullable MethodInvocation asDependenciesBlock(Statement statement) {
                Expression expression = unwrap(statement);
                if (!(expression instanceof J.MethodInvocation)) {
                    return null;
                }
                J.MethodInvocation m = (J.MethodInvocation) expression;
                if (!"dependencies".equals(m.getSimpleName()) || m.getSelect() != null || m.getArguments().size() != 1 ||
                    !(m.getArguments().get(0) instanceof J.Lambda) ||
                    !(((J.Lambda) m.getArguments().get(0)).getBody() instanceof J.Block)) {
                    return null;
                }
                J.MethodInvocation enclosing = getCursor().firstEnclosing(J.MethodInvocation.class);
                return enclosing != null && "buildscript".equals(enclosing.getSimpleName()) ? null : m;
            }

            private boolean recordIfConfigurationDependency(Statement declaration, Map<String, List<String>> parents) {
                Expression expression = unwrap(declaration);
                if (!(expression instanceof J.MethodInvocation)) {
                    return false;
                }
                J.MethodInvocation m = (J.MethodInvocation) expression;
                if (m.getSelect() != null || m.getArguments().size() != 1) {
                    return false;
                }
                String parent = configurationArgument(m.getArguments().get(0));
                String child = m.getSimpleName();
                if (parent == null || parent.equals(child) || variables.names.contains(child)) {
                    return false;
                }
                parents.computeIfAbsent(child, k -> new ArrayList<>()).add(parent);
                return true;
            }

            private @Nullable String configurationArgument(Expression argument) {
                if (isKotlin()) {
                    return argument instanceof J.Identifier && variables.kotlinConfigurations.contains(((J.Identifier) argument).getSimpleName()) ?
                            ((J.Identifier) argument).getSimpleName() : null;
                }
                if (argument instanceof J.FieldAccess) {
                    J.FieldAccess fieldAccess = (J.FieldAccess) argument;
                    return fieldAccess.getTarget() instanceof J.Identifier &&
                           "configurations".equals(((J.Identifier) fieldAccess.getTarget()).getSimpleName()) &&
                           !variables.names.contains("configurations") && !variables.names.contains(fieldAccess.getSimpleName()) ?
                            fieldAccess.getSimpleName() : null;
                }
                return null;
            }

            private boolean isKotlin() {
                return getCursor().firstEnclosing(JavaSourceFile.class) instanceof K.CompilationUnit;
            }

            private Statement configurationsBlock(Map<String, List<String>> parents, Space prefix, ExecutionContext ctx) {
                boolean kotlin = isKotlin();
                String indent = indent(prefix);
                StringBuilder snippet = new StringBuilder("configurations {\n");
                for (Map.Entry<String, List<String>> entry : parents.entrySet()) {
                    String arguments = String.join(", ", entry.getValue());
                    if (kotlin) {
                        snippet.append(indent).append("    ").append(entry.getKey()).append(" {\n")
                                .append(indent).append("        extendsFrom(").append(arguments).append(")\n")
                                .append(indent).append("    }\n");
                    } else {
                        snippet.append(indent).append("    ").append(entry.getKey()).append(".extendsFrom(").append(arguments).append(")\n");
                    }
                }
                snippet.append(indent).append("}\n");
                SourceFile parsed = GradleParser.builder().build()
                        .parseInputs(singletonList(Parser.Input.fromString(Paths.get(kotlin ? "build.gradle.kts" : "build.gradle"), snippet.toString())), null, ctx)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Unable to parse `configurations { }` block"));
                List<Statement> statements = kotlin ? ((K.CompilationUnit) parsed).getStatements() : ((G.CompilationUnit) parsed).getStatements();
                return statements.get(0).withPrefix(prefix);
            }
        });
    }

    private static Expression unwrap(Statement statement) {
        if (statement instanceof J.Return) {
            Expression expression = ((J.Return) statement).getExpression();
            return expression == null ? new J.Empty(Tree.randomId(), Space.EMPTY, Markers.EMPTY) : expression;
        }
        return statement instanceof Expression ? (Expression) statement : new J.Empty(Tree.randomId(), Space.EMPTY, Markers.EMPTY);
    }

    private static String indent(Space prefix) {
        String whitespace = prefix.getLastWhitespace();
        return whitespace.substring(whitespace.lastIndexOf('\n') + 1);
    }

    private static class Scripts {
        static class Variables {
            final Set<String> names = new HashSet<>();
            final Set<String> kotlinConfigurations = new HashSet<>();
        }

        static Variables variables(JavaSourceFile sourceFile) {
            Variables variables = new Variables();
            Set<String> repeated = new HashSet<>();
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable, Integer p) {
                    for (J.VariableDeclarations.NamedVariable variable : multiVariable.getVariables()) {
                        if (!variables.names.add(variable.getSimpleName())) {
                            repeated.add(variable.getSimpleName());
                        }
                        if (createsConfiguration(multiVariable, variable.getInitializer())) {
                            variables.kotlinConfigurations.add(variable.getSimpleName());
                        }
                    }
                    return super.visitVariableDeclarations(multiVariable, p);
                }
            }.visit(sourceFile, 0);
            variables.kotlinConfigurations.removeAll(repeated);
            return variables;
        }

        private static boolean createsConfiguration(J.VariableDeclarations declarations, @Nullable Expression initializer) {
            boolean by = declarations.getMarkers().findFirst(By.class).isPresent();
            if (initializer instanceof J.FieldAccess) {
                J.FieldAccess fieldAccess = (J.FieldAccess) initializer;
                return by && "creating".equals(fieldAccess.getSimpleName()) && isConfigurations(fieldAccess.getTarget());
            }
            if (initializer instanceof J.MethodInvocation) {
                J.MethodInvocation m = (J.MethodInvocation) initializer;
                return isConfigurations(m.getSelect()) &&
                       (by ? "creating".equals(m.getSimpleName()) : "create".equals(m.getSimpleName()));
            }
            return false;
        }

        private static boolean isConfigurations(@Nullable Expression expression) {
            return expression instanceof J.Identifier && "configurations".equals(((J.Identifier) expression).getSimpleName());
        }
    }
}
