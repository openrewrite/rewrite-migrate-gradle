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
package org.openrewrite.gradle.migrate.application;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.gradle.migrate.internal.MovedExpressions;
import org.openrewrite.groovy.GroovyTemplate;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.Space;
import org.openrewrite.kotlin.KotlinTemplate;
import org.openrewrite.kotlin.tree.K;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static java.util.Collections.emptySet;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseApplicationBlockForConventionProperties extends Recipe {

    /**
     * The writable properties of the removed {@code ApplicationPluginConvention}. {@code mainClassName} is
     * {@link UseMainClassPropertyForApplication}'s, and {@code applicationDistribution} is read-only.
     */
    private static final Set<String> PROPERTIES = new HashSet<>(Arrays.asList(
            "applicationName", "applicationDefaultJvmArgs", "executableDir"));
    private static final Set<String> PROJECT_BLOCKS = new HashSet<>(Arrays.asList(
            "subprojects", "allprojects", "project", "afterEvaluate", "withId", "withPlugin"));

    String displayName = "Move the `application` convention properties into the `application { }` block";

    String description = "`ApplicationPluginConvention` is deprecated since Gradle 8.2 and removed in Gradle 9, so assigning " +
            "`applicationName`, `applicationDefaultJvmArgs` or `executableDir` on the project warns on Gradle 8.14 in Groovy, " +
            "fails on Gradle 9 in Groovy with `Could not set unknown property`, and does not " +
            "compile in Kotlin on Gradle 8.14 or 9. The assignment moves into the `application { }` block. Kotlin uses " +
            "`configure<JavaApplication> { }` where the script's own `plugins { }` block does not apply `application`, because " +
            "only then does the type-safe accessor exist. `+=`, reads, and a script's own `def`, `val` or `ext` of the same name " +
            "are left alone. See the [Gradle upgrade guide](https://docs.gradle.org/8.14/userguide/upgrading_version_8.html#application_convention_deprecation) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaVisitor<ExecutionContext>() {
            private Set<String> shadowed = emptySet();
            private boolean kotlinAccessor;

            @Override
            public J preVisit(J tree, ExecutionContext ctx) {
                if (tree instanceof JavaSourceFile) {
                    shadowed = shadowedProperties(tree);
                    kotlinAccessor = tree instanceof K.CompilationUnit && appliesApplicationPlugin(tree);
                }
                return tree;
            }

            @Override
            public J visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
                String property = assignedProperty(assignment.getVariable());
                if (property == null || shadowed.contains(property)) {
                    return super.visitAssignment(assignment, ctx);
                }
                Scope scope = scope();
                if (scope == Scope.OTHER) {
                    return super.visitAssignment(assignment, ctx);
                }
                boolean kotlin = getCursor().firstEnclosing(JavaSourceFile.class) instanceof K.CompilationUnit;
                String block = kotlin && (scope == Scope.PROJECT || !kotlinAccessor) ? "configure<JavaApplication>" : "application";
                String snippet = block + " {\n    " + property + " = #{any()}\n}";
                JavaTemplate template = kotlin ? KotlinTemplate.builder(snippet).build() : GroovyTemplate.builder(snippet).build();
                // The value travels as a parameter rather than as text, so a `#{` inside it cannot be mistaken for a placeholder
                Expression value = assignment.getAssignment().withPrefix(Space.EMPTY);
                J applied = template.apply(getCursor(), assignment.getCoordinates().replace(), value);
                return MovedExpressions.reindent(applied, value, getCursor(), 1);
            }

            private @Nullable String assignedProperty(Expression variable) {
                if (variable instanceof J.Identifier) {
                    String name = ((J.Identifier) variable).getSimpleName();
                    return PROPERTIES.contains(name) ? name : null;
                }
                if (variable instanceof J.FieldAccess) {
                    J.FieldAccess field = (J.FieldAccess) variable;
                    if (PROPERTIES.contains(field.getSimpleName()) && field.getTarget() instanceof J.Identifier &&
                        "project".equals(((J.Identifier) field.getTarget()).getSimpleName())) {
                        return field.getSimpleName();
                    }
                }
                return null;
            }

            private Scope scope() {
                Cursor enclosing = getCursor().getParent() == null ? null : getCursor().getParent().dropParentUntil(
                        v -> v instanceof J.Lambda || v instanceof J.ClassDeclaration || v instanceof J.MethodDeclaration ||
                                v == Cursor.ROOT_VALUE);
                if (enclosing == null || enclosing.getValue() == Cursor.ROOT_VALUE) {
                    return Scope.SCRIPT;
                }
                if (!(enclosing.getValue() instanceof J.Lambda)) {
                    return Scope.OTHER;
                }
                J.MethodInvocation owner = enclosing.firstEnclosing(J.MethodInvocation.class);
                return owner != null && PROJECT_BLOCKS.contains(owner.getSimpleName()) ? Scope.PROJECT : Scope.OTHER;
            }
        });
    }

    private enum Scope {
        SCRIPT, PROJECT, OTHER
    }

    /**
     * Whether the script's own {@code plugins { }} block applies {@code application}, the only case in which Kotlin
     * generates the type-safe {@code application { }} accessor.
     */
    private static boolean appliesApplicationPlugin(J tree) {
        boolean[] found = {false};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, Integer p) {
                if ("plugins".equals(method.getSimpleName()) && method.getSelect() == null) {
                    found[0] |= new JavaIsoVisitor<boolean[]>() {
                        @Override
                        public J.Identifier visitIdentifier(J.Identifier identifier, boolean[] f) {
                            f[0] |= "application".equals(identifier.getSimpleName());
                            return identifier;
                        }

                        @Override
                        public J.Literal visitLiteral(J.Literal literal, boolean[] f) {
                            f[0] |= "application".equals(literal.getValue());
                            return literal;
                        }
                    }.reduce(method, new boolean[1])[0];
                }
                return super.visitMethodInvocation(method, p);
            }
        }.visit(tree, 0);
        return found[0];
    }

    /**
     * A script's own variable, or an extra property, of the same name was never the convention's property. One
     * shadowed name says nothing about the others, so each is tracked on its own.
     */
    private static Set<String> shadowedProperties(J tree) {
        Set<String> shadowed = new HashSet<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable variable, Integer p) {
                record(variable.getSimpleName());
                return super.visitVariable(variable, p);
            }

            @Override
            public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, Integer p) {
                if (isExt(fieldAccess.getTarget())) {
                    record(fieldAccess.getSimpleName());
                }
                return super.visitFieldAccess(fieldAccess, p);
            }

            @Override
            public J.Assignment visitAssignment(J.Assignment assignment, Integer p) {
                J.MethodInvocation owner = getCursor().firstEnclosing(J.MethodInvocation.class);
                if (owner != null && "ext".equals(owner.getSimpleName()) && assignment.getVariable() instanceof J.Identifier) {
                    record(((J.Identifier) assignment.getVariable()).getSimpleName());
                }
                return super.visitAssignment(assignment, p);
            }

            private void record(String name) {
                if (PROPERTIES.contains(name)) {
                    shadowed.add(name);
                }
            }

            private boolean isExt(@Nullable Expression target) {
                return target instanceof J.Identifier && "ext".equals(((J.Identifier) target).getSimpleName());
            }
        }.visit(tree, 0);
        return shadowed;
    }
}
