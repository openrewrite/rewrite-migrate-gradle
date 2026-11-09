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

@Value
@EqualsAndHashCode(callSuper = false)
public class UseTaskPropertiesForPluginConventions extends Recipe {

    private static final String WEB_APP_DIR_NAME = "webAppDirName";
    private static final String APP_DIR_NAME = "appDirName";
    private static final String LIB_DIR_NAME = "libDirName";
    private static final String PROJECT_REPORT_DIR_NAME = "projectReportDirName";
    private static final Set<String> PROPERTIES = new HashSet<>(Arrays.asList(WEB_APP_DIR_NAME, APP_DIR_NAME, LIB_DIR_NAME, PROJECT_REPORT_DIR_NAME));
    private static final Set<String> PROJECT_BLOCKS = new HashSet<>(Arrays.asList(
            "subprojects", "allprojects", "project", "afterEvaluate", "withId", "withPlugin"));

    String displayName = "Configure the `war`, `ear` and project report tasks instead of their plugin conventions";

    String description = "`WarPluginConvention`, `EarPluginConvention` and `ProjectReportsPluginConvention` are deprecated since " +
            "Gradle 8.2 and removed in Gradle 9, so assigning `webAppDirName`, `appDirName`, `libDirName` or `projectReportDirName` on the project " +
            "warns on Gradle 8.14 in Groovy, fails on Gradle 9 in Groovy, and does not compile in Kotlin on Gradle 8.14 or 9. " +
            "Each assignment becomes configuration of the task, as the upgrade guide says. A script that declares its own `War` or " +
            "`Ear` task gets `tasks.withType(..).configureEach { }`, because the convention reached those tasks too and `war { }` " +
            "would not. The replacement for `projectReportDirName` moves only the HTML dependency report: on Gradle 8.14 and 9.8 " +
            "the text reports of `projectReport` stay in `build/reports/project`. Only a literal `projectReportDirName` is changed. " +
            "Reads, and a script's own `def`, `val` or `ext` of the same name, are left alone. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/8.14/userguide/upgrading_version_8.html#war_convention_deprecation) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaVisitor<ExecutionContext>() {
            private Set<String> shadowed = new HashSet<>();
            private Set<String> kotlinAccessors = new HashSet<>();
            private Set<String> customTaskTypes = new HashSet<>();

            @Override
            public J preVisit(J tree, ExecutionContext ctx) {
                if (tree instanceof JavaSourceFile) {
                    shadowed = findShadowedProperties(tree);
                    kotlinAccessors = tree instanceof K.CompilationUnit ? findPluginsBlockPlugins(tree) : new HashSet<>();
                    customTaskTypes = findTaskTypeReferences(tree);
                }
                return tree;
            }

