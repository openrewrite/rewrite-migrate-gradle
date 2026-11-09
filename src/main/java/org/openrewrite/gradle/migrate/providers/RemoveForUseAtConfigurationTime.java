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
package org.openrewrite.gradle.migrate.providers;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

@Value
@EqualsAndHashCode(callSuper = false)
public class RemoveForUseAtConfigurationTime extends Recipe {

    private static final String METHOD_NAME = "forUseAtConfigurationTime";
    private static final String PROVIDER = "org.gradle.api.provider.Provider";

    String displayName = "Remove `Provider.forUseAtConfigurationTime()`";

    String description = "`Provider.forUseAtConfigurationTime()` was deprecated in Gradle 7.4, which treats an external " +
            "value read at configuration time as a configuration cache input without being told, and it is gone from " +
            "Gradle 9: the call still runs on 8.14.4, and fails on 9.8.2. This recipe removes the call and keeps the " +
            "provider it was made on, in Groovy and Kotlin build scripts and in the Java, Kotlin and Groovy sources of " +
            "plugins. See the [Gradle upgrade guide](https://docs.gradle.org/current/userguide/upgrading_version_7.html#for_use_at_configuration_time_deprecation) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaVisitor<ExecutionContext>() {
            @Override
            public @Nullable J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
                Expression provider = m.getSelect();
                if (!METHOD_NAME.equals(m.getSimpleName()) || provider == null || hasArguments(m) || !isOnProvider(m)) {
                    return m;
                }
                if (getCursor().getParentTreeCursor().getValue() instanceof J.Block &&
                    !(provider instanceof J.MethodInvocation)) {
                    // The call is the whole statement and what it was made on cannot stand as one:
                    // `provider;` is not a statement in Java, and in no language is a bare
                    // `J.Identifier` a `Statement`, so leaving one among a block's statements makes
                    // an LST that cannot even be serialized. Groovy gives a closure's trailing
                    // expression a `J.Return` of its own, so this does not reach that.
                    return null;
                }
                return provider.withPrefix(m.getPrefix());
            }
        };
    }

    private static boolean hasArguments(J.MethodInvocation m) {
        return !m.getArguments().isEmpty() && !(m.getArguments().get(0) instanceof J.Empty);
    }

    /**
     * Without a type, as in a build script parsed without the Gradle API, the name alone has to do. It is distinctive
     * enough for that, and a type that does not resolve to a {@code Provider} says the method is someone else's.
     */
    private static boolean isOnProvider(J.MethodInvocation m) {
        JavaType.Method type = m.getMethodType();
        return type == null || TypeUtils.isAssignableTo(PROVIDER, type.getDeclaringType());
    }
}
