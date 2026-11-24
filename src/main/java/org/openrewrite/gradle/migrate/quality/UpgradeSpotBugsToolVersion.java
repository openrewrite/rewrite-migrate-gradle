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
package org.openrewrite.gradle.migrate.quality;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.gradle.DependencyVersionSelector;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.gradle.internal.ChangeStringLiteral;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markup;
import org.openrewrite.maven.MavenDownloadingException;
import org.openrewrite.maven.table.MavenMetadataFailures;
import org.openrewrite.maven.tree.GroupArtifactVersion;
import org.openrewrite.semver.LatestRelease;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.openrewrite.gradle.migrate.internal.BuildScripts.*;

@Value
@EqualsAndHashCode(callSuper = false)
public class UpgradeSpotBugsToolVersion extends Recipe {

    private static final String PLUGIN_ID_PATTERN = "com.github.spotbugs*";

    /// The first SpotBugs Gradle plugin release whose default tool, SpotBugs 4.9.4, reads Java 25 class files.
    private static final String FIRST_PLUGIN_WITH_CURRENT_DEFAULT = "6.2.4";

    @Option(displayName = "New version",
            description = "An exact version or node-style semver selector for the SpotBugs tool.",
            example = "4.9.x")
    String newVersion;

    String displayName = "Upgrade the SpotBugs tool version";

