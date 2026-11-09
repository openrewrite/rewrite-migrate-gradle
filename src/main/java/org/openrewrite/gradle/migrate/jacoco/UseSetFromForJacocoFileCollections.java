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
package org.openrewrite.gradle.migrate.jacoco;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.groovy.GroovyTemplate;
import org.openrewrite.groovy.tree.G;
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
import java.util.Iterator;
import java.util.Set;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseSetFromForJacocoFileCollections extends Recipe {

    private static final Set<String> PROPERTIES = new HashSet<>(Arrays.asList(
            "classDirectories", "executionData", "sourceDirectories", "additionalClassDirs", "additionalSourceDirs"));
    private static final Set<String> TASK_NAMES = new HashSet<>(Arrays.asList(
            "jacocoTestReport", "jacocoTestCoverageVerification"));
    private static final Set<String> TASK_TYPES = new HashSet<>(Arrays.asList(
            "JacocoReport", "JacocoCoverageVerification"));

    String displayName = "Use `setFrom` for the file collections of JaCoCo tasks";

    String description = "Gradle 6.0 removed the setters of `classDirectories`, `executionData`, `sourceDirectories`, " +
            "`additionalClassDirs` and `additionalSourceDirs` from `JacocoReportBase`. Assigning one fails with " +
            "`Cannot set the value of read-only property` on Gradle 6.9 and 7.6, and with `Val cannot be reassigned` in a " +
            "Kotlin script. Later Gradle versions accept the assignment again, but `setFrom` works on all of them. " +
            "This recipe acts only on assignments inside JaCoCo report and coverage verification task configuration. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/current/userguide/upgrading_version_5.html) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaVisitor<ExecutionContext>() {
            private Set<String> shadowed = new HashSet<>();

            @Override
            public J preVisit(J tree, ExecutionContext ctx) {
                if (tree instanceof JavaSourceFile) {
                    shadowed = declaredVariables(tree);
                }
                return tree;
            }

            @Override
            public J visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
                J visited = super.visitAssignment(assignment, ctx);
                if (!(visited instanceof J.Assignment)) {
                    return visited;
                }
                J.Assignment a = (J.Assignment) visited;
                Expression variable = a.getVariable();
                if (variable instanceof J.Identifier) {
                    String name = ((J.Identifier) variable).getSimpleName();
                    return PROPERTIES.contains(name) && !shadowed.contains(name) && isInsideJacocoTask() ?
                            setFrom(a, name + ".setFrom(#{any()})", a.getAssignment()) : a;
                }
                if (variable instanceof J.FieldAccess) {
                    J.FieldAccess field = (J.FieldAccess) variable;
                    if (PROPERTIES.contains(field.getSimpleName()) &&
                        (namesJacocoTask(field.getTarget()) || isOwnTask(field.getTarget()) && isInsideJacocoTask())) {
                        return setFrom(a, "#{any()}." + field.getSimpleName() + ".setFrom(#{any()})", field.getTarget(), a.getAssignment());
                    }
                }
                return a;
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J visited = super.visitMethodInvocation(method, ctx);
                if (!(visited instanceof J.MethodInvocation)) {
                    return visited;
                }
                // `setClassDirectories(x)`, only callable as a method in a Groovy script
                J.MethodInvocation m = (J.MethodInvocation) visited;
                String property = propertyOfSetter(m.getSimpleName());
                if (property == null || m.getSelect() != null || m.getArguments().size() != 1 || !isInsideJacocoTask()) {
                    return m;
                }
                return setFrom(m, property + ".setFrom(#{any()})", m.getArguments().get(0));
            }

            private J setFrom(J.Assignment a, String code, Object... parameters) {
                return template(code).apply(getCursor(), a.getCoordinates().replace(), withoutPrefixes(parameters));
            }

            private J setFrom(J.MethodInvocation m, String code, Object... parameters) {
                return template(code).apply(getCursor(), m.getCoordinates().replace(), withoutPrefixes(parameters));
            }

            private JavaTemplate template(String code) {
                return getCursor().firstEnclosing(JavaSourceFile.class) instanceof K.CompilationUnit ?
                        KotlinTemplate.builder(code).build() : GroovyTemplate.builder(code).build();
            }

            private Object[] withoutPrefixes(Object[] parameters) {
                return Arrays.stream(parameters).map(p -> ((Expression) p).withPrefix(Space.EMPTY)).toArray();
            }

            private boolean isInsideJacocoTask() {
                Iterator<Object> enclosing = getCursor().getParentOrThrow().getPath(J.MethodInvocation.class::isInstance);
                while (enclosing.hasNext()) {
                    J.MethodInvocation m = (J.MethodInvocation) enclosing.next();
                    if (configuresJacocoTask(m)) {
                        return true;
                    }
                }
                return false;
            }
        });
    }

    private static Set<String> declaredVariables(J tree) {
        Set<String> names = new HashSet<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable variable, Integer p) {
                names.add(variable.getSimpleName());
                return super.visitVariable(variable, p);
            }
        }.visit(tree, 0);
        return names;
    }

    private static boolean configuresJacocoTask(J.MethodInvocation m) {
        if (TASK_NAMES.contains(m.getSimpleName()) || namesJacocoTask(m.getSelect())) {
            return true;
        }
        if (m.getTypeParameters() != null) {
            for (Expression typeParameter : m.getTypeParameters()) {
                if (typeParameter instanceof J.Identifier && TASK_TYPES.contains(((J.Identifier) typeParameter).getSimpleName())) {
                    return true;
                }
            }
        }
        for (Expression argument : m.getArguments()) {
            if (argument instanceof J.Literal && TASK_NAMES.contains(String.valueOf(((J.Literal) argument).getValue())) ||
                namesJacocoType(argument)) {
                return true;
            }
        }
        return false;
    }

    /**
     * `JacocoReport`, `JacocoReport::class`, `JacocoReport.class`, `JacocoReport::class.java` or `type: JacocoReport`.
     */
    private static boolean namesJacocoType(Expression argument) {
        if (argument instanceof J.Identifier) {
            return TASK_TYPES.contains(((J.Identifier) argument).getSimpleName());
        }
        if (argument instanceof J.FieldAccess) {
            J.FieldAccess field = (J.FieldAccess) argument;
            return TASK_TYPES.contains(field.getSimpleName()) || namesJacocoType(field.getTarget());
        }
        if (argument instanceof J.MemberReference) {
            return namesJacocoType(((J.MemberReference) argument).getContaining());
        }
        if (argument instanceof G.MapEntry) {
            return namesJacocoType(((G.MapEntry) argument).getValue());
        }
        if (argument instanceof J.MethodInvocation) {
            // `task report(type: JacocoReport)`, where the name is parsed as an invocation
            for (Expression nested : ((J.MethodInvocation) argument).getArguments()) {
                if (namesJacocoType(nested)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean namesJacocoTask(@Nullable Expression expression) {
        if (expression instanceof J.Identifier) {
            return TASK_NAMES.contains(((J.Identifier) expression).getSimpleName());
        }
        if (expression instanceof J.FieldAccess) {
            J.FieldAccess field = (J.FieldAccess) expression;
            return TASK_NAMES.contains(field.getSimpleName());
        }
        if (expression instanceof J.MethodInvocation) {
            J.MethodInvocation m = (J.MethodInvocation) expression;
            return configuresJacocoTask(m);
        }
        return false;
    }

    /**
     * `it.classDirectories` or `delegate.classDirectories` inside the task's own configuration.
     */
    private static boolean isOwnTask(Expression expression) {
        return expression instanceof J.Identifier &&
               ("it".equals(((J.Identifier) expression).getSimpleName()) || "delegate".equals(((J.Identifier) expression).getSimpleName()));
    }

    private static @Nullable String propertyOfSetter(String name) {
        if (name.length() > 3 && name.startsWith("set")) {
            String property = Character.toLowerCase(name.charAt(3)) + name.substring(4);
            return PROPERTIES.contains(property) ? property : null;
        }
        return null;
    }
}
