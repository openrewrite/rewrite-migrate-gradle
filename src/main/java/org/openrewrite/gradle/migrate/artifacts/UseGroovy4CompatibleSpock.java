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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Arrays.asList;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseGroovy4CompatibleSpock extends Recipe {

    private static final String SPOCK_GROUP = "org.spockframework";
    private static final Set<String> GROOVY_GROUPS =
            new HashSet<>(asList("org.codehaus.groovy", "org.apache.groovy"));
    private static final Set<String> PLUGIN_DEVELOPMENT_PLUGINS =
            new HashSet<>(asList("java-gradle-plugin", "org.gradle.java-gradle-plugin"));

    /** A Spock version with the Groovy variant it was built against, such as {@code 2.0-groovy-3.0}. */
    private static final Pattern VARIANT = Pattern.compile("^(\\d+\\.\\d+(?:[.\\-][\\w.]+?)??)-groovy-(\\d+\\.\\d+)$");

    /** Gradle 9 compiles with Groovy 4, so that is the variant to be on. */
    private static final String GROOVY = "4.0";
    /** The first Spock release with a Groovy 4 variant. Below it, there is nothing to switch to. */
    private static final int FIRST_MAJOR = 2;
    private static final int FIRST_MINOR = 2;
    /** The newest Spock release with a Groovy 4 variant, for a version that has none of its own. */
    private static final String NEWEST = "2.5";

    final String displayName = "Use a Groovy 4 compatible Spock";

    final String description = "Gradle 9 compiles Groovy with Groovy 4, where Gradle 8 used Groovy 3. A Gradle " +
            "plugin project that takes Groovy from Gradle rather than declaring its own, which is what " +
            "`java-gradle-plugin` projects do, then compiles its Spock specifications against Groovy 4, and a Spock " +
            "built for an earlier Groovy refuses to run: \"The Spock compiler plugin cannot execute because Spock " +
            "2.0.0-groovy-3.0 is not compatible with Groovy 4.0.33\". This recipe switches every " +
            "`org.spockframework` dependency of such a project to the Groovy 4 variant of the same Spock version, " +
            "or to " + NEWEST + "-groovy-" + GROOVY + " where that version has no Groovy 4 variant, which is every " +
            "Spock before 2.2. A project that declares its own Groovy is left alone, since its Spock matches that " +
            "Groovy and not Gradle's. Spock 1.x is left alone as well: it has no Groovy 4 variant at all, and " +
            "moving to 2.x is a migration of the tests rather than of the build, since Spock 2 runs on the JUnit " +
            "Platform. A version written as a property or an interpolated string is left alone, because the " +
            "variant cannot be read from it. See the " +
            "[Gradle upgrade guide](https://docs.gradle.org/9.0.0/userguide/upgrading_major_version_9.html).";

    final Duration estimatedEffortPerOccurrence = Duration.ofMinutes(5);

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaIsoVisitor<ExecutionContext>() {

            boolean applies;

            @Override
            public @Nullable J visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof JavaSourceFile) {
                    JavaSourceFile cu = (JavaSourceFile) tree;
                    applies = isPluginProject(cu) && !declaresGroovy(cu);
                    if (!applies) {
                        return cu;
                    }
                }
                return super.visit(tree, ctx);
            }

            @Override
            public J.Literal visitLiteral(J.Literal literal, ExecutionContext ctx) {
                J.Literal l = super.visitLiteral(literal, ctx);
                if (l.getType() != JavaType.Primitive.String || !(l.getValue() instanceof String)) {
                    return l;
                }
                Dependency dependency = DependencyNotation.parse((String) l.getValue());
                if (dependency == null || !SPOCK_GROUP.equals(dependency.getGroupId()) ||
                        dependency.getVersion() == null) {
                    return l;
                }
                String version = groovy4VariantOf(dependency.getVersion());
                return version == null ? l : withVersion(l, dependency.getVersion(), version);
            }

            /** Replaces the version in a string notation, keeping the quotes the script used. */
            private J.Literal withVersion(J.Literal literal, String was, String becomes) {
                String notation = ((String) literal.getValue()).replace(':' + was, ':' + becomes);
                String source = literal.getValueSource();
                return literal
                        .withValue(notation)
                        .withValueSource(source == null ? null : source.replace(':' + was, ':' + becomes));
            }
        });
    }

    /**
     * The Groovy 4 variant to use in place of a Spock version, or null to leave the version alone: it is
     * already on Groovy 4, it is not a variant at all, or it is a Spock too old to have one.
     */
    static @Nullable String groovy4VariantOf(String version) {
        Matcher matcher = VARIANT.matcher(version);
        if (!matcher.matches() || GROOVY.equals(matcher.group(2))) {
            return null;
        }
        String spock = matcher.group(1);
        Matcher number = Pattern.compile("^(\\d+)\\.(\\d+)").matcher(spock);
        if (!number.find()) {
            return null;
        }
        int major = Integer.parseInt(number.group(1));
        int minor = Integer.parseInt(number.group(2));
        if (major < FIRST_MAJOR) {
            // Spock 1.x has no Groovy 4 variant, and 2.x is a migration of the tests, not of the build.
            return null;
        }
        boolean hasOwnVariant = major > FIRST_MAJOR || minor >= FIRST_MINOR;
        return (hasOwnVariant ? spock : NEWEST) + "-groovy-" + GROOVY;
    }

    /** Whether the project takes its Groovy from Gradle, which is what a plugin project does. */
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

    /** Whether the script declares a Groovy of its own, which its Spock variant belongs to and not Gradle's. */
    private static boolean declaresGroovy(JavaSourceFile cu) {
        Set<String> found = new HashSet<>();
        new JavaIsoVisitor<Set<String>>() {
            @Override
            public J.Literal visitLiteral(J.Literal literal, Set<String> declared) {
                if (literal.getType() == JavaType.Primitive.String && literal.getValue() instanceof String) {
                    Dependency dependency = DependencyNotation.parse((String) literal.getValue());
                    if (dependency != null && dependency.getArtifactId() != null &&
                            GROOVY_GROUPS.contains(dependency.getGroupId())) {
                        declared.add(dependency.getGroupId());
                    }
                }
                return literal;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, Set<String> declared) {
                J.MethodInvocation m = super.visitMethodInvocation(method, declared);
                List<Expression> arguments = m.getArguments();
                String group = null;
                for (Expression argument : arguments) {
                    if (argument instanceof J.Assignment) {
                        Expression variable = ((J.Assignment) argument).getVariable();
                        Expression value = ((J.Assignment) argument).getAssignment();
                        if (variable instanceof J.Identifier && "group".equals(((J.Identifier) variable).getSimpleName()) &&
                                value instanceof J.Literal && GROOVY_GROUPS.contains(((J.Literal) value).getValue())) {
                            group = String.valueOf(((J.Literal) value).getValue());
                        }
                    }
                }
                // exclude group: 'org.codehaus.groovy' leaves Gradle's Groovy in place rather than declaring one.
                if (group != null && !"exclude".equals(m.getSimpleName())) {
                    declared.add(group);
                }
                return m;
            }
        }.visit(cu, found);
        return !found.isEmpty();
    }
}
