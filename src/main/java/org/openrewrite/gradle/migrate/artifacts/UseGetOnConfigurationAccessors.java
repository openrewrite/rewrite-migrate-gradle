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
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JContainer;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.kotlin.KotlinIsoVisitor;
import org.openrewrite.marker.Markers;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseGetOnConfigurationAccessors extends Recipe {

    private static final Set<String> CONFIGURATION_MEMBERS = new HashSet<>(Arrays.asList(
            "addToAntBuilder", "all", "allArtifacts", "allDependencies", "allDependencyConstraints", "artifacts",
            "asFileTree", "asPath", "attributes", "buildDependencies", "contains", "copy", "copyRecursive",
            "defaultDependencies", "dependencies", "dependencyConstraints", "description", "exclude", "excludeRules",
            "extendsFrom", "fileCollection", "files", "filter", "getTaskDependencyFromProjectDependency", "hierarchy",
            "incoming", "isCanBeConsumed", "isCanBeResolved", "isEmpty", "isTransitive", "isVisible", "minus", "outgoing",
            "plus", "resolutionStrategy", "resolve", "resolvedConfiguration", "setDescription", "setExtendsFrom",
            "setTransitive", "singleFile", "state", "withDependencies"));

    private static final String CONFIGURATIONS = "configurations";

    String displayName = "Call `get()` on Kotlin DSL configuration accessors";

    String description = "Gradle 8.0 removed the Kotlin DSL extensions that let a script reach the members of a `Configuration` " +
            "directly from a `NamedDomainObjectProvider<Configuration>`, so `configurations.compileClasspath.files` fails to " +
            "compile with `Unresolved reference: files`. This recipe inserts `.get()` for the members the upgrade guide lists. " +
            "Generated accessors don't resolve when a script is parsed without its build's generated code, so the recipe " +
            "matches the shape `configurations.<name>.<member>` rather than the type of `<name>`. Values obtained from " +
            "`by configurations.creating` or `getByName(..)` are already a `Configuration`, and don't have this shape. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/current/userguide/upgrading_version_7.html#v7_configuration_extension_methods_removed_from_kotlin_dsl) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new KotlinIsoVisitor<ExecutionContext>() {
            private Set<String> variables = new HashSet<>();

            @Override
            public J preVisit(J tree, ExecutionContext ctx) {
                if (tree instanceof JavaSourceFile) {
                    variables = declaredVariables(tree);
                }
                return tree;
            }

            @Override
            public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, ExecutionContext ctx) {
                J.FieldAccess f = super.visitFieldAccess(fieldAccess, ctx);
                return CONFIGURATION_MEMBERS.contains(f.getSimpleName()) && isAccessor(f.getTarget()) ?
                        f.withTarget(get(f.getTarget())) : f;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                return CONFIGURATION_MEMBERS.contains(m.getSimpleName()) && isAccessor(m.getSelect()) ?
                        m.withSelect(get(m.getSelect())) : m;
            }

            /**
             * `configurations.compileClasspath`, or `project.configurations.compileClasspath`. A local variable named
             * like the container or like the configuration isn't that accessor.
             */
            private boolean isAccessor(@Nullable Expression expression) {
                if (!(expression instanceof J.FieldAccess)) {
                    return false;
                }
                J.FieldAccess accessor = (J.FieldAccess) expression;
                Expression container = accessor.getTarget();
                if (variables.contains(accessor.getSimpleName()) || !isContainerType(container)) {
                    return false;
                }
                if (container instanceof J.Identifier) {
                    String name = ((J.Identifier) container).getSimpleName();
                    return CONFIGURATIONS.equals(name) && !variables.contains(name);
                }
                if (container instanceof J.FieldAccess) {
                    J.FieldAccess qualified = (J.FieldAccess) container;
                    return CONFIGURATIONS.equals(qualified.getSimpleName()) && qualified.getTarget() instanceof J.Identifier &&
                           "project".equals(((J.Identifier) qualified.getTarget()).getSimpleName());
                }
                return false;
            }

            /**
             * The parser does attribute the container, unlike the generated accessor on it, so use what's known.
             */
            private boolean isContainerType(Expression container) {
                JavaType type = container.getType();
                return type == null || type instanceof JavaType.Unknown ||
                       TypeUtils.isOfClassType(type, "org.gradle.api.artifacts.ConfigurationContainer");
            }

            private J.MethodInvocation get(Expression accessor) {
                return new J.MethodInvocation(
                        Tree.randomId(),
                        accessor.getPrefix(),
                        Markers.EMPTY,
                        JRightPadded.build(accessor.withPrefix(Space.EMPTY)),
                        null,
                        new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, emptyList(), "get", null, null),
                        JContainer.build(Space.EMPTY,
                                singletonList(JRightPadded.build(new J.Empty(Tree.randomId(), Space.EMPTY, Markers.EMPTY))),
                                Markers.EMPTY),
                        null);
            }
        });
    }

    private static Set<String> declaredVariables(J tree) {
        Set<String> names = new HashSet<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable variable, Integer p) {
                names.add(variable.getSimpleName());
                return super.visitVariable(variable, p);
            }
        }.visit(tree, 0);
        return names;
    }
}
