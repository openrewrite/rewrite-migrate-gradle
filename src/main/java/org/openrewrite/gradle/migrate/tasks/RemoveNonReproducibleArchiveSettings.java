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

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;

import static java.util.Arrays.asList;
import static java.util.Collections.singleton;

public class RemoveNonReproducibleArchiveSettings extends Recipe {

    @Getter
    final String displayName = "Remove archive settings that defeat reproducible builds";

    @Getter
    final String description = "Remove `preserveFileTimestamps = true` and `reproducibleFileOrder = false` from " +
            "archive tasks, in either DSL spelling, so the archive takes Gradle's own default instead. Timestamps " +
            "written into an archive, and file order that follows the filesystem, both make the same sources produce " +
            "a different artifact on each build, which means a published artifact cannot be checked against a " +
            "rebuild of its sources. The reproducible values written explicitly are left alone, since someone who " +
            "wrote them agrees with the default and said so deliberately. See the " +
            "[Gradle best practice](https://docs.gradle.org/current/userguide/best_practices_security.html#builds_should_be_reproducible).";

    @Getter
    final Set<String> tags = singleton("security");

    @Getter
    final Duration estimatedEffortPerOccurrence = Duration.ofMinutes(2);

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block b = (J.Block) super.visitBlock(block, ctx);
                return b.withStatements(ListUtils.filter(b.getStatements(),
                        statement -> !defeatsReproducibility(statement)));
            }

            @Override
            public @Nullable J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
                if (!hasEmptyClosure(m) || hasEmptyClosure(method)) {
                    return m;
                }
                // A call that creates a task stays, since removing it would remove the task, but the closure
                // left behind configures nothing and Gradle does not require one.
                if (TASK_CREATING_METHODS.contains(m.getSimpleName())) {
                    return m.withArguments(ListUtils.filter(m.getArguments(),
                            argument -> !isEmptyClosure(argument)));
                }
                // Anything else emptied of its settings configures nothing; all it would still do is realise
                // the task eagerly, which Gradle discourages.
                return null;
            }

            @Override
            public @Nullable J visitReturn(J.Return return_, ExecutionContext ctx) {
                J.Return r = (J.Return) super.visitReturn(return_, ctx);
                // Groovy wraps a closure's or script's final expression in an implicit return
                return r.getExpression() == null && return_.getExpression() != null ? null : r;
            }
        });
    }

    private static final Set<String> TASK_CREATING_METHODS = new HashSet<>(asList("create", "maybeCreate", "register", "task"));

    private static boolean hasEmptyClosure(J.MethodInvocation method) {
        for (Expression argument : method.getArguments()) {
            if (isEmptyClosure(argument)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEmptyClosure(Expression argument) {
        return argument instanceof J.Lambda && ((J.Lambda) argument).getBody() instanceof J.Block &&
                ((J.Block) ((J.Lambda) argument).getBody()).getStatements().isEmpty();
    }

    private static boolean defeatsReproducibility(Statement statement) {
        // Groovy parses a closure's final statement as an implicit return of its expression, so the last setting
        // in a block arrives wrapped rather than as a bare assignment.
        Object candidate = statement instanceof J.Return ? ((J.Return) statement).getExpression() : statement;
        if (!(candidate instanceof J.Assignment)) {
            return false;
        }
        J.Assignment assignment = (J.Assignment) candidate;

        String name = variableName(assignment.getVariable());
        if (name == null) {
            return false;
        }

        Object value = assignment.getAssignment() instanceof J.Literal ?
                ((J.Literal) assignment.getAssignment()).getValue() :
                null;
        if (!(value instanceof Boolean)) {
            return false;
        }

        // The Kotlin DSL spells a Boolean property's accessor with the `is` prefix
        if ("preserveFileTimestamps".equals(name) || "isPreserveFileTimestamps".equals(name)) {
            return Boolean.TRUE.equals(value);
        }
        if ("reproducibleFileOrder".equals(name) || "isReproducibleFileOrder".equals(name)) {
            return Boolean.FALSE.equals(value);
        }
        return false;
    }

    private static @Nullable String variableName(Expression variable) {
        if (variable instanceof J.Identifier) {
            return ((J.Identifier) variable).getSimpleName();
        }
        if (variable instanceof J.FieldAccess) {
            return ((J.FieldAccess) variable).getSimpleName();
        }
        return null;
    }
}
