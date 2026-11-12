/*
 * Copyright 2024 the original author or authors.
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
package org.openrewrite.gradle.migrate.settings;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.gradle.IsSettingsGradle;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;

import java.util.List;

@Value
@EqualsAndHashCode(callSuper = false)
public class RemoveEnableFeaturePreview extends Recipe {

    @Option(displayName = "The feature preview name",
            description = "The name of the feature preview to remove.",
            example = "ONE_LOCKFILE_PER_PROJECT")
    String previewFeatureName;

    String displayName = "Remove an enabled Gradle preview feature";

    String description = "Remove an `enableFeaturePreview` call for a feature preview from `settings.gradle` or " +
            "`settings.gradle.kts`. Gradle rejects or warns on a feature preview once the feature is promoted or " +
            "dropped, so the call has to go when upgrading past that version.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        // A JavaIsoVisitor, not a GroovyIsoVisitor, so that the Kotlin DSL's settings.gradle.kts is visited too
        return Preconditions.check(new IsSettingsGradle<>(), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public @Nullable J visit(@Nullable Tree tree, ExecutionContext ctx) {
                J visited = super.visit(tree, ctx);
                // A Groovy script's top-level statements belong to the compilation unit; a Kotlin one's to a block
                if (tree instanceof G.CompilationUnit && visited instanceof G.CompilationUnit) {
                    G.CompilationUnit after = (G.CompilationUnit) visited;
                    return after.withStatements(keepLeadingSpace(((G.CompilationUnit) tree).getStatements(), after.getStatements()));
                }
                return visited;
            }

            @Override
            public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block b = super.visitBlock(block, ctx);
                return b.withStatements(keepLeadingSpace(block.getStatements(), b.getStatements()));
            }

            @Override
            public J.@Nullable MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                return isPreview(method) ? null : method;
            }
        });
    }

    private boolean isPreview(Statement statement) {
        if (!(statement instanceof J.MethodInvocation)) {
            return false;
        }
        J.MethodInvocation method = (J.MethodInvocation) statement;
        return "enableFeaturePreview".equals(method.getSimpleName()) &&
               method.getArguments().size() == 1 &&
               J.Literal.isLiteralValue(method.getArguments().get(0), previewFeatureName);
    }

    /**
     * When the removed call opened its block, the statement that now opens it takes over the call's leading space,
     * so the block or script does not start with the blank line that separated the two.
     */
    private List<Statement> keepLeadingSpace(List<Statement> before, List<Statement> after) {
        if (before.isEmpty() || after.isEmpty() || !isPreview(before.get(0)) || after.get(0) == before.get(0)) {
            return after;
        }
        return ListUtils.mapFirst(after, first -> first.withPrefix(before.get(0).getPrefix()));
    }
}
