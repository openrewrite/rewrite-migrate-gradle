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
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.gradle.trait.GradlePlugin;
import org.openrewrite.groovy.GroovyTemplate;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.maven.tree.Dependency;
import org.openrewrite.maven.tree.DependencyNotation;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.Arrays.asList;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseLocalGroovy extends Recipe {

    private static final Set<String> GROOVY_GROUPS =
            new HashSet<>(asList("org.codehaus.groovy", "org.apache.groovy"));
    /** The whole Groovy distribution, which is what {@code localGroovy()} is. A module of it is not. */
    private static final Set<String> GROOVY_DISTRIBUTIONS = new HashSet<>(asList("groovy", "groovy-all"));
    private static final Set<String> PLUGIN_DEVELOPMENT_PLUGINS =
            new HashSet<>(asList("java-gradle-plugin", "org.gradle.java-gradle-plugin"));
    /** The Groovy major Gradle 9 brings in. A declaration of this major does not conflict with it. */
    private static final int GRADLES_GROOVY_MAJOR = 4;

    final String displayName = "Use `localGroovy()` in a Gradle plugin project";

    final String description = "A Gradle plugin project compiles against the Gradle API, which brings Gradle's own " +
            "Groovy with it, so a Groovy declared in the build as well is redundant. Where the two are different " +
            "majors it is worse than redundant: Gradle 9 brings Groovy 4, and a project that declares Groovy 2 or 3 " +
            "fails to compile with \"Conflicting module versions. Module [groovy-nio] is loaded in version 2.5.4 and " +
            "you are trying to load version 4.0.33\". This recipe replaces such a declaration with `localGroovy()`, " +
            "which is Gradle's own Groovy named explicitly, in a project that applies `java-gradle-plugin`. A " +
            "declaration of Groovy " + GRADLES_GROOVY_MAJOR + " or later is left alone, since it does not conflict, " +
            "and so is a project that is not a plugin project, where Gradle's Groovy is not on the classpath to " +
            "begin with and `localGroovy()` would pin the project to whichever Gradle runs it. A module of the " +
            "distribution, such as `groovy-json`, is left alone as well: `localGroovy()` is the whole distribution " +
            "and swapping one module for it would widen what the project depends on. See the " +
            "[Gradle userguide](https://docs.gradle.org/current/userguide/custom_plugins.html).";

    final Duration estimatedEffortPerOccurrence = Duration.ofMinutes(5);

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public @Nullable J visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof JavaSourceFile && !isPluginProject((JavaSourceFile) tree)) {
                    return (J) tree;
                }
                return super.visit(tree, ctx);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                List<Expression> arguments = m.getArguments();
                if (arguments.isEmpty() || !inDependenciesBlock()) {
                    return m;
                }
                Expression notation = arguments.get(0);
                if (!isConflictingGroovy(notation)) {
                    return m;
                }
                // Replacing the argument through the template parenthesizes the call, where the Groovy
                // declaration omits the parentheses, so only the argument of this call is swapped and the
                // container that remembers they were omitted is kept as it is.
                J.MethodInvocation localGroovy = (J.MethodInvocation) GroovyTemplate.builder("localGroovy()")
                        .build()
                        .apply(getCursor(), m.getCoordinates().replace());
                return m.withArguments(ListUtils.mapFirst(m.getArguments(),
                        argument -> localGroovy
                                .withPrefix(argument.getPrefix())
                                .withMarkers(argument.getMarkers())));
            }

            private boolean inDependenciesBlock() {
                J.MethodInvocation enclosing =
                        getCursor().getParentTreeCursor().firstEnclosing(J.MethodInvocation.class);
                if (enclosing == null || !"dependencies".equals(enclosing.getSimpleName())) {
                    return false;
                }
                // A buildscript or constraints block is about resolving the build itself, not compiling it.
                return getCursor().getPathAsStream().noneMatch(p -> p instanceof J.MethodInvocation &&
                        ("buildscript".equals(((J.MethodInvocation) p).getSimpleName()) ||
                                "constraints".equals(((J.MethodInvocation) p).getSimpleName())));
            }
        });
    }

    /** Whether a dependency notation names a Groovy distribution of a major Gradle 9 does not bring. */
    static boolean isConflictingGroovy(Expression notation) {
        if (!(notation instanceof J.Literal) || ((J.Literal) notation).getType() != JavaType.Primitive.String ||
                !(((J.Literal) notation).getValue() instanceof String)) {
            return false;
        }
        Dependency dependency = DependencyNotation.parse((String) ((J.Literal) notation).getValue());
        if (dependency == null || !GROOVY_GROUPS.contains(dependency.getGroupId()) ||
                !GROOVY_DISTRIBUTIONS.contains(dependency.getArtifactId()) || dependency.getVersion() == null) {
            return false;
        }
        int dot = dependency.getVersion().indexOf('.');
        try {
            String major = dot < 0 ? dependency.getVersion() : dependency.getVersion().substring(0, dot);
            return Integer.parseInt(major.trim()) < GRADLES_GROOVY_MAJOR;
        } catch (NumberFormatException e) {
            // A version this cannot read, such as a property, is not one to make a decision from.
            return false;
        }
    }

    /** Whether the project compiles against the Gradle API, which is what brings Gradle's Groovy in. */
    private static boolean isPluginProject(JavaSourceFile cu) {
        GradleProject gradleProject = cu.getMarkers().findFirst(GradleProject.class).orElse(null);
        if (gradleProject != null &&
                gradleProject.getPlugins().stream().anyMatch(p -> PLUGIN_DEVELOPMENT_PLUGINS.contains(p.getId()))) {
            return true;
        }
        Set<String> declared = new HashSet<>();
        new GradlePlugin.Matcher().asVisitor(plugin -> {
            if (PLUGIN_DEVELOPMENT_PLUGINS.contains(plugin.getPluginId())) {
                declared.add(plugin.getPluginId());
            }
            return plugin.getTree();
        }).visit(cu, 0);
        return !declared.isEmpty();
    }
}