    String description = "Raise a literal `toolVersion` of the SpotBugs Gradle plugin to `newVersion`, preserving newer versions. " +
            "Where a build script declares a SpotBugs plugin older than 6.2.4 and sets no `toolVersion`, set one, " +
            "since the default tool of those plugin versions cannot read Java 25 class files.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        MavenMetadataFailures metadataFailures = new MavenMetadataFailures(this);
        return Preconditions.check(new IsBuildGradle<>(), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public @Nullable J visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof JavaSourceFile)) {
                    return (J) tree;
                }
                JavaSourceFile cu = (JavaSourceFile) tree;
                DependencyVersionSelector selector = new DependencyVersionSelector(metadataFailures,
                        cu.getMarkers().findFirst(GradleProject.class).orElse(null), null);
                try {
                    AtomicBoolean toolVersionSet = new AtomicBoolean();
                    JavaSourceFile upgraded = (JavaSourceFile) new UpgradeToolVersion(selector, toolVersionSet).visitNonNull(cu, ctx);
                    if (toolVersionSet.get()) {
                        return upgraded;
                    }
                    String pluginVersion = declaredPluginVersion(cu, PLUGIN_ID_PATTERN, true);
                    if (pluginVersion == null ||
                        new LatestRelease(null).compare(null, pluginVersion, FIRST_PLUGIN_WITH_CURRENT_DEFAULT) >= 0) {
                        return cu;
                    }
                    String version = select(selector, "0", ctx);
                    return version == null ? cu : addToolVersion(cu, version, ctx);
                } catch (MavenDownloadingException e) {
                    return Markup.warn(cu, e);
                }
            }
        });
    }

    private @Nullable String select(DependencyVersionSelector selector, String currentVersion, ExecutionContext ctx) throws MavenDownloadingException {
        return selector.select(new GroupArtifactVersion("com.github.spotbugs", "spotbugs", currentVersion),
                "spotbugs", newVersion, (String) null, ctx);
    }

    private JavaSourceFile addToolVersion(JavaSourceFile cu, String version, ExecutionContext ctx) {
        String quote = isKotlin(cu) ? "\"" : "'";
        Statement block = parseStatement(cu, "spotbugs {\n    toolVersion = " + quote + version + quote + "\n}", ctx);
        if (!(block instanceof J.MethodInvocation)) {
            return cu;
        }
        J.Block parsedBody = body((J.MethodInvocation) block);
        List<Statement> statements = topLevelStatements(cu);
        if (statements == null) {
            return cu;
        }
        for (Statement statement : statements) {
            if (isTopLevelBlock(statement, "spotbugs")) {
                return withTopLevelStatements(cu, s -> ListUtils.map(s, existing -> existing == statement ?
                        appendToBlock((J.MethodInvocation) existing, parsedBody) : existing));
            }
        }
        Statement newBlock = block.withPrefix(Space.format(statements.isEmpty() ? "" : "\n\n"));
        return withTopLevelStatements(cu, s -> ListUtils.<Statement>concat(s, newBlock));
    }

    private static J.MethodInvocation appendToBlock(J.MethodInvocation spotbugs, J.Block parsedBody) {
        J.Block existingBody = body(spotbugs);
        J.Block newBody;
        if (existingBody.getStatements().isEmpty()) {
            String indent = spotbugs.getPrefix().getIndent();
            newBody = existingBody.withStatements(ListUtils.map(parsedBody.getStatements(),
                            s -> s.withPrefix(Space.format("\n" + indent + "    "))))
                    .withEnd(Space.format("\n" + indent));
        } else {
            Space prefix = existingBody.getStatements().get(0).getPrefix();
            Statement toolVersion = parsedBody.getStatements().get(0).withPrefix(prefix);
            newBody = existingBody.withStatements(ListUtils.concat(existingBody.getStatements(), toolVersion));
        }
        J.Lambda lambda = (J.Lambda) spotbugs.getArguments().get(0);
        return spotbugs.withArguments(ListUtils.map(spotbugs.getArguments(), a -> a == lambda ? lambda.withBody(newBody) : a));
    }

    private static J.Block body(J.MethodInvocation block) {
        return (J.Block) ((J.Lambda) block.getArguments().get(0)).getBody();
    }

    @EqualsAndHashCode(callSuper = false)
    @Value
    private class UpgradeToolVersion extends JavaIsoVisitor<ExecutionContext> {
        DependencyVersionSelector selector;
        AtomicBoolean toolVersionSet;

        @Override
        public J.Assignment visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
            J.Assignment a = super.visitAssignment(assignment, ctx);
            if (namesToolVersion(a.getVariable())) {
                return a.withAssignment(upgrade(a.getAssignment(), ctx));
            }
            return a;
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
            if (m.getArguments().size() != 1) {
                return m;
            }
            boolean setter = "toolVersion".equals(m.getSimpleName()) && m.getSelect() == null && isInsideBlock(getCursor(), "spotbugs");
            boolean propertySet = "set".equals(m.getSimpleName()) && m.getSelect() != null && namesToolVersion(m.getSelect());
            if (setter || propertySet) {
                return m.withArguments(ListUtils.mapFirst(m.getArguments(), arg -> upgrade(arg, ctx)));
            }
            return m;
        }

        private boolean namesToolVersion(Expression expression) {
            if (expression instanceof J.Identifier) {
                return "toolVersion".equals(((J.Identifier) expression).getSimpleName()) && isInsideBlock(getCursor(), "spotbugs");
            }
            if (expression instanceof J.FieldAccess) {
                J.FieldAccess fieldAccess = (J.FieldAccess) expression;
                return "toolVersion".equals(fieldAccess.getSimpleName()) &&
                       (fieldAccess.getTarget() instanceof J.Identifier &&
                        "spotbugs".equals(((J.Identifier) fieldAccess.getTarget()).getSimpleName()) ||
                        isInsideBlock(getCursor(), "spotbugs"));
            }
            return false;
        }

        private Expression upgrade(Expression value, ExecutionContext ctx) {
            toolVersionSet.set(true);
            if (!(value instanceof J.Literal) || !(((J.Literal) value).getValue() instanceof String)) {
                return value;
            }
            try {
                String version = select(selector, (String) ((J.Literal) value).getValue(), ctx);
                return version == null ? value : ChangeStringLiteral.withStringValue((J.Literal) value, version);
            } catch (MavenDownloadingException e) {
                return Markup.warn(value, e);
            }
        }
    }
}
