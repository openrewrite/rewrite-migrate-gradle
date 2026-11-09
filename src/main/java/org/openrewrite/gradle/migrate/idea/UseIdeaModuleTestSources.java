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
package org.openrewrite.gradle.migrate.idea;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.groovy.GroovyTemplate;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.kotlin.KotlinTemplate;
import org.openrewrite.kotlin.tree.K;

import java.util.Iterator;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseIdeaModuleTestSources extends Recipe {

    private static final String TEST_SOURCE_DIRS = "testSourceDirs";
    private static final String TEST_RESOURCE_DIRS = "testResourceDirs";

    String displayName = "Use `testSources` and `testResources` on the IDEA module";

    String description = "`IdeaModule.testSourceDirs` and `testResourceDirs` are deprecated and removed in Gradle 9, where using them " +
            "fails. `testSources` and `testResources` are file collections, so adding becomes `from(..)` and replacing becomes " +
            "`setFrom(..)`. Only writes inside `idea { module { } }` or through `idea.module` change. Reads are left alone, as is " +
            "any assignment that uses the old value other than by adding to it. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/9.0.0/userguide/upgrading_major_version_9.html#removal_of_deprecated_test_source_dirs_and_test_resource_dirs_from_idea_module) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitAssignmentOperation(J.AssignmentOperation operation, ExecutionContext ctx) {
                if (operation.getOperator() == J.AssignmentOperation.Type.Addition) {
                    J replaced = replace(operation, operation.getVariable(), "from", operation.getAssignment());
                    if (replaced != null) {
                        return replaced;
                    }
                }
                return super.visitAssignmentOperation(operation, ctx);
            }

            @Override
            public J visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
                Expression value = assignment.getAssignment();
                String property = propertyName(assignment.getVariable());
                J replaced = null;
                if (property != null) {
                    if (value instanceof J.Binary && ((J.Binary) value).getOperator() == J.Binary.Type.Addition &&
                            property.equals(propertyName(((J.Binary) value).getLeft())) &&
                            !mentions(((J.Binary) value).getRight(), property)) {
                        replaced = replace(assignment, assignment.getVariable(), "from", ((J.Binary) value).getRight());
                    } else if (!mentions(value, property)) {
                        replaced = replace(assignment, assignment.getVariable(), "setFrom", value);
                    }
                }
                return replaced != null ? replaced : super.visitAssignment(assignment, ctx);
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                if (("add".equals(method.getSimpleName()) || "addAll".equals(method.getSimpleName())) &&
                        method.getSelect() != null && method.getArguments().size() == 1 && !(method.getArguments().get(0) instanceof J.Lambda)) {
                    J replaced = replace(method, method.getSelect(), "from", method.getArguments().get(0));
                    if (replaced != null) {
                        return replaced;
                    }
                }
                return super.visitMethodInvocation(method, ctx);
            }

            private @Nullable J replace(Statement statement, Expression variable, String method, Expression value) {
                String property = propertyName(variable);
                if (property == null || !isOnIdeaModule(variable)) {
                    return null;
                }
                String collection = TEST_SOURCE_DIRS.equals(property) ? "testSources" : "testResources";
                String receiver = variable instanceof J.FieldAccess ? ((J.FieldAccess) variable).getTarget().printTrimmed(getCursor()) + "." : "";
                String snippet = receiver + collection + "." + method + "(#{any()})";
                boolean kotlin = getCursor().firstEnclosing(JavaSourceFile.class) instanceof K.CompilationUnit;
                JavaTemplate template = kotlin ? KotlinTemplate.builder(snippet).build() : GroovyTemplate.builder(snippet).build();
                return template.apply(getCursor(), statement.getCoordinates().replace(), value.withPrefix(Space.EMPTY));
            }

            private boolean isOnIdeaModule(Expression variable) {
                if (variable instanceof J.FieldAccess) {
                    Expression target = ((J.FieldAccess) variable).getTarget();
                    if (target instanceof J.FieldAccess) {
                        J.FieldAccess module = (J.FieldAccess) target;
                        return "module".equals(module.getSimpleName()) && module.getTarget() instanceof J.Identifier &&
                                "idea".equals(((J.Identifier) module.getTarget()).getSimpleName());
                    }
                    return target instanceof J.Identifier && "module".equals(((J.Identifier) target).getSimpleName()) &&
                            isInside(getCursor(), "idea");
                }
                return isInside(getCursor(), "module") && isInside(getCursor(), "idea");
            }

            private boolean isInside(Cursor cursor, String name) {
                Iterator<Cursor> path = cursor.getPathAsCursors(c -> c.getValue() instanceof J.MethodInvocation);
                while (path.hasNext()) {
                    J.MethodInvocation m = path.next().getValue();
                    if (name.equals(m.getSimpleName()) || m.getSelect() instanceof J.Identifier && name.equals(((J.Identifier) m.getSelect()).getSimpleName())) {
                        return true;
                    }
                }
                return false;
            }
        });
    }

    private static @Nullable String propertyName(@Nullable Expression variable) {
        String name = null;
        if (variable instanceof J.Identifier) {
            name = ((J.Identifier) variable).getSimpleName();
        } else if (variable instanceof J.FieldAccess) {
            name = ((J.FieldAccess) variable).getSimpleName();
        }
        return TEST_SOURCE_DIRS.equals(name) || TEST_RESOURCE_DIRS.equals(name) ? name : null;
    }

    private static boolean mentions(J tree, String name) {
        boolean[] found = {false};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, Integer p) {
                found[0] |= name.equals(identifier.getSimpleName());
                return identifier;
            }
        }.visit(tree, 0);
        return found[0];
    }
}
