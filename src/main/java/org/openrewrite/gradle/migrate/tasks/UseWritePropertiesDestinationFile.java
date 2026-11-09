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
package org.openrewrite.gradle.migrate.tasks;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.groovy.GroovyTemplate;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;
import org.openrewrite.marker.Markers;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseWritePropertiesDestinationFile extends Recipe {

    private static final String WRITE_PROPERTIES = "WriteProperties";

    String displayName = "Use `destinationFile` instead of `outputFile` on `WriteProperties` tasks";

    String description = "`WriteProperties.outputFile` is deprecated and removed in Gradle 9, where setting it fails. " +
            "`destinationFile` replaces it. Only assignments directly in a block that names the `WriteProperties` type change. " +
            "A string value is wrapped in `file(..)`, because Gradle 8.14 does not accept a string for `destinationFile`. " +
            "Reads are left alone, since `destinationFile` is a `Provider` rather than a `File`. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/9.0.0/userguide/upgrading_major_version_9.html) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
                J.Assignment a = (J.Assignment) super.visitAssignment(assignment, ctx);
                if (!(a.getVariable() instanceof J.Identifier) ||
                        !"outputFile".equals(((J.Identifier) a.getVariable()).getSimpleName()) ||
                        !isInWritePropertiesBlock()) {
                    return a;
                }
                Expression value = a.getAssignment();
                if (isString(value) && getCursor().firstEnclosing(G.CompilationUnit.class) != null) {
                    return GroovyTemplate.builder("destinationFile = file(#{any()})").build()
                            .apply(getCursor(), a.getCoordinates().replace(), value.withPrefix(Space.EMPTY).withMarkers(Markers.EMPTY));
                }
                return a.withVariable(((J.Identifier) a.getVariable()).withSimpleName("destinationFile"));
            }

            private boolean isString(Expression value) {
                return value instanceof G.GString ||
                        value instanceof J.Literal && ((J.Literal) value).getValue() instanceof String;
            }

            private boolean isInWritePropertiesBlock() {
                Cursor lambda = getCursor().dropParentUntil(v -> v instanceof J.Lambda || v == Cursor.ROOT_VALUE);
                if (lambda.getValue() == Cursor.ROOT_VALUE) {
                    return false;
                }
                J.MethodInvocation owner = lambda.firstEnclosing(J.MethodInvocation.class);
                return owner != null && namesWriteProperties(owner);
            }

            private boolean namesWriteProperties(J.MethodInvocation m) {
                boolean[] found = {false};
                JavaIsoVisitor<Integer> visitor = new JavaIsoVisitor<Integer>() {
                    @Override
                    public J.Identifier visitIdentifier(J.Identifier identifier, Integer p) {
                        found[0] |= WRITE_PROPERTIES.equals(identifier.getSimpleName());
                        return identifier;
                    }
                };
                if (m.getSelect() != null) {
                    visitor.visit(m.getSelect(), 0);
                }
                if (m.getTypeParameters() != null) {
                    m.getTypeParameters().forEach(t -> visitor.visit(t, 0));
                }
                for (Expression argument : m.getArguments()) {
                    if (!(argument instanceof J.Lambda)) {
                        visitor.visit(argument, 0);
                    }
                }
                return found[0];
            }
        });
    }
}
