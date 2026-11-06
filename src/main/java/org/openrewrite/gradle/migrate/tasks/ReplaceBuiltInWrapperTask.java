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
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.marker.OmitParentheses;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.Space;
import org.openrewrite.marker.Markers;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static java.util.Collections.emptyList;

@Value
@EqualsAndHashCode(callSuper = false)
public class ReplaceBuiltInWrapperTask extends Recipe {

    private static final String WRAPPER = "wrapper";
    private static final String WRAPPER_TYPE = "org.gradle.api.tasks.wrapper.Wrapper";

    String displayName = "Configure the built-in `wrapper` task instead of redeclaring it";

    String description = "Gradle 4.8 deprecated replacing a built-in task and Gradle 5.0 made it an error, which still fails on " +
            "Gradle 8.14.4 and 9.8.2: `Cannot add task 'wrapper' as a task with that name already exists`. This recipe rewrites " +
            "`task wrapper(type: Wrapper) { .. }`, `tasks.register('wrapper', Wrapper) { .. }` and `tasks.create('wrapper', Wrapper) { .. }` " +
            "to `wrapper { .. }`, and the Kotlin forms to `tasks.wrapper { .. }`. A declaration without configuration is removed. " +
            "Only the root project has a built-in `wrapper` task, so declarations in a subproject, which still work, are left " +
            "alone, as are declarations nested in a block and those with other arguments such as `group:`. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/current/userguide/upgrading_version_4.html#rel4.8:configure_internal_tasks).";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaVisitor<ExecutionContext>() {
            @Override
            public @Nullable J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                JavaSourceFile sourceFile = getCursor().firstEnclosingOrThrow(JavaSourceFile.class);
                if (!isTopLevelStatement() || !isRootProject(sourceFile) || importsAnotherWrapper(sourceFile)) {
                    return method;
                }
                if (sourceFile instanceof G.CompilationUnit) {
                    return groovy(method);
                }
                return kotlin(method);
            }

            private boolean isTopLevelStatement() {
                Object parent = getCursor().getParentTreeCursor().getValue();
                if (parent instanceof JavaSourceFile) {
                    return true;
                }
                // A Kotlin script's statements sit in a block of the compilation unit
                return parent instanceof J.Block && getCursor().getParentTreeCursor().getParentTreeCursor().getValue() instanceof JavaSourceFile;
            }

            private @Nullable J groovy(J.MethodInvocation m) {
                J.Lambda closure;
                if ("task".equals(m.getSimpleName()) && m.getSelect() == null && m.getArguments().size() == 1 &&
                    m.getArguments().get(0) instanceof J.MethodInvocation) {
                    // task wrapper(type: Wrapper) { }
                    J.MethodInvocation declaration = (J.MethodInvocation) m.getArguments().get(0);
                    if (!WRAPPER.equals(declaration.getSimpleName()) || declaration.getSelect() != null) {
                        return m;
                    }
                    List<Expression> arguments = declaration.getArguments();
                    if (arguments.isEmpty() || !isTypeEntry(arguments.get(0)) || arguments.size() > 2 ||
                        arguments.size() == 2 && !(arguments.get(1) instanceof J.Lambda)) {
                        return m;
                    }
                    closure = arguments.size() == 2 ? (J.Lambda) arguments.get(1) : null;
                } else if (isContainerDeclaration(m) && isGroovyTypedName(m.getArguments())) {
                    // tasks.register('wrapper', Wrapper) { }
                    closure = m.getArguments().size() == 3 ? (J.Lambda) m.getArguments().get(2) : null;
                } else {
                    return m;
                }
                return closure == null ? null : configureWrapper(m, null, closure);
            }

            private @Nullable J kotlin(J.MethodInvocation m) {
                boolean declares = "task".equals(m.getSimpleName()) && m.getSelect() == null || isContainerDeclaration(m);
                if (!declares || m.getTypeParameters() == null || m.getTypeParameters().size() != 1 ||
                    !isWrapperType(m.getTypeParameters().get(0))) {
                    return m;
                }
                List<Expression> arguments = m.getArguments();
                if (arguments.isEmpty() || arguments.size() > 2 || !isWrapperName(arguments.get(0)) ||
                    arguments.size() == 2 && !(arguments.get(1) instanceof J.Lambda)) {
                    return m;
                }
                if (arguments.size() == 1) {
                    return null;
                }
                J.Identifier tasks = new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, emptyList(), "tasks", null, null);
                J.MethodInvocation configured = configureWrapper(m, tasks, (J.Lambda) arguments.get(1));
                // Kotlin prints `tasks.wrapper() { }` unless told that nothing precedes the trailing lambda
                return configured.getPadding().withArguments(configured.getPadding().getArguments()
                        .withMarkers(Markers.build(Collections.singletonList(new OmitParentheses(Tree.randomId())))));
            }

            private J.MethodInvocation configureWrapper(J.MethodInvocation m, J.@Nullable Identifier select, J.Lambda closure) {
                return m.withName(m.getName().withSimpleName(WRAPPER).withPrefix(Space.EMPTY).withType(null))
                        .withMethodType(null)
                        .getPadding().withSelect(select == null ? null : JRightPadded.build(select))
                        .getPadding().withTypeParameters(null)
                        .withArguments(Collections.singletonList(closure));
            }

            private boolean isContainerDeclaration(J.MethodInvocation m) {
                return ("register".equals(m.getSimpleName()) || "create".equals(m.getSimpleName())) &&
                       m.getSelect() instanceof J.Identifier && "tasks".equals(((J.Identifier) m.getSelect()).getSimpleName());
            }

            private boolean isGroovyTypedName(List<Expression> arguments) {
                return (arguments.size() == 2 || arguments.size() == 3 && arguments.get(2) instanceof J.Lambda) &&
                       isWrapperName(arguments.get(0)) && isWrapperType(arguments.get(1));
            }

            private boolean isWrapperName(Expression expression) {
                return expression instanceof J.Literal && WRAPPER.equals(((J.Literal) expression).getValue());
            }

            private boolean isTypeEntry(Expression expression) {
                if (!(expression instanceof G.MapEntry)) {
                    return false;
                }
                G.MapEntry entry = (G.MapEntry) expression;
                return entry.getKey() instanceof J.Literal && "type".equals(((J.Literal) entry.getKey()).getValue()) &&
                       isWrapperType(entry.getValue());
            }

            private boolean isWrapperType(Expression expression) {
                if (expression instanceof J.Identifier) {
                    return "Wrapper".equals(((J.Identifier) expression).getSimpleName());
                }
                return expression instanceof J.FieldAccess && WRAPPER_TYPE.equals(((J.FieldAccess) expression).printTrimmed());
            }

            private boolean importsAnotherWrapper(JavaSourceFile sourceFile) {
                for (J.Import anImport : sourceFile.getImports()) {
                    String imported = anImport.getQualid().printTrimmed();
                    if (imported.endsWith(".Wrapper") && !WRAPPER_TYPE.equals(imported)) {
                        return true;
                    }
                }
                return false;
            }

            /**
             * Only the root project of a build has the task. A subproject's `wrapper` is the script's own and still works.
             */
            private boolean isRootProject(JavaSourceFile sourceFile) {
                Optional<GradleProject> project = sourceFile.getMarkers().findFirst(GradleProject.class);
                if (project.isPresent() && !project.get().getPath().isEmpty()) {
                    return ":".equals(project.get().getPath());
                }
                return sourceFile.getSourcePath().getParent() == null;
            }
        });
    }
}
