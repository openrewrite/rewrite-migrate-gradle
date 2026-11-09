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
import org.openrewrite.gradle.migrate.internal.RemoveDeprecatedPropertyVisitor;

import java.util.function.Predicate;

@Value
@EqualsAndHashCode(callSuper = false)
public class RemoveConfigurationVisible extends Recipe {

    private static final Predicate<String> OWNER = "configurations"::equals;

    String displayName = "Remove the deprecated `Configuration.visible` property";

    String description = "`Configuration.visible` has had no effect since Gradle 9.0, and is deprecated for removal in " +
            "Gradle 11. Gradle 9.8.2 warns on `setVisible` where Gradle 8.14.4 does not. This recipe removes `visible` " +
            "and, in Kotlin, `isVisible` where a `configurations` block or call owns it, and leaves anything else with " +
            "that name alone. A configuration that relied on `visible` to have `assemble` build its artifacts now " +
            "needs an explicit task dependency, which this recipe does not add. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/current/userguide/upgrading_version_9.html#deprecate-visible-property) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                Tree withoutVisible = new RemoveDeprecatedPropertyVisitor("visible", OWNER).visit(tree, ctx);
                return new RemoveDeprecatedPropertyVisitor("isVisible", OWNER).visit(withoutVisible, ctx);
            }
        });
    }
}
