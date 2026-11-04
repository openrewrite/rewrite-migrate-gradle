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

import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.gradle.marker.GradleDependencyConfiguration;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.gradle.trait.GradlePlugin;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.maven.tree.Dependency;
import org.openrewrite.maven.tree.DependencyNotation;
import org.openrewrite.maven.tree.ResolvedDependency;
import org.openrewrite.properties.search.FindProperties;
import org.openrewrite.properties.tree.Properties;
import org.openrewrite.semver.LatestRelease;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static java.util.Arrays.asList;

public class RemoveExplicitKotlinStdlibDependency extends ScanningRecipe<Set<String>> {

    private static final String KOTLIN_GROUP = "org.jetbrains.kotlin";
    private static final String DEFAULT_DEPENDENCY_PROPERTY = "kotlin.stdlib.default.dependency";

    /** The first Kotlin Gradle plugin that adds the standard library itself. */
    private static final String FIRST_PLUGIN_ADDING_STDLIB = "1.4.0";

    private static final Set<String> STDLIB_ARTIFACTS = new HashSet<>(asList(
            "kotlin-stdlib", "kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8", "kotlin-stdlib-common"));

    private static final Set<String> KOTLIN_PLUGINS = new HashSet<>(asList(
            "org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.android", "org.jetbrains.kotlin.multiplatform",
            "org.jetbrains.kotlin.js", "kotlin", "kotlin-android", "kotlin-multiplatform"));

    @Getter
    final String displayName = "Remove explicit Kotlin standard library dependency";

    @Getter
    final String description = "The Kotlin Gradle plugin adds the version of `kotlin-stdlib` that matches itself, " +
            "so declaring the dependency explicitly is redundant. Removes `org.jetbrains.kotlin:kotlin-stdlib` and " +
            "its `-jdk7`, `-jdk8` and `-common` variants, written either as coordinates or with the Kotlin DSL " +
            "`kotlin(\"stdlib\")` helper, from a project that applies a Kotlin plugin. Only `implementation` " +
            "declarations are removed, because that is the one configuration the Kotlin plugin contributes the " +
            "standard library to: a declaration on `api` is consumer-visible through the published compile scope, " +
            "and one on a configuration of the project's own has nothing putting it back. A declaration is kept " +
            "when it pins a version newer than the plugin's, since removing it would quietly downgrade the " +
            "standard library; when the plugin predates 1.4, the first version that adds the standard library " +
            "itself; and when `kotlin.stdlib.default.dependency=false` turns that default off, leaving the " +
            "declaration as the only thing providing it. See the " +
            "[Gradle best practice](https://docs.gradle.org/current/userguide/best_practices_dependencies.html#dont_depend_on_kotlin_stdlib).";

    @Getter
    final Duration estimatedEffortPerOccurrence = Duration.ofMinutes(2);

