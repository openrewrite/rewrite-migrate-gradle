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
package org.openrewrite.gradle.migrate.search;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.SearchResult;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Value
@EqualsAndHashCode(callSuper = false)
public class FindCustomTestTasksWithoutTestClasses extends Recipe {

    private static final String MESSAGE = "Custom Test task without both testClassesDirs and classpath: on Gradle 9 it runs no tests and still succeeds";
    private static final Set<String> CREATORS = new HashSet<>(Arrays.asList("register", "create", "maybeCreate", "task"));

    String displayName = "Find custom `Test` tasks without `testClassesDirs` and `classpath`";

    String description = "A `Test` task a build registers itself used to fall back to the `test` source set, with a deprecation warning since " +
            "Gradle 8.1. Gradle 9 removed that convention, and such a task now reports `NO-SOURCE` and the build succeeds, so its tests " +
            "silently stop running. Which classes it should run is a decision the build has to make. A task configured elsewhere in the " +
            "script is also marked. See the [Gradle upgrade guide](https://docs.gradle.org/9.0.0/userguide/upgrading_major_version_9.html) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                if (ScriptSearch.mentions(m, "Test") && isTaskCreation(m) && !configuresTestClasses(m)) {
                    return SearchResult.found(m, MESSAGE);
                }
                return m;
            }

            private boolean isTaskCreation(J.MethodInvocation m) {
                // `task x(type: Test) { }` is a call of `x` as the argument of `task`
                if ("task".equals(m.getSimpleName()) && m.getArguments().get(0) instanceof J.MethodInvocation) {
                    return false;
                }
                if (CREATORS.contains(m.getSimpleName())) {
                    return true;
                }
                J.MethodInvocation enclosing = getCursor().getParentTreeCursor().firstEnclosing(J.MethodInvocation.class);
                return m.getSelect() == null && enclosing != null && "task".equals(enclosing.getSimpleName());
            }
        });
    }

    private static boolean configuresTestClasses(J.MethodInvocation task) {
        Set<String> configured = new HashSet<>();
        JavaIsoVisitor<Integer> visitor = new JavaIsoVisitor<Integer>() {
            @Override
            public J.Assignment visitAssignment(J.Assignment assignment, Integer p) {
                configured.add(name(assignment.getVariable()));
                return super.visitAssignment(assignment, p);
            }

            @Override
            public J.AssignmentOperation visitAssignmentOperation(J.AssignmentOperation assignment, Integer p) {
                configured.add(name(assignment.getVariable()));
                return super.visitAssignmentOperation(assignment, p);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, Integer p) {
                if ("setTestClassesDirs".equals(method.getSimpleName())) {
                    configured.add("testClassesDirs");
                } else if ("setClasspath".equals(method.getSimpleName())) {
                    configured.add("classpath");
                } else if (method.getSelect() != null && ("from".equals(method.getSimpleName()) || "setFrom".equals(method.getSimpleName()))) {
                    configured.add(name(method.getSelect()));
                }
                return super.visitMethodInvocation(method, p);
            }

            private String name(Expression variable) {
                if (variable instanceof J.Identifier) {
                    return ((J.Identifier) variable).getSimpleName();
                }
                return variable instanceof J.FieldAccess ? ((J.FieldAccess) variable).getSimpleName() : "";
            }
        };
        for (Expression argument : task.getArguments()) {
            if (argument instanceof J.Lambda) {
                visitor.visit(argument, 0);
            }
        }
        return configured.contains("testClassesDirs") && configured.contains("classpath");
    }
}
