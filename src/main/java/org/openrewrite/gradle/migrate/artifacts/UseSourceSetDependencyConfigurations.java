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
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.kotlin.marker.By;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseSourceSetDependencyConfigurations extends Recipe {

    private static final String COMPILE = "Compile";
    private static final String RUNTIME = "Runtime";
    private static final Set<String> CREATING_METHODS = new HashSet<>(Arrays.asList("create", "register", "maybeCreate"));
    private static final Set<String> CREATING_DELEGATES = new HashSet<>(Arrays.asList("creating", "registering"));

    String displayName = "Use `<sourceSet>Implementation` and `<sourceSet>RuntimeOnly` for custom source sets";

    String description = "Gradle 7.0 removed the `<sourceSet>Compile` and `<sourceSet>Runtime` configurations, so declaring a " +
            "dependency on one fails, or in a Kotlin script's string form reports that the configuration was not found. " +
            "This recipe renames them to `<sourceSet>Implementation` and `<sourceSet>RuntimeOnly`, in dependency " +
            "declarations and as the configurations of `extendsFrom` inside a `configurations { }` block. " +
            "It acts only where the prefix is proven to be a source set: an entry of a `sourceSets { }` block or a test suite " +
            "in this script, or the configurations of the `GradleProject` model. A configuration this script declares itself, " +
            "such as `fooCompile` in `configurations { }`, is left alone, and so are `main` and `test`, which " +
            "`ChangeDependencyConfiguration` covers. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/current/userguide/upgrading_version_6.html) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaIsoVisitor<ExecutionContext>() {
            private Names names = new Names();
            private @Nullable GradleProject project;

            @Override
            public @Nullable J visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof JavaSourceFile) {
                    names = Names.of((JavaSourceFile) tree);
                    project = ((SourceFile) tree).getMarkers().findFirst(GradleProject.class).orElse(null);
                }
                return super.visit(tree, ctx);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                if ("extendsFrom".equals(m.getSimpleName())) {
                    return renameExtendsFrom(m, ctx);
                }
                if (isAddOnDependencies(m)) {
                    return renameFirstLiteral(m, ctx);
                }
                if (!isDirectlyInsideDependencies()) {
                    return m;
                }
                if ("add".equals(m.getSimpleName())) {
                    return renameFirstLiteral(m, ctx);
                }
                if (m.getSelect() instanceof J.Literal) {
                    // `"intTestCompile"("a:b:1")`, the only way a Kotlin script can name a configuration without an accessor
                    J.Literal renamed = renameLiteral((J.Literal) m.getSelect(), ctx);
                    return renamed == m.getSelect() ? m : m.withSelect(renamed);
                }
                if (m.getSelect() != null) {
                    return m;
                }
                String replacement = replacement(m.getSimpleName());
                if (replacement == null) {
                    return m;
                }
                markDirty(ctx);
                return m.withName(m.getName().withSimpleName(replacement));
            }

            private boolean isAddOnDependencies(J.MethodInvocation m) {
                if (!"add".equals(m.getSimpleName()) || m.getSelect() == null) {
                    return false;
                }
                Expression select = m.getSelect();
                String name = select instanceof J.Identifier ? ((J.Identifier) select).getSimpleName() :
                        select instanceof J.FieldAccess ? ((J.FieldAccess) select).getSimpleName() : null;
                return "dependencies".equals(name);
            }

            private boolean isDirectlyInsideDependencies() {
                J.MethodInvocation enclosing = enclosingMethod();
                return enclosing != null && ("dependencies".equals(enclosing.getSimpleName()) || "constraints".equals(enclosing.getSimpleName()));
            }

            private J.MethodInvocation renameFirstLiteral(J.MethodInvocation m, ExecutionContext ctx) {
                return m.withArguments(ListUtils.mapFirst(m.getArguments(), argument ->
                        argument instanceof J.Literal ? renameLiteral((J.Literal) argument, ctx) : argument));
            }

            private J.Literal renameLiteral(J.Literal literal, ExecutionContext ctx) {
                if (!(literal.getValue() instanceof String) || literal.getValueSource() == null) {
                    return literal;
                }
                String name = (String) literal.getValue();
                String replacement = replacement(name);
                if (replacement == null) {
                    return literal;
                }
                markDirty(ctx);
                return literal.withValue(replacement).withValueSource(literal.getValueSource().replace(name, replacement));
            }

            private J.MethodInvocation renameExtendsFrom(J.MethodInvocation m, ExecutionContext ctx) {
                String receiver = configurationReference(m.getSelect());
                String replacement = receiver == null ? null : replacement(receiver);
                if (replacement == null || !isConfigurationsReference(m)) {
                    return m;
                }
                markDirty(ctx);
                return m.withSelect(renameReference(m.getSelect(), replacement))
                        .withArguments(ListUtils.map(m.getArguments(), argument -> {
                            String parent = configurationReference(argument);
                            String parentReplacement = parent == null ? null : parentReplacement(parent);
                            return parentReplacement == null ? argument : renameReference(argument, parentReplacement);
                        }));
            }

            /**
             * A bare `x.extendsFrom(..)` names a configuration only directly inside `configurations { }`, while
             * `configurations.x.extendsFrom(..)` says so itself.
             */
            private boolean isConfigurationsReference(J.MethodInvocation m) {
                if (m.getSelect() instanceof J.FieldAccess) {
                    return true;
                }
                J.MethodInvocation enclosing = enclosingMethod();
                return enclosing != null && "configurations".equals(enclosing.getSimpleName());
            }

            private @Nullable String parentReplacement(String name) {
                if ("testCompile".equals(name)) {
                    return "testImplementation";
                }
                return "testRuntime".equals(name) ? "testRuntimeOnly" : replacement(name);
            }

            private @Nullable String replacement(String configuration) {
                String suffix = configuration.endsWith(COMPILE) ? COMPILE : configuration.endsWith(RUNTIME) ? RUNTIME : null;
                if (suffix == null || configuration.equals(suffix) || names.declaredConfigurations.contains(configuration)) {
                    return null;
                }
                String prefix = configuration.substring(0, configuration.length() - suffix.length());
                if ("main".equals(prefix) || "test".equals(prefix)) {
                    return null;
                }
                String newSuffix = COMPILE.equals(suffix) ? "Implementation" : "RuntimeOnly";
                if (names.sourceSets.contains(prefix) || isSourceSetInModel(prefix, suffix, newSuffix)) {
                    return prefix + newSuffix;
                }
                return null;
            }

            /**
             * The Java plugin gives a source set both the declarable configuration and its classpath, which a
             * configuration that merely happens to be named `fooCompile` doesn't have.
             */
            private boolean isSourceSetInModel(String prefix, String suffix, String newSuffix) {
                return project != null && project.getConfiguration(prefix + newSuffix) != null &&
                        project.getConfiguration(prefix + suffix + "Classpath") != null;
            }

            private J.@Nullable MethodInvocation enclosingMethod() {
                return getCursor().getParentOrThrow().firstEnclosing(J.MethodInvocation.class);
            }

            private void markDirty(ExecutionContext ctx) {
                JavaSourceSet.markDirty(ctx, getCursor().firstEnclosing(SourceFile.class));
            }
        });
    }

    private static @Nullable String configurationReference(@Nullable Expression expression) {
        if (expression instanceof J.Identifier) {
            return ((J.Identifier) expression).getSimpleName();
        }
        if (expression instanceof J.FieldAccess) {
            J.FieldAccess fieldAccess = (J.FieldAccess) expression;
            return isIdentifier(fieldAccess.getTarget(), "configurations") ? fieldAccess.getSimpleName() : null;
        }
        return null;
    }

    private static Expression renameReference(@Nullable Expression expression, String name) {
        if (expression instanceof J.FieldAccess) {
            J.FieldAccess fieldAccess = (J.FieldAccess) expression;
            return fieldAccess.withName(fieldAccess.getName().withSimpleName(name));
        }
        return ((J.Identifier) expression).withSimpleName(name);
    }

    private static boolean isIdentifier(@Nullable Expression expression, String name) {
        return expression instanceof J.Identifier && name.equals(((J.Identifier) expression).getSimpleName());
    }

    private static @Nullable String literalName(J.MethodInvocation m) {
        if (!m.getArguments().isEmpty() && m.getArguments().get(0) instanceof J.Literal) {
            Object value = ((J.Literal) m.getArguments().get(0)).getValue();
            return value instanceof String ? (String) value : null;
        }
        return null;
    }

    /**
     * What the script itself says its source sets and configurations are, gathered before anything is renamed.
     */
    private static class Names {
        final Set<String> sourceSets = new HashSet<>();
        final Set<String> declaredConfigurations = new HashSet<>();

        static Names of(JavaSourceFile sourceFile) {
            Names names = new Names();
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.Identifier visitIdentifier(J.Identifier identifier, Integer p) {
                    // `sourceSets { intTest }` and `configurations { fooCompile }` create the element by naming it
                    if (getCursor().getParentTreeCursor().getValue() instanceof G.ExpressionStatement) {
                        record(identifier.getSimpleName());
                    }
                    return identifier;
                }

                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, Integer p) {
                    String name = method.getSimpleName();
                    Expression select = method.getSelect();
                    if (CREATING_METHODS.contains(name)) {
                        String created = literalName(method);
                        if (created != null) {
                            if (isIdentifier(select, "sourceSets")) {
                                names.sourceSets.add(created);
                            } else if (isIdentifier(select, "configurations")) {
                                names.declaredConfigurations.add(created);
                            } else if (select == null) {
                                record(created);
                            }
                        }
                    } else if (select == null && !method.getArguments().isEmpty()) {
                        Expression first = method.getArguments().get(0);
                        if (first instanceof J.Lambda) {
                            // `sourceSets { intTest { } }` and `configurations { fooCompile { } }`
                            record(name);
                        } else if (isIdentifier(first, "JvmTestSuite") && isDirectlyInside("suites")) {
                            // `testing { suites { intTest(JvmTestSuite) } }`
                            names.sourceSets.add(name);
                        }
                    }
                    return super.visitMethodInvocation(method, p);
                }

                @Override
                public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable variable, Integer p) {
                    J.VariableDeclarations declarations = getCursor().firstEnclosing(J.VariableDeclarations.class);
                    Expression initializer = variable.getInitializer();
                    if (declarations != null && declarations.getMarkers().findFirst(By.class).isPresent() &&
                            initializer != null && isSourceSetDelegate(initializer)) {
                        names.sourceSets.add(variable.getSimpleName());
                    } else {
                        // Whatever else a script's own `val fooCompile by configurations` or `def fooCompile` is,
                        // renaming a call to it would leave the variable behind.
                        names.declaredConfigurations.add(variable.getSimpleName());
                    }
                    return super.visitVariable(variable, p);
                }

                private boolean isSourceSetDelegate(Expression initializer) {
                    if (initializer instanceof J.FieldAccess) {
                        J.FieldAccess fieldAccess = (J.FieldAccess) initializer;
                        return CREATING_DELEGATES.contains(fieldAccess.getSimpleName()) &&
                                isIdentifier(fieldAccess.getTarget(), "sourceSets");
                    }
                    if (initializer instanceof J.MethodInvocation) {
                        J.MethodInvocation m = (J.MethodInvocation) initializer;
                        return CREATING_DELEGATES.contains(m.getSimpleName()) &&
                                (isIdentifier(m.getSelect(), "sourceSets") || isDirectlyInside("suites") || isDirectlyInside("sourceSets"));
                    }
                    return initializer instanceof J.Identifier &&
                            CREATING_DELEGATES.contains(((J.Identifier) initializer).getSimpleName()) &&
                            (isDirectlyInside("sourceSets") || isDirectlyInside("suites"));
                }

                private void record(String name) {
                    if (isDirectlyInside("sourceSets") || isDirectlyInside("suites")) {
                        names.sourceSets.add(name);
                    } else if (isDirectlyInside("configurations")) {
                        names.declaredConfigurations.add(name);
                    }
                }

                private boolean isDirectlyInside(String block) {
                    J.MethodInvocation enclosing = getCursor().getParentOrThrow().firstEnclosing(J.MethodInvocation.class);
                    return enclosing != null && block.equals(enclosing.getSimpleName());
                }
            }.visit(sourceFile, 0);
            return names;
        }
    }
}
