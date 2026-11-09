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
import org.openrewrite.gradle.IsSettingsGradle;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.SearchResult;

import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.singleton;

@Value
@EqualsAndHashCode(callSuper = false)
public class FindRemovedPlugins extends Recipe {

    private static final Map<String, String> REMOVED = new HashMap<>();

    static {
        REMOVED.put("findbugs", "`findbugs` was removed in Gradle 6: the guide suggests the `com.github.spotbugs` plugin");
        REMOVED.put("osgi", "`osgi` was removed in Gradle 6: the guide suggests a community OSGi plugin");
        REMOVED.put("jdepend", "`jdepend` was removed in Gradle 6: the guide suggests a community analysis plugin");
        REMOVED.put("play", "`play` was removed in Gradle 6: the guide suggests the Play Framework plugin on the plugin portal");
    }

    String displayName = "Find plugins Gradle has removed";

    String description = "The core `findbugs`, `osgi`, `jdepend` and `play` plugins are gone, and applying one fails with `Plugin [id: 'findbugs'] was not found` " +
            "or `Plugin with id 'osgi' not found` on Gradle 8.14 and 9.8. Their replacements configure differently, so moving takes a person. " +
            "Covers `plugins { id .. }` and `apply plugin: ..` in both DSLs. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/current/userguide/upgrading_version_5.html#v5_the_findbugs_plugin_removed) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(Preconditions.or(new IsBuildGradle<>(), new IsSettingsGradle<>()), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                String id = null;
                if ("id".equals(m.getSimpleName()) && m.getSelect() == null && m.getArguments().size() == 1 &&
                        ScriptSearch.isInside(getCursor(), singleton("plugins"))) {
                    id = stringValue(m.getArguments().get(0));
                } else if ("apply".equals(m.getSimpleName()) && m.getSelect() == null) {
                    id = appliedPlugin(m);
                }
                String message = id == null ? null : REMOVED.get(id);
                return message == null ? m : SearchResult.found(m, message);
            }

            private @Nullable String appliedPlugin(J.MethodInvocation m) {
                for (Expression argument : m.getArguments()) {
                    if (argument instanceof G.MapEntry && "plugin".equals(stringValue(((G.MapEntry) argument).getKey()))) {
                        return stringValue(((G.MapEntry) argument).getValue());
                    }
                    if (argument instanceof J.Assignment && ((J.Assignment) argument).getVariable() instanceof J.Identifier &&
                            "plugin".equals(((J.Identifier) ((J.Assignment) argument).getVariable()).getSimpleName())) {
                        return stringValue(((J.Assignment) argument).getAssignment());
                    }
                }
                return null;
            }

            private @Nullable String stringValue(Expression expression) {
                return expression instanceof J.Literal && ((J.Literal) expression).getValue() instanceof String ?
                        (String) ((J.Literal) expression).getValue() : null;
            }
        });
    }
}
