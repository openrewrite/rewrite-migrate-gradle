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
package org.openrewrite.gradle.migrate.kotlin;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.marker.Markers;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import static java.util.Collections.emptyList;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseProjectInsteadOfBuildScriptLabel extends Recipe {

    /**
     * What {@code this@Build_gradle} offers through {@code org.gradle.kotlin.dsl.KotlinScript} and {@code Project} has as well.
     */
    private static final Set<String> SCRIPT_API = new HashSet<>(Arrays.asList(
            "logger", "logging", "resources", "relativePath", "uri", "file", "files", "fileTree", "zipTree", "tarTree",
            "copy", "copySpec", "mkdir", "delete"));
    /**
     * In these, {@code project} is not the project of the script, which is often why the label was there.
     */
    private static final Set<String> OTHER_PROJECT_BLOCKS = new HashSet<>(Arrays.asList(
            "subprojects", "allprojects", "project", "rootProject", "configure", "beforeProject", "afterProject"));

    String displayName = "Use `project` instead of `this@Build_gradle`";

    String description = "The Kotlin 2 compiler in Gradle 9 no longer resolves the label `this@Build_gradle`, which fails with `Unresolved label`. " +
            "On Gradle 8.14 the label is the script object rather than the `Project`, so it only offers the script API, such as " +
            "`file(..)` and `logger`, and those calls move to `project`. A member the script declares itself, a label inside " +
            "`subprojects`, `allprojects`, `project(..)` or `configure(..)` where `project` is a different project, and a bare " +
            "label are left alone. See the [Gradle upgrade guide](https://docs.gradle.org/9.0.0/userguide/upgrading_major_version_9.html) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaVisitor<ExecutionContext>() {
            private Set<String> declared = new HashSet<>();

            @Override
            public J preVisit(J tree, ExecutionContext ctx) {
                if (tree instanceof K.CompilationUnit) {
                    declared = findDeclaredNames(tree);
                }
                return tree;
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
                return isLabeledScriptApi(m.getSelect(), m.getSimpleName()) ? m.withSelect(project(m.getSelect())) : m;
            }

            @Override
            public J visitFieldAccess(J.FieldAccess fieldAccess, ExecutionContext ctx) {
                J.FieldAccess f = (J.FieldAccess) super.visitFieldAccess(fieldAccess, ctx);
                return isLabeledScriptApi(f.getTarget(), f.getSimpleName()) ? f.withTarget(project(f.getTarget())) : f;
            }

            private boolean isLabeledScriptApi(Expression receiver, String member) {
                return receiver instanceof K.This && ((K.This) receiver).getLabel() != null &&
                        "Build_gradle".equals(((K.This) receiver).getLabel().getSimpleName()) &&
                        SCRIPT_API.contains(member) && !declared.contains(member) && !isInOtherProjectBlock();
            }

            private boolean isInOtherProjectBlock() {
                Iterator<Cursor> path = getCursor().getPathAsCursors(c -> c.getValue() instanceof J.MethodInvocation);
                while (path.hasNext()) {
                    if (OTHER_PROJECT_BLOCKS.contains(path.next().<J.MethodInvocation>getValue().getSimpleName())) {
                        return true;
                    }
                }
                return false;
            }

            private J.Identifier project(Expression receiver) {
                return new J.Identifier(Tree.randomId(), receiver.getPrefix(), Markers.EMPTY, emptyList(), "project", null, null);
            }
        });
    }

    private static Set<String> findDeclaredNames(J tree) {
        Set<String> names = new HashSet<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable variable, Integer p) {
                names.add(variable.getSimpleName());
                return super.visitVariable(variable, p);
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, Integer p) {
                names.add(method.getSimpleName());
                return super.visitMethodDeclaration(method, p);
            }
        }.visit(tree, 0);
        return names;
    }
}
