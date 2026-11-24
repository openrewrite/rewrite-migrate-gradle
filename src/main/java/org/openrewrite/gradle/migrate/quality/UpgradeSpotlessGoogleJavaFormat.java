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
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;
import org.openrewrite.marker.Markup;
import org.openrewrite.maven.MavenDownloadingException;
import org.openrewrite.maven.table.MavenMetadataFailures;
import org.openrewrite.maven.tree.GroupArtifactVersion;
import org.openrewrite.semver.LatestRelease;

import static java.util.Collections.singletonList;
import static org.openrewrite.Tree.randomId;
import static org.openrewrite.gradle.migrate.internal.BuildScripts.*;

@Value
@EqualsAndHashCode(callSuper = false)
public class UpgradeSpotlessGoogleJavaFormat extends Recipe {

    /// The first Spotless Gradle plugin release whose default google-java-format runs on Java 25.
    private static final String FIRST_PLUGIN_WITH_CURRENT_DEFAULT = "8.0.0";

    @Option(displayName = "New version",
            description = "An exact version or node-style semver selector for google-java-format.",
            example = "1.27.x")
    String newVersion;

    String displayName = "Upgrade google-java-format in Spotless";

    String description = "Raise a literal google-java-format version passed to Spotless' `googleJavaFormat(..)` to `newVersion`, " +
            "preserving newer versions. Where a build script declares a Spotless plugin older than 8.0.0 and calls " +
            "`googleJavaFormat()` without a version, pin one, since the default of those plugin versions fails on Java 25.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        MavenMetadataFailures metadataFailures = new MavenMetadataFailures(this);
        return Preconditions.check(new IsBuildGradle<>(), new JavaIsoVisitor<ExecutionContext>() {
            @Nullable
            DependencyVersionSelector selector;

            boolean pinDefault;

            @Override
            public @Nullable J visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof JavaSourceFile) {
                    JavaSourceFile cu = (JavaSourceFile) tree;
                    selector = new DependencyVersionSelector(metadataFailures,
                            cu.getMarkers().findFirst(GradleProject.class).orElse(null), null);
                    String pluginVersion = declaredPluginVersion(cu, "com.diffplug.spotless", false);
                    pinDefault = pluginVersion != null &&
                                 new LatestRelease(null).compare(null, pluginVersion, FIRST_PLUGIN_WITH_CURRENT_DEFAULT) < 0;
                }
                return super.visit(tree, ctx);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                if (!"googleJavaFormat".equals(m.getSimpleName()) || m.getSelect() != null ||
                    !isInsideBlock(getCursor(), "spotless") || selector == null) {
                    return m;
                }
                try {
                    if (m.getArguments().size() == 1 && m.getArguments().get(0) instanceof J.Empty) {
                        if (!pinDefault) {
                            return m;
                        }
                        String version = select("0", ctx);
                        if (version == null) {
                            return m;
                        }
                        String quote = isKotlin(getCursor().firstEnclosingOrThrow(JavaSourceFile.class)) ? "\"" : "'";
                        return m.withArguments(singletonList(new J.Literal(randomId(), Space.EMPTY, Markers.EMPTY,
                                version, quote + version + quote, null, JavaType.Primitive.String)));
                    }
                    if (m.getArguments().size() == 1 && m.getArguments().get(0) instanceof J.Literal &&
                        ((J.Literal) m.getArguments().get(0)).getValue() instanceof String) {
                        J.Literal current = (J.Literal) m.getArguments().get(0);
                        String version = select((String) current.getValue(), ctx);
                        if (version != null) {
                            return m.withArguments(singletonList(ChangeStringLiteral.withStringValue(current, version)));
                        }
                    }
                } catch (MavenDownloadingException e) {
                    return Markup.warn(m, e);
                }
                return m;
            }

            private @Nullable String select(String currentVersion, ExecutionContext ctx) throws MavenDownloadingException {
                return selector == null ? null : selector.select(
                        new GroupArtifactVersion("com.google.googlejavaformat", "google-java-format", currentVersion),
                        null, newVersion, (String) null, ctx);
            }
        });
    }
}
