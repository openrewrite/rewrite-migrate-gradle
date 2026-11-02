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
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.java.marker.OmitParentheses;
import org.openrewrite.java.tree.*;
import org.openrewrite.kotlin.KotlinIsoVisitor;
import org.openrewrite.kotlin.marker.By;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.singletonList;
import static org.openrewrite.Tree.randomId;

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
                J.MethodInvocation call = containerCall(variable.getInitializer(), variable.getSimpleName());
                if (call == null) {
                    return vd;
                }
                return vd.withMarkers(vd.getMarkers().removeByType(By.class))
                        .withVariables(singletonList(variable.withInitializer(call)));
            }
        });
    }

    private static J.@Nullable MethodInvocation containerCall(@Nullable Expression delegate, String elementName) {
        if (delegate instanceof J.Identifier) {
            J.Identifier identifier = (J.Identifier) delegate;
            String method = DELEGATE_TO_METHOD.get(identifier.getSimpleName());
            if (method == null) {
                return null;
            }
            return new J.MethodInvocation(randomId(), identifier.getPrefix(), Markers.EMPTY, null, null,
                    rename(identifier.withPrefix(Space.EMPTY), method),
                    JContainer.build(singletonList(JRightPadded.build(stringLiteral(elementName)))), null);
        } else if (delegate instanceof J.FieldAccess) {
            J.FieldAccess fieldAccess = (J.FieldAccess) delegate;
            String method = DELEGATE_TO_METHOD.get(fieldAccess.getSimpleName());
            if (method == null) {
                return null;
            }
            return new J.MethodInvocation(randomId(), fieldAccess.getPrefix(), Markers.EMPTY,
                    JRightPadded.build(fieldAccess.getTarget()).withAfter(fieldAccess.getPadding().getName().getBefore()),
                    null,
                    rename(fieldAccess.getName(), method),
                    JContainer.build(singletonList(JRightPadded.build(stringLiteral(elementName)))), null);
        } else if (delegate instanceof J.MethodInvocation) {
            J.MethodInvocation invocation = (J.MethodInvocation) delegate;
            String method = DELEGATE_TO_METHOD.get(invocation.getSimpleName());
            if (method == null || invocation.getTypeParameters() != null) {
                return null;
            }
            return elementCall(invocation, method, elementName);
        }
        return null;
    }

    private static J.@Nullable MethodInvocation elementCall(J.MethodInvocation delegate, String method, String elementName) {
        JContainer<Expression> arguments = delegate.getPadding().getArguments();
        List<JRightPadded<Expression>> remaining = new ArrayList<>();
        for (JRightPadded<Expression> argument : arguments.getPadding().getElements()) {
            if (!(argument.getElement() instanceof J.Empty)) {
                remaining.add(argument);
            }
        }

        JContainer<Expression> typeParameters = null;
        if (!remaining.isEmpty() && isClassLiteral(remaining.get(0).getElement())) {
            Expression type = ((J.MemberReference) remaining.remove(0).getElement()).getContaining();
            typeParameters = JContainer.build(singletonList(JRightPadded.build(type.withPrefix(Space.EMPTY))));
        }
        if (remaining.size() > 1 || (remaining.size() == 1 && !(remaining.get(0).getElement() instanceof J.Lambda))) {
            return null;
        }

        List<JRightPadded<Expression>> newArguments = new ArrayList<>();
        newArguments.add(JRightPadded.build(stringLiteral(elementName)));
        newArguments.addAll(remaining);
        return delegate
                .withName(rename(delegate.getName(), method))
                .withMethodType(null)
                .getPadding().withTypeParameters(typeParameters)
                .getPadding().withArguments(arguments
                        .withMarkers(arguments.getMarkers().removeByType(OmitParentheses.class))
                        .getPadding().withElements(newArguments));
    }

    private static boolean isClassLiteral(Expression expression) {
        return expression instanceof J.MemberReference &&
                "class".equals(((J.MemberReference) expression).getReference().getSimpleName());
    }

    private static J.Identifier rename(J.Identifier name, String method) {
        return name.withSimpleName(method).withType(null).withFieldType(null);
    }

    private static J.Literal stringLiteral(String value) {
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$");
        return new J.Literal(randomId(), Space.EMPTY, Markers.EMPTY, value, "\"" + escaped + "\"", null, JavaType.Primitive.String);
    }
}
