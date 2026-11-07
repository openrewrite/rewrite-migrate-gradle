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
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.FindSourceFiles;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.UsePropertyAssignmentSyntax;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseAssignmentForRepositoryUrl extends Recipe {

    private static final Set<String> REPOSITORY_TYPES = new HashSet<>(Arrays.asList("maven", "ivy"));
    private static final String REPOSITORIES = "repositories";

    String displayName = "Use `=` assignment syntax for a repository's `url`";

    String description = "Setting a property with the Gradle-generated `propName value` syntax is deprecated since Gradle " +
            "8.14 and scheduled for removal in Gradle 10. This recipe turns `url 'x'` into `url = 'x'` directly inside a " +
            "`maven { }` or `ivy { }` block of `repositories`, in Groovy scripts. It stays out of every other block: a " +
            "`url` in a builder closure such as `pom.withXml { asNode().children().last() + { url 'x' } }` is a call that " +
            "adds an XML node, and fails as an assignment. Kotlin has no such syntax. " +
            "See the [Gradle 8.14 upgrade guide](https://docs.gradle.org/8.14/userguide/upgrading_version_8.html#groovy_space_assignment_syntax) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        TreeVisitor<?, ExecutionContext> assignment = new UsePropertyAssignmentSyntax("url").getVisitor();
        return Preconditions.check(new FindSourceFiles("**/*.gradle"), new JavaVisitor<ExecutionContext>() {
            @Override
            public @Nullable J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
                if (!"url".equals(m.getSimpleName()) || m.getSelect() != null || !isDirectlyInRepository(getCursor())) {
                    return m;
                }
                J assigned = (J) assignment.visit(m, ctx, getCursor().getParentOrThrow());
                return assigned instanceof J.Assignment ? assigned : m;
            }
        });
    }

    private static boolean isDirectlyInRepository(Cursor cursor) {
        Iterator<Object> enclosing = cursor.getParentOrThrow().getPath(J.MethodInvocation.class::isInstance);
        if (!enclosing.hasNext()) {
            return false;
        }
        J.MethodInvocation repository = (J.MethodInvocation) enclosing.next();
        if (!REPOSITORY_TYPES.contains(repository.getSimpleName())) {
            return false;
        }
        boolean inRepositories = isRepositories(repository.getSelect());
        while (enclosing.hasNext()) {
            J.MethodInvocation outer = (J.MethodInvocation) enclosing.next();
            // The legacy `maven` plugin's upload configuration is migrated on its own
            if ("uploadArchives".equals(outer.getSimpleName())) {
                return false;
            }
            inRepositories |= REPOSITORIES.equals(outer.getSimpleName());
        }
        return inRepositories;
    }

    private static boolean isRepositories(@Nullable Expression select) {
        if (select instanceof J.Identifier) {
            return REPOSITORIES.equals(((J.Identifier) select).getSimpleName());
        }
        return select instanceof J.FieldAccess && REPOSITORIES.equals(((J.FieldAccess) select).getSimpleName());
    }
}
