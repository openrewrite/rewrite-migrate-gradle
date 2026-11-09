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
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.gradle.IsSettingsGradle;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseUsingForDependencySubstitution extends Recipe {

    String displayName = "Use `using` instead of `with` in dependency substitution rules";

    String description = "Gradle 8.0 removed `DependencySubstitutions.Substitution.with(ComponentSelector)`, " +
            "so a rule `substitute(a).with(b)` fails with `No signature of method` in a Groovy script and `Unresolved reference: with` " +
            "in a Kotlin one. This recipe renames it to `using`. It requires `substitute(..)` as the receiver, optionally " +
            "through `because(..)`, because `with` also names Kotlin's standard library function and Groovy's `Object.with(Closure)`. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/current/userguide/upgrading_version_7.html#v7_dependency_substitution_api_cleanup) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(Preconditions.or(new IsBuildGradle<>(), new IsSettingsGradle<>()), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                if ("with".equals(m.getSimpleName()) && m.getArguments().size() == 1 &&
                    !(m.getArguments().get(0) instanceof J.Lambda) && isSubstitution(m.getSelect())) {
                    return m.withName(m.getName().withSimpleName("using"));
                }
                return m;
            }
        });
    }

    private static boolean isSubstitution(@Nullable Expression select) {
        if (!(select instanceof J.MethodInvocation)) {
            return false;
        }
        J.MethodInvocation m = (J.MethodInvocation) select;
        if ("substitute".equals(m.getSimpleName())) {
            return m.getArguments().size() == 1;
        }
        return "because".equals(m.getSimpleName()) && isSubstitution(m.getSelect());
    }
}
