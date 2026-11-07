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
package org.openrewrite.gradle.migrate.base;

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
import org.openrewrite.java.tree.Statement;
import org.openrewrite.kotlin.KotlinTemplate;
import org.openrewrite.kotlin.tree.K;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseBaseExtensionBlock extends Recipe {

    private static final String ARCHIVES_BASE_NAME = "archivesBaseName";
    private static final Set<String> PROPERTIES = new HashSet<>(Arrays.asList(ARCHIVES_BASE_NAME, "libsDirName", "distsDirName"));
    private static final Set<String> PROJECT_BLOCKS = new HashSet<>(Arrays.asList("subprojects", "allprojects", "project"));

    String displayName = "Move `archivesBaseName`, `libsDirName` and `distsDirName` into the `base { }` extension block";

    String description = "Gradle 9 removed the `BasePluginConvention` (deprecated in 8.2), so setting `archivesBaseName`, " +
            "`libsDirName` or `distsDirName` on the project fails. Each becomes a `Property` of the `base { }` block: " +
            "`archivesName`, and `libsDirectory` and `distsDirectory` resolved against `layout.buildDirectory`. " +
            "Reads of these properties, and assignments inside task configuration, are left for review. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/8.14/userguide/upgrading_version_8.html#base_convention_deprecation) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaVisitor<ExecutionContext>() {
            private Set<String> shadowed = new HashSet<>();
            private boolean kotlinAccessors;

            @Override
            public J preVisit(J tree, ExecutionContext ctx) {
                if (tree instanceof JavaSourceFile) {
                    shadowed = findShadowedProperties(tree);
                    kotlinAccessors = tree instanceof K.CompilationUnit && declaresPluginsBlock(tree);
                }
                return tree;
            }

            @Override
            public J visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
                String property = propertyAssigned(assignment.getVariable());
                if (property == null) {
                    return super.visitAssignment(assignment, ctx);
                }
                J replaced = replace(assignment, property, assignment.getAssignment());
                return replaced == null ? super.visitAssignment(assignment, ctx) : replaced;
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                List<Expression> arguments = method.getArguments();
                String property = null;
                Expression value = null;
                if (isProjectReceiver(method.getSelect())) {
                    if ("setProperty".equals(method.getSimpleName()) && arguments.size() == 2 &&
                        arguments.get(0) instanceof J.Literal && PROPERTIES.contains(String.valueOf(((J.Literal) arguments.get(0)).getValue()))) {
                        // `setProperty("archivesBaseName", "x")` is how Kotlin scripts reached the convention
                        property = String.valueOf(((J.Literal) arguments.get(0)).getValue());
                        value = arguments.get(1);
                    } else if (PROPERTIES.contains(method.getSimpleName()) && arguments.size() == 1 &&
                               !(arguments.get(0) instanceof J.Empty) && !(arguments.get(0) instanceof J.Lambda)) {
                        // `archivesBaseName 'x'`, which UseAssignmentForPropertySyntax otherwise turns into an assignment
                        property = method.getSimpleName();
                        value = arguments.get(0);
                    }
                }
                if (property != null) {
                    J replaced = replace(method, property, value);
                    if (replaced != null) {
                        return replaced;
                    }
                }
                return super.visitMethodInvocation(method, ctx);
            }

            private @Nullable String propertyAssigned(Expression variable) {
                if (variable instanceof J.Identifier) {
                    String name = ((J.Identifier) variable).getSimpleName();
                    return PROPERTIES.contains(name) ? name : null;
                }
                if (variable instanceof J.FieldAccess) {
                    J.FieldAccess field = (J.FieldAccess) variable;
                    return PROPERTIES.contains(field.getSimpleName()) && isProjectReceiver(field.getTarget()) ?
                            field.getSimpleName() : null;
                }
                return null;
            }

            private @Nullable J replace(Statement statement, String property, @Nullable Expression value) {
                if (value == null || shadowed.contains(property)) {
                    return null;
                }
                Scope scope = scope();
                if (scope == Scope.OTHER) {
                    return null;
                }
                boolean kotlin = getCursor().firstEnclosing(JavaSourceFile.class) instanceof K.CompilationUnit;
                String body = propertySnippet(property, kotlin);
                String snippet;
                if (scope == Scope.BASE) {
                    snippet = body;
                } else if (kotlin && (scope == Scope.PROJECT || !kotlinAccessors)) {
                    // Type-safe accessors exist only for plugins a `plugins { }` block applies to this very script
                    snippet = "configure<BasePluginExtension> {\n    " + body + "\n}";
                } else {
                    snippet = "base {\n    " + body + "\n}";
                }
                JavaTemplate template = kotlin ? KotlinTemplate.builder(snippet).build() : GroovyTemplate.builder(snippet).build();
                // The value travels as a parameter rather than as text, so a `#{` inside it cannot be mistaken for a placeholder
                Expression parameter = value.withPrefix(Space.EMPTY);
                J applied = template.apply(getCursor(), statement.getCoordinates().replace(), parameter);
                // Inside `base { }` already the assignment stays where it is; otherwise a block goes round it
                return MovedExpressions.reindent(applied, parameter, getCursor(), scope == Scope.BASE ? 0 : 1);
            }

            private Scope scope() {
                Cursor lambda = getCursor().getParent() == null ? null : getCursor().getParent().dropParentUntil(
                        v -> v instanceof J.Lambda || v instanceof J.ClassDeclaration || v instanceof J.MethodDeclaration ||
                             v == Cursor.ROOT_VALUE);
                if (lambda == null || lambda.getValue() == Cursor.ROOT_VALUE) {
                    return Scope.SCRIPT;
                }
                if (!(lambda.getValue() instanceof J.Lambda)) {
                    return Scope.OTHER;
                }
                J.MethodInvocation owner = lambda.firstEnclosing(J.MethodInvocation.class);
                if (owner == null) {
                    return Scope.OTHER;
                }
                if ("base".equals(owner.getSimpleName())) {
                    return Scope.BASE;
                }
                return PROJECT_BLOCKS.contains(owner.getSimpleName()) ? Scope.PROJECT : Scope.OTHER;
            }
        });
    }

    private enum Scope {
        SCRIPT, PROJECT, BASE, OTHER
    }

    private static String propertySnippet(String property, boolean kotlin) {
        if (ARCHIVES_BASE_NAME.equals(property)) {
            return kotlin ? "archivesName.set(#{any()})" : "archivesName = #{any()}";
        }
        String directory = "libsDirName".equals(property) ? "libsDirectory" : "distsDirectory";
        return kotlin ? directory + ".set(layout.buildDirectory.dir(#{any()}))" :
                directory + " = layout.buildDirectory.dir(#{any()})";
    }

    private static boolean isProjectReceiver(@Nullable Expression select) {
        return select == null || select instanceof J.Identifier &&
                                 ("project".equals(((J.Identifier) select).getSimpleName()) ||
                                  "base".equals(((J.Identifier) select).getSimpleName()));
    }

    private static boolean declaresPluginsBlock(J tree) {
        boolean[] found = {false};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, Integer p) {
                found[0] |= "plugins".equals(method.getSimpleName()) && method.getSelect() == null;
                return method;
            }
        }.visit(tree, 0);
        return found[0];
    }

    /**
     * A script's own variable, or an extra property, of the same name was never the convention's property.
     */
    private static Set<String> findShadowedProperties(J tree) {
        Set<String> shadowed = new HashSet<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable variable, Integer p) {
                if (PROPERTIES.contains(variable.getSimpleName())) {
                    shadowed.add(variable.getSimpleName());
                }
                return super.visitVariable(variable, p);
            }

            @Override
            public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, Integer p) {
                if (PROPERTIES.contains(fieldAccess.getSimpleName()) && fieldAccess.getTarget() instanceof J.Identifier &&
                    "ext".equals(((J.Identifier) fieldAccess.getTarget()).getSimpleName())) {
                    shadowed.add(fieldAccess.getSimpleName());
                }
                return super.visitFieldAccess(fieldAccess, p);
            }

            @Override
            public J.Assignment visitAssignment(J.Assignment assignment, Integer p) {
                J.MethodInvocation owner = getCursor().firstEnclosing(J.MethodInvocation.class);
                if (owner != null && "ext".equals(owner.getSimpleName()) && assignment.getVariable() instanceof J.Identifier &&
                    PROPERTIES.contains(((J.Identifier) assignment.getVariable()).getSimpleName())) {
                    shadowed.add(((J.Identifier) assignment.getVariable()).getSimpleName());
                }
                return super.visitAssignment(assignment, p);
            }
        }.visit(tree, 0);
        return shadowed;
    }
}
