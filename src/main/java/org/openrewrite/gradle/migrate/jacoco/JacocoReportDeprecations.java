/*
 * Copyright 2025 the original author or authors.
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
package org.openrewrite.gradle.migrate.jacoco;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.gradle.migrate.internal.TaskScope;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.marker.OmitParentheses;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JContainer;
import org.openrewrite.java.tree.JLeftPadded;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.Space;
import org.openrewrite.marker.Markers;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;

@Value
@EqualsAndHashCode(callSuper = false)
public class JacocoReportDeprecations extends Recipe {

    private static final Map<String, String> RENAMES;
    private static final Map<String, String> REPORTING_TASK_TYPES;
    private static final Pattern REPORTING_TASK_NAMES = Pattern.compile("test|jacocoTestReport|(checkstyle|pmd|codenarc|spotbugs)[A-Z]\\w*");
    private static final Set<String> REPORT_NAMES = new HashSet<>(asList("console", "csv", "html", "junitXml", "sarif", "text", "xml"));
    private static final Set<String> EVERY_REPORT = new HashSet<>(asList("all", "configureEach"));
    private static final Set<String> EVERY_REPORT_WITH_PARAMETER = new HashSet<>(asList("all", "configureEach", "each", "forEach"));
    private static final Set<String> NAMED_REPORT = new HashSet<>(asList("create", "getByName", "maybeCreate", "named", "register"));

    static {
        Map<String, String> renames = new HashMap<>();
        renames.put("enabled", "required");
        renames.put("isEnabled", "required");
        renames.put("setEnabled", "required");
        renames.put("destination", "outputLocation");
        renames.put("setDestination", "outputLocation");
        RENAMES = Collections.unmodifiableMap(renames);

        Map<String, String> types = new HashMap<>();
        types.put("Checkstyle", "org.gradle.api.plugins.quality.Checkstyle");
        types.put("CodeNarc", "org.gradle.api.plugins.quality.CodeNarc");
        types.put("JacocoReport", "org.gradle.testing.jacoco.tasks.JacocoReport");
        types.put("Pmd", "org.gradle.api.plugins.quality.Pmd");
        types.put("SpotBugsTask", "com.github.spotbugs.snom.SpotBugsTask");
        types.put("Test", "org.gradle.api.tasks.testing.Test");
        REPORTING_TASK_TYPES = Collections.unmodifiableMap(types);
    }

    String displayName = "Use `required` and `outputLocation` on task reports";

    String description = "Gradle 8.0 removed `enabled` from `Report`, so setting it fails on Gradle 8.14.4 and 9.8.2, and Kotlin " +
            "scripts stop compiling on `isEnabled` and on `destination`. In Groovy, `destination` keeps working through 8.14.4 and " +
            "fails on 9.8.2. This recipe sets `required` and `outputLocation` instead, under `reports` of the `Test`, `JacocoReport`, " +
            "`Checkstyle`, `Pmd`, `CodeNarc` and `SpotBugsTask` tasks, and turns the method forms into assignments since those " +
            "have no method form. That includes `reports.all { enabled = false }`, which raises no error today but disables the task " +
            "itself. A string for `destination` is wrapped in `file(..)`, since `outputLocation` rejects one on Gradle 8. A task's own " +
            "`enabled`, and any `reports` block that isn't on one of these tasks, are left alone. " +
            "See [the Gradle docs on this topic](https://docs.gradle.org/current/userguide/upgrading_version_7.html#v7_report_and_test_report_api_cleanup).";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaVisitor<ExecutionContext>() {
            private @Nullable TaskScope scope;

            private TaskScope scope() {
                JavaSourceFile sourceFile = getCursor().firstEnclosingOrThrow(JavaSourceFile.class);
                if (scope == null || !scope.isFor(sourceFile)) {
                    scope = new TaskScope(sourceFile, REPORTING_TASK_TYPES, n -> REPORTING_TASK_NAMES.matcher(n).matches());
                }
                return scope;
            }

            @Override
            public J visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
                J.Assignment a = (J.Assignment) super.visitAssignment(assignment, ctx);
                Expression variable = a.getVariable();
                if (variable instanceof J.Identifier) {
                    J.Identifier id = (J.Identifier) variable;
                    if (isProperty(id.getSimpleName()) && !scope().isLexical(id.getSimpleName()) && isInsideReportClosure()) {
                        return a.withVariable(renamed(id, RENAMES.get(id.getSimpleName())))
                                .withAssignment(value(id.getSimpleName(), a.getAssignment()));
                    }
                } else if (variable instanceof J.FieldAccess) {
                    J.FieldAccess field = (J.FieldAccess) variable;
                    if (isProperty(field.getSimpleName()) && isReport(field.getTarget())) {
                        return a.withVariable(field.withName(renamed(field.getName(), RENAMES.get(field.getSimpleName()))).withType(null))
                                .withAssignment(value(field.getSimpleName(), a.getAssignment()));
                    }
                }
                return a;
            }

            private boolean isProperty(String name) {
                return "enabled".equals(name) || "isEnabled".equals(name) || "destination".equals(name);
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
                if (RENAMES.containsKey(m.getSimpleName()) && m.getArguments().size() == 1 &&
                    !(m.getArguments().get(0) instanceof J.Lambda) && !(m.getArguments().get(0) instanceof J.Empty) &&
                    isStatement() &&
                    (m.getSelect() == null ? !scope().isLexical(m.getSimpleName()) && isInsideReportClosure() : isReport(m.getSelect()))) {
                    return toAssignment(m);
                }
                return m;
            }

            private boolean isStatement() {
                Object parent = getCursor().getParentTreeCursor().getValue();
                return parent instanceof J.Block || parent instanceof J.Return || parent instanceof JavaSourceFile;
            }

            /**
             * `html { enabled = x }`, `reports.html { enabled = x }`, `reports.all { enabled = x }`, `reports.create("html") { enabled = x }`
             */
            private boolean isInsideReportClosure() {
                Cursor lambda = TaskScope.nearestLambda(getCursor());
                if (lambda == null || hasParameters(lambda.getValue())) {
                    return false;
                }
                Object owner = lambda.getParentTreeCursor().getValue();
                if (!(owner instanceof J.MethodInvocation)) {
                    return false;
                }
                J.MethodInvocation m = (J.MethodInvocation) owner;
                return (REPORT_NAMES.contains(m.getSimpleName()) ||
                        EVERY_REPORT.contains(m.getSimpleName()) ||
                        NAMED_REPORT.contains(m.getSimpleName()) && isReportNameLiteral(m)) &&
                       isOnReports(m.getSelect(), lambda.getParentTreeCursor());
            }

            private boolean isReportNameLiteral(J.MethodInvocation m) {
                return !m.getArguments().isEmpty() && m.getArguments().get(0) instanceof J.Literal &&
                       REPORT_NAMES.contains(String.valueOf(((J.Literal) m.getArguments().get(0)).getValue()));
            }

            /**
             * `reports.html`, `html` inside of `reports { }`, `test.reports.html`, or a closure's own parameter over `reports`.
             */
            private boolean isReport(Expression expression) {
                if (expression instanceof J.Identifier) {
                    String name = ((J.Identifier) expression).getSimpleName();
                    if (scope().isLexical(name) && !isParameterOverReports(name)) {
                        return false;
                    }
                    return REPORT_NAMES.contains(name) && isOnReports(null, getCursor()) || isParameterOverReports(name);
                }
                if (expression instanceof J.FieldAccess) {
                    J.FieldAccess field = (J.FieldAccess) expression;
                    return REPORT_NAMES.contains(field.getSimpleName()) && isReportContainer(field.getTarget(), getCursor());
                }
                return false;
            }

            /**
             * @param select The receiver of a call, null for a call that resolves against the enclosing closure.
             */
            private boolean isOnReports(@Nullable Expression select, Cursor cursor) {
                if (select != null) {
                    return isReportContainer(select, cursor);
                }
                Cursor lambda = TaskScope.nearestLambda(cursor.getValue() instanceof J.MethodInvocation ? cursor.getParentTreeCursor() : cursor);
                if (lambda == null || hasParameters(lambda.getValue())) {
                    return false;
                }
                Object owner = lambda.getParentTreeCursor().getValue();
                return owner instanceof J.MethodInvocation && "reports".equals(((J.MethodInvocation) owner).getSimpleName()) &&
                       isOnReportingTask(((J.MethodInvocation) owner).getSelect(), lambda.getParentTreeCursor());
            }

            /**
             * `reports` in a reporting task's closure, or `test.reports`
             */
            private boolean isReportContainer(Expression expression, Cursor cursor) {
                if (expression instanceof J.Identifier) {
                    return "reports".equals(((J.Identifier) expression).getSimpleName()) && !scope().isLexical("reports") &&
                           scope().isInsideTaskClosure(cursor);
                }
                if (expression instanceof J.FieldAccess) {
                    J.FieldAccess field = (J.FieldAccess) expression;
                    return "reports".equals(field.getSimpleName()) && scope().isTaskReference(field.getTarget(), cursor);
                }
                return false;
            }

            private boolean isOnReportingTask(@Nullable Expression select, Cursor reportsBlock) {
                return select == null ? scope().isInsideTaskClosure(reportsBlock.getParentTreeCursor()) :
                        scope().isTaskReference(select, reportsBlock);
            }

            /**
             * `reports.all { report -> report.enabled = x }`, `reports.each { it.enabled = x }`
             */
            private boolean isParameterOverReports(String name) {
                Cursor lambda = TaskScope.nearestLambda(getCursor());
                if (lambda == null) {
                    return false;
                }
                J.Lambda l = lambda.getValue();
                boolean isParameter = "it".equals(name) && !hasParameters(l);
                for (J parameter : l.getParameters().getParameters()) {
                    if (parameter instanceof J.VariableDeclarations) {
                        isParameter |= ((J.VariableDeclarations) parameter).getVariables().get(0).getSimpleName().equals(name);
                    }
                }
                Object owner = lambda.getParentTreeCursor().getValue();
                return isParameter && owner instanceof J.MethodInvocation &&
                       EVERY_REPORT_WITH_PARAMETER.contains(((J.MethodInvocation) owner).getSimpleName()) &&
                       ((J.MethodInvocation) owner).getSelect() != null &&
                       isReportContainer(((J.MethodInvocation) owner).getSelect(), lambda.getParentTreeCursor());
            }
        });
    }

    private static boolean hasParameters(J.Lambda lambda) {
        return lambda.getParameters().getParameters().stream().anyMatch(p -> !(p instanceof J.Empty));
    }

    private static J.Identifier renamed(J.Identifier identifier, String newName) {
        return identifier.withSimpleName(newName).withType(null).withFieldType(null);
    }

    /**
     * `outputLocation` rejects a String on Gradle 8.14.4 and accepts it on 9.8.2; a File works on both.
     */
    private static Expression value(String property, Expression value) {
        if (RENAMES.get(property).equals("outputLocation") &&
            (value instanceof G.GString || value instanceof J.Literal && ((J.Literal) value).getValue() instanceof String)) {
            J.Identifier file = new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, emptyList(), "file", null, null);
            return new J.MethodInvocation(Tree.randomId(), value.getPrefix(), Markers.EMPTY, null, null, file,
                    JContainer.build(Space.EMPTY, Collections.singletonList(JRightPadded.build(asArgument(value))), Markers.EMPTY),
                    null);
        }
        return value;
    }

    /**
     * The value of `destination 'x'` is marked as printed without parentheses, which `file('x')` must not inherit.
     */
    private static Expression asArgument(Expression value) {
        return value.withPrefix(Space.EMPTY).withMarkers(value.getMarkers().removeByType(OmitParentheses.class));
    }

    private static J.Assignment toAssignment(J.MethodInvocation m) {
        J.Identifier name = new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, emptyList(), RENAMES.get(m.getSimpleName()), null, null);
        Expression variable = m.getSelect() == null ? name :
                new J.FieldAccess(Tree.randomId(), Space.EMPTY, Markers.EMPTY, m.getSelect(),
                        new JLeftPadded<>(Space.EMPTY, name, Markers.EMPTY), null);
        Expression rhs = value(m.getSimpleName(), m.getArguments().get(0)).withPrefix(Space.SINGLE_SPACE);
        return new J.Assignment(Tree.randomId(), m.getPrefix(), Markers.EMPTY, variable,
                new JLeftPadded<>(Space.SINGLE_SPACE, rhs, Markers.EMPTY), null);
    }
}