    @Override
    public Set<String> getInitialValue(ExecutionContext ctx) {
        return new HashSet<>();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Set<String> projectsWithoutDefaultStdlib) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof Properties.File &&
                        ((Properties.File) tree).getSourcePath().endsWith("gradle.properties")) {
                    Properties.File properties = (Properties.File) tree;
                    for (Properties.Entry entry : FindProperties.find(properties, DEFAULT_DEPENDENCY_PROPERTY, false)) {
                        if ("false".equals(entry.getValue().getText().trim())) {
                            projectsWithoutDefaultStdlib.add(directoryOf(properties.getSourcePath()));
                        }
                    }
                }
                return tree;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Set<String> projectsWithoutDefaultStdlib) {
        return Preconditions.check(new IsBuildGradle<>(), new JavaIsoVisitor<ExecutionContext>() {

            @Nullable
            String kotlinPluginVersion;

            @Override
            public @Nullable J visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof JavaSourceFile) {
                    JavaSourceFile cu = (JavaSourceFile) tree;
                    if (!appliesKotlinPlugin(cu) || defaultStdlibDisabledFor(cu.getSourcePath()) ||
                            kotlinPluginVersion != null && isOlder(kotlinPluginVersion, FIRST_PLUGIN_ADDING_STDLIB)) {
                        return cu;
                    }
                }
                return super.visit(tree, ctx);
            }

            @Override
            public J.@Nullable Return visitReturn(J.Return return_, ExecutionContext ctx) {
                J.Return r = super.visitReturn(return_, ctx);
                // Groovy wraps a closure's final statement in an implicit return
                return return_.getExpression() != null && r.getExpression() == null ? null : r;
            }

            @Override
            public J.@Nullable MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                return isRedundantStdlib(m) ? null : m;
            }

            private boolean isRedundantStdlib(J.MethodInvocation m) {
                // A declaration carrying a closure, such as one with exclusions, has more than just the notation
                if (!"implementation".equals(m.getSimpleName()) || m.getArguments().size() != 1 ||
                        !inDependenciesBlock()) {
                    return false;
                }
                Expression argument = m.getArguments().get(0);
                if (argument instanceof J.MethodInvocation &&
                        "kotlin".equals(((J.MethodInvocation) argument).getSimpleName())) {
                    J.MethodInvocation kotlin = (J.MethodInvocation) argument;
                    if (kotlin.getArguments().isEmpty() || !(kotlin.getArguments().get(0) instanceof J.Literal) ||
                            !isStdlibShorthand(((J.Literal) kotlin.getArguments().get(0)).getValue())) {
                        return false;
                    }
                    if (kotlin.getArguments().size() == 1) {
                        return true;
                    }
                    Expression version = kotlin.getArguments().get(1);
                    return kotlin.getArguments().size() == 2 && version instanceof J.Literal &&
                            ((J.Literal) version).getValue() instanceof String &&
                            notNewerThanPlugin((String) ((J.Literal) version).getValue());
                }
                if (argument instanceof J.Literal && ((J.Literal) argument).getValue() instanceof String) {
                    Dependency dependency = DependencyNotation.parse((String) ((J.Literal) argument).getValue());
                    return dependency != null && KOTLIN_GROUP.equals(dependency.getGroupId()) &&
                            STDLIB_ARTIFACTS.contains(dependency.getArtifactId()) &&
                            dependency.getClassifier() == null &&
                            (dependency.getVersion() == null || notNewerThanPlugin(dependency.getVersion()));
                }
                return false;
            }

            private boolean isStdlibShorthand(@Nullable Object name) {
                return name instanceof String && STDLIB_ARTIFACTS.contains("kotlin-" + name);
            }

            private boolean notNewerThanPlugin(String version) {
                return kotlinPluginVersion != null && !isOlder(kotlinPluginVersion, version);
            }

            private boolean inDependenciesBlock() {
                J.MethodInvocation enclosing =
                        getCursor().getParentTreeCursor().firstEnclosing(J.MethodInvocation.class);
                if (enclosing == null || !"dependencies".equals(enclosing.getSimpleName())) {
                    return false;
                }
                return getCursor().getPathAsStream().noneMatch(p -> p instanceof J.MethodInvocation &&
                        ("buildscript".equals(((J.MethodInvocation) p).getSimpleName()) ||
                                "constraints".equals(((J.MethodInvocation) p).getSimpleName())));
            }

            private boolean appliesKotlinPlugin(JavaSourceFile cu) {
                kotlinPluginVersion = null;
                Set<String> declared = new HashSet<>();
                new GradlePlugin.Matcher().asVisitor(plugin -> {
                    if (plugin.getPluginId() != null && KOTLIN_PLUGINS.contains(plugin.getPluginId())) {
                        declared.add(plugin.getPluginId());
                        if (plugin.getVersion() != null) {
                            kotlinPluginVersion = plugin.getVersion();
                        }
                    }
                    return plugin.getTree();
                }).visit(cu, 0);

                GradleProject gradleProject = cu.getMarkers().findFirst(GradleProject.class).orElse(null);
                if (gradleProject != null) {
                    if (kotlinPluginVersion == null) {
                        GradleDependencyConfiguration classpath =
                                gradleProject.getBuildscript().getConfiguration("classpath");
                        ResolvedDependency plugin = classpath == null ? null :
                                classpath.findResolvedDependency(KOTLIN_GROUP, "kotlin-gradle-plugin");
                        if (plugin != null) {
                            kotlinPluginVersion = plugin.getVersion();
                        }
                    }
                    if (gradleProject.getPlugins().stream().anyMatch(p -> KOTLIN_PLUGINS.contains(p.getId()))) {
                        return true;
                    }
                }
                return !declared.isEmpty();
            }

            private boolean defaultStdlibDisabledFor(Path buildFile) {
                String project = directoryOf(buildFile);
                for (String disabled : projectsWithoutDefaultStdlib) {
                    if (disabled.isEmpty() || project.equals(disabled) || project.startsWith(disabled + "/")) {
                        return true;
                    }
                }
                return false;
            }
        });
    }

    private static boolean isOlder(String version, String than) {
        return new LatestRelease(null).compare(null, version, than) < 0;
    }

    private static String directoryOf(Path path) {
        Path parent = path.getParent();
        return parent == null ? "" : parent.toString().replace('\\', '/');
    }
}
