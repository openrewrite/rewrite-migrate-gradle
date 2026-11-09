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
import java.util.Map;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateTaskOutputProperties extends Recipe {

    private static final String REPORTS_DIR = "reportsDir";
    private static final String BIN_RESULTS_DIR = "binResultsDir";
    private static final String DESTINATION_DIR = "destinationDir";
    private static final String TEST_RESULT_DIRS = "testResultDirs";

    private static final Map<String, String> TEST_TYPES;
    private static final Map<String, String> TEST_REPORT_TYPES =
            Collections.singletonMap("TestReport", "org.gradle.api.tasks.testing.TestReport");
    private static final Map<String, String> REPLACEMENTS;

    static {
        Map<String, String> testTypes = new HashMap<>();
        testTypes.put("AbstractTestTask", "org.gradle.api.tasks.testing.AbstractTestTask");
        testTypes.put("Test", "org.gradle.api.tasks.testing.Test");
        TEST_TYPES = Collections.unmodifiableMap(testTypes);

        Map<String, String> replacements = new HashMap<>();
        replacements.put(REPORTS_DIR, "reportsDirectory");
        replacements.put(BIN_RESULTS_DIR, "binaryResultsDirectory");
        replacements.put(DESTINATION_DIR, "destinationDirectory");
        replacements.put(TEST_RESULT_DIRS, "testResults");
        REPLACEMENTS = Collections.unmodifiableMap(replacements);
    }

    String displayName = "Migrate removed test and JaCoCo output properties";

    String description = "Gradle 8.0 removed `JacocoPluginExtension.reportsDir`, `AbstractTestTask.binResultsDir` and " +
            "`TestReport.testResultDirs`, so setting them fails on Gradle 8.14.4 and 9.8.2. `TestReport.destinationDir` is " +
            "deprecated, works on 8.14.4 and fails on 9.8.2. This recipe sets `reportsDirectory`, `binaryResultsDirectory` " +
            "and `destinationDirectory` instead, wrapping a string in `file(..)` since they reject one on Gradle 8, and calls " +
            "`testResults.setFrom(..)` since `testResults` as an assignment rejects the list or file `testResultDirs` " +
            "accepted. Only writes change, and only on `jacoco` or a proven `Test` or `TestReport` task: reads change " +
            "type, and `destinationDir` of `Javadoc`, `reportsDir` of `checkstyle` and others are unrelated. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/8.14/userguide/upgrading_version_7.html#v7_report_and_test_report_api_cleanup).";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaVisitor<ExecutionContext>() {
            private @Nullable TaskScope testTasks;
            private @Nullable TaskScope testReportTasks;

            private TaskScope testTasks() {
                JavaSourceFile sourceFile = getCursor().firstEnclosingOrThrow(JavaSourceFile.class);
                if (testTasks == null || !testTasks.isFor(sourceFile)) {
                    testTasks = new TaskScope(sourceFile, TEST_TYPES, "test"::equals);
                }
                return testTasks;
            }

            private TaskScope testReportTasks() {
                JavaSourceFile sourceFile = getCursor().firstEnclosingOrThrow(JavaSourceFile.class);
                if (testReportTasks == null || !testReportTasks.isFor(sourceFile)) {
                    testReportTasks = new TaskScope(sourceFile, TEST_REPORT_TYPES, n -> false);
                }
                return testReportTasks;
            }

            @Override
            public J visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
                J.Assignment a = (J.Assignment) super.visitAssignment(assignment, ctx);
                Expression variable = a.getVariable();
                if (variable instanceof J.Identifier) {
                    String property = ((J.Identifier) variable).getSimpleName();
                    if (REPLACEMENTS.containsKey(property) && isInOwnerClosure(property)) {
                        return replace(a.getPrefix(), null, property, a.getAssignment());
                    }
                } else if (variable instanceof J.FieldAccess) {
                    J.FieldAccess field = (J.FieldAccess) variable;
                    if (REPLACEMENTS.containsKey(field.getSimpleName()) && isOwner(field.getTarget(), field.getSimpleName())) {
                        return replace(a.getPrefix(), field.getTarget(), field.getSimpleName(), a.getAssignment());
                    }
                }
                return a;
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
                String property = setterProperty(m);
                if (property == null || m.getArguments().size() != 1 || m.getArguments().get(0) instanceof J.Lambda ||
                    m.getArguments().get(0) instanceof J.Empty || !isStatement()) {
                    return m;
                }
                boolean owner = m.getSelect() == null ? isInOwnerClosure(m.getSimpleName()) && isInOwnerClosure(property) :
                        isOwner(m.getSelect(), property);
                return owner ? replace(m.getPrefix(), m.getSelect(), property, m.getArguments().get(0)) : m;
            }

            private @Nullable String setterProperty(J.MethodInvocation m) {
                String name = m.getSimpleName();
                if (name.length() > 3 && name.startsWith("set")) {
                    String property = Character.toLowerCase(name.charAt(3)) + name.substring(4);
                    return REPLACEMENTS.containsKey(property) ? property : null;
                }
                // Only Groovy turns a property into a method of its name
                return getCursor().firstEnclosing(JavaSourceFile.class) instanceof G.CompilationUnit &&
                       REPLACEMENTS.containsKey(name) ? name : null;
            }

            private boolean isStatement() {
                Object parent = getCursor().getParentTreeCursor().getValue();
                return parent instanceof J.Block || parent instanceof J.Return || parent instanceof JavaSourceFile;
            }

            /**
             * @param name The property or setter, which a script's own variable or method of that name hides.
             */
            private boolean isInOwnerClosure(String name) {
                TaskScope scope = testTasks();
                if (scope.isLexical(name)) {
                    return false;
                }
                switch (REPLACEMENTS.containsKey(name) ? name : uncapitalizedSetter(name)) {
                    case REPORTS_DIR:
                        return isJacocoClosure();
                    case BIN_RESULTS_DIR:
                        return scope.isInsideTaskClosure(getCursor());
                    default:
                        return testReportTasks().isInsideTaskClosure(getCursor());
                }
            }

            private String uncapitalizedSetter(String setter) {
                return Character.toLowerCase(setter.charAt(3)) + setter.substring(4);
            }

            private boolean isOwner(Expression target, String property) {
                switch (property) {
                    case REPORTS_DIR:
                        return isJacoco(target);
                    case BIN_RESULTS_DIR:
                        return testTasks().isTaskReference(target, getCursor());
                    default:
                        return testReportTasks().isTaskReference(target, getCursor());
                }
            }

            /**
             * `test { jacoco { } }` is `JacocoTaskExtension`, without a `reportsDir` to confuse this with.
             */
            private boolean isJacocoClosure() {
                Cursor lambda = TaskScope.nearestLambda(getCursor());
                if (lambda == null) {
                    return false;
                }
                Object owner = lambda.getParentTreeCursor().getValue();
                if (!(owner instanceof J.MethodInvocation)) {
                    return false;
                }
                J.MethodInvocation m = (J.MethodInvocation) owner;
                if ("jacoco".equals(m.getSimpleName())) {
                    return m.getSelect() == null && !testTasks().isLexical("jacoco");
                }
                return "configure".equals(m.getSimpleName()) && m.getTypeParameters() != null && m.getTypeParameters().size() == 1 &&
                       m.getTypeParameters().get(0) instanceof J.Identifier &&
                       "JacocoPluginExtension".equals(((J.Identifier) m.getTypeParameters().get(0)).getSimpleName());
            }

            private boolean isJacoco(Expression target) {
                if (testTasks().isLexical("jacoco")) {
                    return false;
                }
                if (target instanceof J.Identifier) {
                    return "jacoco".equals(((J.Identifier) target).getSimpleName());
                }
                return target instanceof J.FieldAccess && "jacoco".equals(((J.FieldAccess) target).getSimpleName()) &&
                       ((J.FieldAccess) target).getTarget() instanceof J.Identifier &&
                       "project".equals(((J.Identifier) ((J.FieldAccess) target).getTarget()).getSimpleName());
            }
        });
    }

    /**
     * @return `select.reportsDirectory = value`, or `select.testResults.setFrom(value)`.
     */
    @SuppressWarnings("unchecked")
    private static <T extends J> T replace(Space prefix, @Nullable Expression select, String property, Expression value) {
        Expression replacement = member(select, REPLACEMENTS.get(property));
        if (TEST_RESULT_DIRS.equals(property)) {
            J.Identifier setFrom = identifier("setFrom");
            return (T) new J.MethodInvocation(Tree.randomId(), prefix, Markers.EMPTY, JRightPadded.build(replacement), null, setFrom,
                    JContainer.build(Space.EMPTY, singletonList(JRightPadded.build(asArgument(value))), Markers.EMPTY), null);
        }
        Expression rhs = isString(value) ?
                new J.MethodInvocation(Tree.randomId(), Space.SINGLE_SPACE, Markers.EMPTY, null, null, identifier("file"),
                        JContainer.build(Space.EMPTY, singletonList(JRightPadded.build(asArgument(value))), Markers.EMPTY), null) :
                value.withPrefix(Space.SINGLE_SPACE);
        return (T) new J.Assignment(Tree.randomId(), prefix, Markers.EMPTY, replacement,
                new JLeftPadded<>(Space.SINGLE_SPACE, rhs, Markers.EMPTY), null);
    }

    private static Expression member(@Nullable Expression select, String name) {
        return select == null ? identifier(name) :
                new J.FieldAccess(Tree.randomId(), Space.EMPTY, Markers.EMPTY, select,
                        new JLeftPadded<>(Space.EMPTY, identifier(name), Markers.EMPTY), null);
    }

    private static J.Identifier identifier(String name) {
        return new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, emptyList(), name, null, null);
    }

    /**
     * The value of `destinationDir 'x'` is marked as printed without parentheses, which `file('x')` must not inherit.
     */
    private static Expression asArgument(Expression value) {
        return value.withPrefix(Space.EMPTY).withMarkers(value.getMarkers().removeByType(OmitParentheses.class));
    }

    private static boolean isString(Expression value) {
        return value instanceof G.GString || value instanceof J.Literal && ((J.Literal) value).getValue() instanceof String;
    }
}
