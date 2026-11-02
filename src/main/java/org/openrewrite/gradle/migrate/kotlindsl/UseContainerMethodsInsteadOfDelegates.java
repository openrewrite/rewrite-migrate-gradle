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
package org.openrewrite.gradle.migrate.kotlindsl;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.kotlin.KotlinIsoVisitor;
import org.openrewrite.kotlin.KotlinTemplate;
import org.openrewrite.kotlin.marker.By;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.singletonList;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseContainerMethodsInsteadOfDelegates extends Recipe {

    private static final Map<String, String> DELEGATE_TO_METHOD = new HashMap<>();

    static {
        DELEGATE_TO_METHOD.put("registering", "register");
        DELEGATE_TO_METHOD.put("creating", "create");
        DELEGATE_TO_METHOD.put("existing", "named");
        DELEGATE_TO_METHOD.put("getting", "getByName");
    }

    String displayName = "Use container methods instead of Kotlin DSL delegated properties";

    String description = "Gradle 9.6 deprecates the Kotlin DSL container delegates `by registering`, `by creating`, `by existing` and `by getting`, " +
            "scheduled for removal in Gradle 10. Replace them with explicit calls to `register`, `create`, `named` and `getByName`, " +
            "passing the property name as the element name and a `Type::class` argument as a type argument. " +
            "This applies to any named container, such as `tasks`, `configurations` and `sourceSets`. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/9.8.2/userguide/upgrading_version_9.html#kotlin_dsl_delegated_properties) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new KotlinIsoVisitor<ExecutionContext>() {
            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable, ExecutionContext ctx) {
                J.VariableDeclarations vd = super.visitVariableDeclarations(multiVariable, ctx);
                if (!vd.getMarkers().findFirst(By.class).isPresent() || vd.getVariables().size() != 1) {
                    return vd;
                }
                J.VariableDeclarations.NamedVariable variable = vd.getVariables().get(0);
                Expression initializer = variable.getInitializer();
                Delegate delegate = initializer == null ? null : Delegate.from(initializer);
                if (delegate == null) {
                    return vd;
                }
                Cursor initializerCursor = new Cursor(new Cursor(getCursor(), variable), initializer);
                return vd.withMarkers(vd.getMarkers().removeByType(By.class))
                        .withVariables(singletonList(variable.withInitializer(
                                delegate.toContainerCall(initializerCursor, variable.getSimpleName()))));
            }
        });
    }

    @Value
    private static class Delegate {
        @Nullable Expression container;
        String method;
        @Nullable Expression type;
        @Nullable JRightPadded<Expression> configuration;

        static @Nullable Delegate from(Expression initializer) {
            if (initializer instanceof J.Identifier) {
                return from(null, (J.Identifier) initializer, null, null);
            } else if (initializer instanceof J.FieldAccess) {
                J.FieldAccess fieldAccess = (J.FieldAccess) initializer;
                return from(fieldAccess.getTarget(), fieldAccess.getName(), null, null);
            } else if (initializer instanceof J.MethodInvocation) {
                J.MethodInvocation invocation = (J.MethodInvocation) initializer;
                if (invocation.getTypeParameters() != null) {
                    return null;
                }
                List<JRightPadded<Expression>> arguments = new ArrayList<>();
                for (JRightPadded<Expression> argument : invocation.getPadding().getArguments().getPadding().getElements()) {
                    if (!(argument.getElement() instanceof J.Empty)) {
                        arguments.add(argument);
                    }
                }
                Expression type = null;
                if (!arguments.isEmpty() && isClassLiteral(arguments.get(0).getElement())) {
                    type = ((J.MemberReference) arguments.remove(0).getElement()).getContaining();
                }
                if (arguments.size() > 1 || (arguments.size() == 1 && !(arguments.get(0).getElement() instanceof J.Lambda))) {
                    return null;
                }
                return from(invocation.getSelect(), invocation.getName(), type, arguments.isEmpty() ? null : arguments.get(0));
            }
            return null;
        }

        private static @Nullable Delegate from(@Nullable Expression container, J.Identifier name,
                                               @Nullable Expression type, @Nullable JRightPadded<Expression> configuration) {
            String method = DELEGATE_TO_METHOD.get(name.getSimpleName());
            return method == null ? null : new Delegate(container, method, type, configuration);
        }

        private static boolean isClassLiteral(Expression expression) {
            return expression instanceof J.MemberReference &&
                    "class".equals(((J.MemberReference) expression).getReference().getSimpleName());
        }

        J.MethodInvocation toContainerCall(Cursor initializerCursor, String elementName) {
            String typeArgument = type == null ? "" : "<" + type.printTrimmed(initializerCursor) + ">";
            String escapedName = elementName.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$");
            String call = method + typeArgument + "(\"" + escapedName + "\")";
            Expression initializer = initializerCursor.getValue();
            J.MethodInvocation containerCall = container == null ?
                    KotlinTemplate.apply(call, initializerCursor, initializer.getCoordinates().replace()) :
                    KotlinTemplate.apply("#{any()}." + call, initializerCursor, initializer.getCoordinates().replace(), container);
            if (configuration == null) {
                return containerCall;
            }
            List<JRightPadded<Expression>> arguments = new ArrayList<>(containerCall.getPadding().getArguments().getPadding().getElements());
            arguments.add(configuration);
            return containerCall.getPadding().withArguments(
                    containerCall.getPadding().getArguments().getPadding().withElements(arguments));
        }
    }
}
