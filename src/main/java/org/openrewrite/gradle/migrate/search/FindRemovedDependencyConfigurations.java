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
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.marker.SearchResult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static java.util.Collections.singleton;

@Value
@EqualsAndHashCode(callSuper = false)
public class FindRemovedDependencyConfigurations extends Recipe {

    private static final Map<String, String> REPLACEMENTS = new HashMap<>();

    static {
        REPLACEMENTS.put("compile", "`api` or `implementation`");
        REPLACEMENTS.put("runtime", "`runtimeOnly`");
        REPLACEMENTS.put("testCompile", "`testImplementation`");
        REPLACEMENTS.put("testRuntime", "`testRuntimeOnly`");
    }

    String displayName = "Find dependencies declared on the removed `compile` and `runtime` configurations";

    String description = "The `compile`, `runtime`, `testCompile` and `testRuntime` configurations no longer exist: on Gradle 8.14 and 9.8 declaring " +
            "a dependency on one fails with `Could not find method compile()` or `Configuration with name 'runtime' not found`. " +
            "`MigrateToGradle6` renames those it can resolve, and what remains needs a choice between `api` and `implementation`. " +
            "A script that declares a configuration of that name itself is skipped.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaIsoVisitor<ExecutionContext>() {
            private Set<String> declared = new HashSet<>();

            @Override
            public J preVisit(J tree, ExecutionContext ctx) {
                if (tree instanceof JavaSourceFile) {
                    declared = findDeclaredConfigurations(tree);
                }
                return tree;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                String configuration = configurationNamed(m);
                if (configuration != null && REPLACEMENTS.containsKey(configuration) && !declared.contains(configuration) &&
                        ScriptSearch.isInside(getCursor(), singleton("dependencies"))) {
                    return SearchResult.found(m, "The `" + configuration + "` configuration no longer exists: use " + REPLACEMENTS.get(configuration));
                }
                return m;
            }

            private @Nullable String configurationNamed(J.MethodInvocation m) {
                Expression select = m.getSelect();
                if (select == null) {
                    return m.getSimpleName();
                }
                // Kotlin's `"compile"(..)`
                if ("<empty>".equals(m.getSimpleName()) && select instanceof J.Literal && ((J.Literal) select).getValue() instanceof String) {
                    return (String) ((J.Literal) select).getValue();
                }
                return null;
            }
        });
    }

    private static Set<String> findDeclaredConfigurations(J tree) {
        Set<String> declared = new HashSet<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, Integer p) {
                boolean configurationsBlock = "configurations".equals(method.getSimpleName()) && method.getSelect() == null;
                boolean onConfigurations = method.getSelect() instanceof J.Identifier &&
                        "configurations".equals(((J.Identifier) method.getSelect()).getSimpleName());
                if (configurationsBlock || onConfigurations) {
                    new JavaIsoVisitor<Integer>() {
                        @Override
                        public J.Identifier visitIdentifier(J.Identifier identifier, Integer q) {
                            declared.add(identifier.getSimpleName());
                            return identifier;
                        }

                        @Override
                        public J.Literal visitLiteral(J.Literal literal, Integer q) {
                            if (literal.getValue() instanceof String) {
                                declared.add((String) literal.getValue());
                            }
                            return literal;
                        }
                    }.visit(method, 0);
                }
                return super.visitMethodInvocation(method, p);
            }
        }.visit(tree, 0);
        return declared;
    }
}
