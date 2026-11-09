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
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.kotlin.marker.By;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseProjectPathForDependencyNotation extends Recipe {

    private static final Set<String> NOTATION_WRAPPERS = new HashSet<>(Arrays.asList("platform", "enforcedPlatform", "testFixtures"));

    String displayName = "Use `project(\":path\")` instead of a `Project` as dependency notation";

    String description = "Gradle 9.6 deprecates passing a `Project` object as a dependency notation, and it will fail in Gradle 10. " +
            "This recipe replaces a local variable used as a dependency with the `project(\":path\")` call that initialized it, " +
            "which is what the upgrade guide recommends. Only a variable whose initializer is `project(\":literal\")` is " +
            "replaced, so `subprojects.each`, `rootProject` and `project.parent` are left alone, since the path of those isn't known. " +
            "The variable itself stays, as it may have other uses. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/current/userguide/upgrading_version_9.html#dependency_project_notation) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaIsoVisitor<ExecutionContext>() {
            private Map<String, J.MethodInvocation> projectVariables = new HashMap<>();

            @Override
            public @Nullable J visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof JavaSourceFile) {
                    projectVariables = projectVariables((JavaSourceFile) tree);
                }
                return super.visit(tree, ctx);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                if (projectVariables.isEmpty() || !isDependencyNotationPosition(m)) {
                    return m;
                }
                int[] index = {0};
                boolean add = "add".equals(m.getSimpleName());
                return m.withArguments(ListUtils.map(m.getArguments(), argument -> {
                    int i = index[0]++;
                    if (add && i != 1) {
                        return argument;
                    }
                    if (argument instanceof J.Identifier) {
                        J.MethodInvocation project = projectVariables.get(((J.Identifier) argument).getSimpleName());
                        if (project != null) {
                            // The argument's markers say whether Groovy's call was written without parentheses
                            return project.withId(Tree.randomId()).withPrefix(argument.getPrefix()).withMarkers(argument.getMarkers());
                        }
                    }
                    return argument;
                }));
            }

            private boolean isDependencyNotationPosition(J.MethodInvocation m) {
                J.MethodInvocation enclosing = getCursor().getParentOrThrow().firstEnclosing(J.MethodInvocation.class);
                if (enclosing == null) {
                    return false;
                }
                if (NOTATION_WRAPPERS.contains(m.getSimpleName()) && m.getSelect() == null) {
                    return true;
                }
                // `implementation(p)`, `add("implementation", p)` or Kotlin's `"implementation"(p)`
                return "dependencies".equals(enclosing.getSimpleName()) &&
                       (m.getSelect() == null || m.getSelect() instanceof J.Literal);
            }
        });
    }

    /**
     * The variables that can only ever hold `project(":literal")`: declared once in the whole script, under any
     * scope, including as a closure parameter, and never assigned again.
     */
    private static Map<String, J.MethodInvocation> projectVariables(JavaSourceFile sourceFile) {
        Map<String, J.MethodInvocation> initializers = new HashMap<>();
        Set<String> declared = new HashSet<>();
        Set<String> disqualified = new HashSet<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable, Integer p) {
                for (J.VariableDeclarations.NamedVariable variable : multiVariable.getVariables()) {
                    String name = variable.getSimpleName();
                    if (!declared.add(name) || multiVariable.getMarkers().findFirst(By.class).isPresent()) {
                        disqualified.add(name);
                    }
                    J.MethodInvocation project = asProjectPath(variable.getInitializer());
                    if (project == null) {
                        disqualified.add(name);
                    } else {
                        initializers.put(name, project);
                    }
                }
                return super.visitVariableDeclarations(multiVariable, p);
            }

            @Override
            public J.Assignment visitAssignment(J.Assignment assignment, Integer p) {
                if (assignment.getVariable() instanceof J.Identifier) {
                    disqualified.add(((J.Identifier) assignment.getVariable()).getSimpleName());
                }
                return super.visitAssignment(assignment, p);
            }
        }.visit(sourceFile, 0);
        initializers.keySet().removeAll(disqualified);
        return initializers;
    }

    private static J.@Nullable MethodInvocation asProjectPath(@Nullable Expression initializer) {
        if (!(initializer instanceof J.MethodInvocation)) {
            return null;
        }
        J.MethodInvocation m = (J.MethodInvocation) initializer;
        if (!"project".equals(m.getSimpleName()) || m.getSelect() != null || m.getArguments().size() != 1 ||
            !(m.getArguments().get(0) instanceof J.Literal)) {
            return null;
        }
        Object value = ((J.Literal) m.getArguments().get(0)).getValue();
        return value instanceof String && ((String) value).startsWith(":") ? m : null;
    }
}