            @Override
            public J visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
                String property = propertyAssigned(assignment.getVariable());
                Scope scope = property == null || shadowed.contains(property) ? Scope.OTHER : scope();
                if (scope == Scope.OTHER) {
                    return super.visitAssignment(assignment, ctx);
                }
                boolean kotlin = getCursor().firstEnclosing(JavaSourceFile.class) instanceof K.CompilationUnit;
                Expression value = assignment.getAssignment().withPrefix(Space.EMPTY);
                if (PROJECT_REPORT_DIR_NAME.equals(property)) {
                    value = underReports(value);
                    if (value == null) {
                        return super.visitAssignment(assignment, ctx);
                    }
                }
                String snippet = snippet(property, kotlin, scope);
                JavaTemplate template = kotlin ? KotlinTemplate.builder(snippet).build() : GroovyTemplate.builder(snippet).build();
                // The value travels as a parameter rather than as text, so a `#{` inside it cannot be mistaken for a placeholder
                J applied = template.apply(getCursor(), assignment.getCoordinates().replace(), value);
                // Every snippet above wraps the assignment in a block, so the value sits one level deeper than it was written
                return MovedExpressions.reindent(applied, value, getCursor(), 1);
            }

            private String snippet(String property, boolean kotlin, Scope scope) {
                if (PROJECT_REPORT_DIR_NAME.equals(property)) {
                    return kotlin ?
                            "tasks.withType<HtmlDependencyReportTask> {\n    projectReportDirectory.set(project.layout.buildDirectory.dir(#{any()}))\n}" :
                            "tasks.withType(HtmlDependencyReportTask) {\n    projectReportDirectory = project.layout.buildDirectory.dir(#{any()})\n}";
                }
                boolean war = WEB_APP_DIR_NAME.equals(property);
                String task = war ? "war" : "ear";
                String type = war ? "War" : "Ear";
                String body;
                if (LIB_DIR_NAME.equals(property)) {
                    // `Ear.libDirName` is a plain `String` where the others are directory properties,
                    // so the value crosses unchanged and needs no `file(..)` or `.set(..)`
                    body = LIB_DIR_NAME + " = #{any()}";
                } else {
                    String directory = war ? "webAppDirectory" : "appDirectory";
                    body = kotlin ? directory + ".set(file(#{any()}))" : directory + " = file(#{any()})";
                }
                String block;
                if (customTaskTypes.contains(type)) {
                    block = kotlin ? "tasks.withType<" + type + ">().configureEach" : "tasks.withType(" + type + ").configureEach";
                } else if (!kotlin) {
                    block = task;
                } else if (scope == Scope.SCRIPT && kotlinAccessors.contains(task)) {
                    block = "tasks." + task;
                } else {
                    // Type-safe accessors exist only for plugins a `plugins { }` block applies to this very script
                    block = "tasks.named<" + type + ">(\"" + task + "\")";
                }
                return block + " {\n    " + body + "\n}";
            }

            private @Nullable Expression underReports(Expression value) {
                if (!(value instanceof J.Literal)) {
                    return null;
                }
                J.Literal literal = (J.Literal) value;
                String source = literal.getValueSource();
                if (!(literal.getValue() instanceof String) || source == null || source.length() < 2) {
                    return null;
                }
                String quote = source.substring(0, 1);
                return literal.withValue("reports/" + literal.getValue())
                        .withValueSource(quote + "reports/" + source.substring(1));
            }

            private @Nullable String propertyAssigned(Expression variable) {
                if (variable instanceof J.Identifier) {
                    String name = ((J.Identifier) variable).getSimpleName();
                    return PROPERTIES.contains(name) ? name : null;
                }
                if (variable instanceof J.FieldAccess) {
                    J.FieldAccess field = (J.FieldAccess) variable;
                    return PROPERTIES.contains(field.getSimpleName()) && field.getTarget() instanceof J.Identifier &&
                            "project".equals(((J.Identifier) field.getTarget()).getSimpleName()) ? field.getSimpleName() : null;
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

    private static Set<String> findTaskTypeReferences(J tree) {
        Set<String> types = new HashSet<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, Integer p) {
                if ("War".equals(identifier.getSimpleName()) || "Ear".equals(identifier.getSimpleName())) {
                    types.add(identifier.getSimpleName());
                }
                return super.visitIdentifier(identifier, p);
            }
        }.visit(tree, 0);
        return types;
    }

    /**
     * The plugin names or ids a script's own {@code plugins { }} block mentions, the only plugins for which Kotlin generates
     * type-safe accessors.
     */
    private static Set<String> findPluginsBlockPlugins(J tree) {
        Set<String> plugins = new HashSet<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, Integer p) {
                if ("plugins".equals(method.getSimpleName()) && method.getSelect() == null) {
                    new JavaIsoVisitor<Integer>() {
                        @Override
                        public J.Identifier visitIdentifier(J.Identifier identifier, Integer q) {
                            plugins.add(identifier.getSimpleName());
                            return identifier;
                        }

                        @Override
                        public J.Literal visitLiteral(J.Literal literal, Integer q) {
                            plugins.add(String.valueOf(literal.getValue()));
                            return literal;
                        }
                    }.visit(method, 0);
                }
                return super.visitMethodInvocation(method, p);
            }
        }.visit(tree, 0);
        return plugins;
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
