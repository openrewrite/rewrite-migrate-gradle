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
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.gradle.IsSettingsGradle;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;

import java.util.List;

import static java.util.Collections.singletonList;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseMavenCentralInsteadOfJcenter extends Recipe {

    private static final String JCENTER = "jcenter";
    private static final String MAVEN_CENTRAL = "mavenCentral";

    String displayName = "Use `mavenCentral()` instead of `jcenter()`";

    String description = "`RepositoryHandler.jcenter()` was deprecated in Gradle 7.0 and removed in Gradle 9.0. The JCenter " +
            "repository has redirected to Maven Central since August 2024, which the Gradle upgrade guide names as its " +
            "closest direct replacement. A plain `jcenter()` is deleted when its `repositories` block already declares a " +
            "plain `mavenCentral()`, and renamed otherwise, so that a repository with a content filter never ends up " +
            "as the only way to reach artifacts it filters out. Build and settings scripts are both covered. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/current/userguide/upgrading_major_version_9.html) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
                Preconditions.or(new IsBuildGradle<>(), new IsSettingsGradle<>()),
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                        J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                        if (JCENTER.equals(m.getSimpleName()) && isRepositoriesReference(m.getSelect())) {
                            // `repositories.jcenter()`, which has no block to look for a `mavenCentral()` in
                            return rename(m);
                        }
                        if (!"repositories".equals(m.getSimpleName()) || m.getArguments().size() != 1 ||
                            !(m.getArguments().get(0) instanceof J.Lambda)) {
                            return m;
                        }
                        J.Lambda lambda = (J.Lambda) m.getArguments().get(0);
                        if (!(lambda.getBody() instanceof J.Block)) {
                            return m;
                        }
                        J.Block body = (J.Block) lambda.getBody();
                        List<Statement> statements = migrate(body.getStatements());
                        return statements == body.getStatements() ? m :
                                m.withArguments(singletonList(lambda.withBody(body.withStatements(statements))));
                    }
                });
    }

    private static List<Statement> migrate(List<Statement> statements) {
        boolean[] hasPlainMavenCentral = {false};
        for (Statement statement : statements) {
            J.MethodInvocation repository = asRepository(statement, MAVEN_CENTRAL);
            hasPlainMavenCentral[0] |= repository != null && isPlain(repository);
        }
        return ListUtils.map(statements, statement -> {
            J.MethodInvocation jcenter = asRepository(statement, JCENTER);
            if (jcenter == null) {
                return statement;
            }
            if (isPlain(jcenter)) {
                if (hasPlainMavenCentral[0]) {
                    return null;
                }
                hasPlainMavenCentral[0] = true;
            }
            J.MethodInvocation renamed = rename(jcenter);
            return statement instanceof J.Return ? ((J.Return) statement).withExpression(renamed) : renamed;
        });
    }

    /**
     * The last statement of a Groovy closure comes wrapped in a {@link J.Return}.
     */
    private static J.@Nullable MethodInvocation asRepository(Statement statement, String name) {
        Expression expression = statement instanceof J.Return ? ((J.Return) statement).getExpression() :
                statement instanceof Expression ? (Expression) statement : null;
        if (expression instanceof J.MethodInvocation) {
            J.MethodInvocation m = (J.MethodInvocation) expression;
            if (name.equals(m.getSimpleName()) && m.getSelect() == null) {
                return m;
            }
        }
        return null;
    }

    private static boolean isPlain(J.MethodInvocation repository) {
        return repository.getArguments().isEmpty() || repository.getArguments().get(0) instanceof J.Empty;
    }

    private static boolean isRepositoriesReference(@Nullable Expression select) {
        if (select instanceof J.Identifier) {
            return "repositories".equals(((J.Identifier) select).getSimpleName());
        }
        return select instanceof J.FieldAccess && "repositories".equals(((J.FieldAccess) select).getSimpleName());
    }

    private static J.MethodInvocation rename(J.MethodInvocation m) {
        JavaType.Method type = m.getMethodType();
        J.MethodInvocation renamed = m.withName(m.getName().withSimpleName(MAVEN_CENTRAL));
        return type == null ? renamed : renamed.withMethodType(type.withName(MAVEN_CENTRAL));
    }
}
