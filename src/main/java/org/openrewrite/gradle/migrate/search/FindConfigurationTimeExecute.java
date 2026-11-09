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
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.gradle.IsSettingsGradle;
import org.openrewrite.groovy.GroovyIsoVisitor;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.SearchResult;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Value
@EqualsAndHashCode(callSuper = false)
public class FindConfigurationTimeExecute extends Recipe {

    private static final String MESSAGE = "Starts a process at configuration time, deprecated since Gradle 9 and an error in Gradle 11: move it into a task or use `providers.exec`";
    private static final Set<String> TASK_ACTIONS = new HashSet<>(Arrays.asList("doFirst", "doLast"));

    String displayName = "Find `\"cmd\".execute()` at configuration time";

    String description = "Groovy's `String.execute()` and `List.execute()` at configuration time print a deprecation warning on Gradle 9.8, which says it " +
            "will fail with an error in Gradle 11, and are already an error with the configuration cache on. What the output feeds decides the " +
            "replacement. Calls in `doFirst`, `doLast` and classes are skipped, since those run later. A closure run later than configuration, " +
            "or a variable holding the command, can't be told apart, so the first is marked and the second is not. Groovy only, since `execute` is a Groovy helper. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/9.8.0/userguide/upgrading_version_9.html#config_time_external_processes) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(Preconditions.or(new IsBuildGradle<>(), new IsSettingsGradle<>()), new GroovyIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                if ("execute".equals(m.getSimpleName()) && isCommand(m.getSelect()) &&
                        getCursor().firstEnclosing(J.ClassDeclaration.class) == null &&
                        !ScriptSearch.isInside(getCursor(), TASK_ACTIONS)) {
                    return SearchResult.found(m, MESSAGE);
                }
                return m;
            }

            private boolean isCommand(@Nullable Expression select) {
                return select instanceof G.GString || select instanceof G.ListLiteral ||
                        select instanceof J.Literal && ((J.Literal) select).getValue() instanceof String;
            }
        });
    }
}
