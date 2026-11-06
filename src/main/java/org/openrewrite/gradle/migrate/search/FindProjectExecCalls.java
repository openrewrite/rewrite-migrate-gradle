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
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.SearchResult;

@Value
@EqualsAndHashCode(callSuper = false)
public class FindProjectExecCalls extends Recipe {

    String displayName = "Find `project.exec` and `project.javaexec` calls";

    String description = "`Project.exec`, `Project.javaexec` and the script-level `exec { }` and `javaexec { }` are deprecated since Gradle 8.11 and " +
            "removed in Gradle 9, where calling them fails. The replacement is an `Exec` task, an injected `ExecOperations` or " +
            "`providers.exec`, depending on when it runs and what uses the result, which takes a person to decide. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/9.0.0/userguide/upgrading_major_version_9.html#removal_of_project_exec_project_javaexec_and_script_level_counterparts) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(Preconditions.or(new IsBuildGradle<>(), new IsSettingsGradle<>()), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                String name = m.getSimpleName();
                if (("exec".equals(name) || "javaexec".equals(name)) && m.getArguments().size() == 1 &&
                        isProjectOrScript(m.getSelect(), m) && getCursor().firstEnclosing(J.ClassDeclaration.class) == null) {
                    return SearchResult.found(m, "`" + name + "` is removed in Gradle 9: use an `Exec` or `JavaExec` task, an injected `ExecOperations`, or `providers." + name + "`");
                }
                return m;
            }

            private boolean isProjectOrScript(@Nullable Expression select, J.MethodInvocation m) {
                if (select == null) {
                    return m.getArguments().get(0) instanceof J.Lambda;
                }
                if (select instanceof J.FieldAccess) {
                    return "project".equals(((J.FieldAccess) select).getSimpleName());
                }
                return select instanceof J.Identifier &&
                        ("project".equals(((J.Identifier) select).getSimpleName()) || "rootProject".equals(((J.Identifier) select).getSimpleName()));
            }
        });
    }
}
