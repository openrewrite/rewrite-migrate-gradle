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
package org.openrewrite.gradle.migrate.internal;

import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.Tree;
import org.openrewrite.gradle.GradleParser;
import org.openrewrite.gradle.trait.GradlePlugin;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.semver.LatestRelease;
import org.openrewrite.tree.ParseError;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static java.util.Collections.singletonList;

public final class BuildScripts {

    private BuildScripts() {
    }

    /// The highest literal version a build script declares for a plugin matching `pluginIdPattern`, or `null` when
    /// the script declares none, as when a parent project or settings plugin management supplies the version.
    public static @Nullable String declaredPluginVersion(JavaSourceFile cu, String pluginIdPattern) {
        LatestRelease versions = new LatestRelease(null);
        return new JavaIsoVisitor<AtomicReference<@Nullable String>>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, AtomicReference<@Nullable String> highest) {
                new GradlePlugin.Matcher().pluginIdPattern(pluginIdPattern).get(getCursor()).ifPresent(plugin -> {
                    String version = plugin.getVersion();
                    if (version != null && versions.isValid(null, version) &&
                        (highest.get() == null || versions.compare(null, version, highest.get()) > 0)) {
                        highest.set(version);
                    }
                });
                return super.visitMethodInvocation(method, highest);
            }
        }.reduce(cu, new AtomicReference<>()).get();
    }

    public static boolean isInsideBlock(Cursor cursor, String blockName) {
        Iterator<Object> enclosing = cursor.getParentTreeCursor().getPath(J.MethodInvocation.class::isInstance);
        while (enclosing.hasNext()) {
            J.MethodInvocation m = (J.MethodInvocation) enclosing.next();
            if (blockName.equals(m.getSimpleName()) && m.getSelect() == null) {
                return true;
            }
        }
        return false;
    }

    public static boolean isKotlin(JavaSourceFile cu) {
        return cu instanceof K.CompilationUnit;
    }

    public static @Nullable Statement parseStatement(JavaSourceFile cu, String source, ExecutionContext ctx) {
        return GradleParser.builder().build()
                .parseInputs(singletonList(Parser.Input.fromString(cu.getSourcePath(), source)), null, ctx)
                .findFirst()
                .map(parsed -> {
                    if (parsed instanceof ParseError) {
                        throw ((ParseError) parsed).toException();
                    }
                    List<Statement> statements = topLevelStatements((JavaSourceFile) parsed);
                    return statements == null || statements.isEmpty() ? null : statements.get(0);
                })
                .orElse(null);
    }

    /// The statements of a script, which a Kotlin script wraps in a block that is its first statement.
    public static @Nullable List<Statement> topLevelStatements(JavaSourceFile cu) {
        if (cu instanceof G.CompilationUnit) {
            return ((G.CompilationUnit) cu).getStatements();
        }
        List<Statement> statements = ((K.CompilationUnit) cu).getStatements();
        return !statements.isEmpty() && statements.get(0) instanceof J.Block ?
                ((J.Block) statements.get(0)).getStatements() : null;
    }

    public static JavaSourceFile withTopLevelStatements(JavaSourceFile cu, UnaryOperator<List<Statement>> mapping) {
        if (cu instanceof G.CompilationUnit) {
            G.CompilationUnit g = (G.CompilationUnit) cu;
            return g.withStatements(mapping.apply(g.getStatements()));
        }
        K.CompilationUnit k = (K.CompilationUnit) cu;
        if (k.getStatements().isEmpty() || !(k.getStatements().get(0) instanceof J.Block)) {
            return cu;
        }
        return k.withStatements(ListUtils.mapFirst(k.getStatements(), b -> {
            J.Block block = (J.Block) b;
            return block.withStatements(mapping.apply(block.getStatements()));
        }));
    }

    public static boolean isTopLevelBlock(Tree statement, String blockName) {
        if (!(statement instanceof J.MethodInvocation)) {
            return false;
        }
        J.MethodInvocation m = (J.MethodInvocation) statement;
        return blockName.equals(m.getSimpleName()) && m.getSelect() == null && m.getArguments().size() == 1 &&
               m.getArguments().get(0) instanceof J.Lambda &&
               ((J.Lambda) m.getArguments().get(0)).getBody() instanceof J.Block;
    }
}
