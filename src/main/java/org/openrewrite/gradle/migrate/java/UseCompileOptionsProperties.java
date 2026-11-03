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
package org.openrewrite.gradle.migrate.java;

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
import org.openrewrite.groovy.GroovyVisitor;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseCompileOptionsProperties extends Recipe {

    private static final Set<String> COMPILE_TYPES = new HashSet<>(Arrays.asList("JavaCompile", "GroovyCompile"));

    String displayName = "Set compile option properties instead of calling `fork(Map)`, `debug(Map)` and `define(Map)`";

    String description = "`AbstractOptions.define(Map)`, `CompileOptions.fork(Map)`, `CompileOptions.debug(Map)` and " +
            "`GroovyCompileOptions.fork(Map)` are deprecated and removed in Gradle 9, where calling them fails. Each map entry " +
            "becomes a property assignment, and `fork` and `debug` also set the flag they turned on. Only Groovy is changed, for " +
            "named arguments with a compile task as the owner. A map held in a variable is left alone. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/8.14/userguide/upgrading_version_8.html#deprecated_abstract_options) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new GroovyVisitor<ExecutionContext>() {
            @Override
            public J visitCompilationUnit(G.CompilationUnit cu, ExecutionContext ctx) {
                G.CompilationUnit c = (G.CompilationUnit) super.visitCompilationUnit(cu, ctx);
                return c.withStatements(ListUtils.flatMap(c.getStatements(), this::expand));
            }

            @Override
            public J visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block b = (J.Block) super.visitBlock(block, ctx);
                return b.withStatements(ListUtils.flatMap(b.getStatements(), this::expand));
            }

            private List<Statement> expand(Statement statement) {
                Cursor cursor = new Cursor(getCursor(), statement);
                Statement unwrapped = statement;
                if (statement instanceof J.Return && ((J.Return) statement).getExpression() instanceof J.MethodInvocation) {
                    // The last statement of a closure is wrapped in an implicit return
                    unwrapped = (J.MethodInvocation) ((J.Return) statement).getExpression();
                    cursor = new Cursor(cursor, unwrapped);
                }
                if (!(unwrapped instanceof J.MethodInvocation)) {
                    return singletonList(statement);
                }
                J.MethodInvocation m = (J.MethodInvocation) unwrapped;
                String options = optionsReceiver(m, cursor);
                List<G.MapEntry> entries = options == null ? null : namedArguments(m);
                if (entries == null) {
                    return singletonList(statement);
                }

                String method = m.getSimpleName();
                String target = options;
                List<Statement> replacement = new ArrayList<>();
                if (!"define".equals(method)) {
                    target = options + "." + method + "Options";
                    replacement.add(GroovyTemplate.builder(options + "." + method + " = true").build()
                            .apply(cursor, m.getCoordinates().replace()));
                }
                for (G.MapEntry entry : entries) {
                    String key = (String) ((J.Literal) entry.getKey()).getValue();
                    // The value travels as a parameter rather than as text, so a `#{` inside it cannot be mistaken for a placeholder
                    replacement.add(GroovyTemplate.builder(target + "." + key + " = #{any()}").build()
                            .apply(cursor, m.getCoordinates().replace(), entry.getValue().withPrefix(Space.EMPTY)));
                }
                Space prefix = statement.getPrefix();
                return ListUtils.map(replacement, (i, s) -> s.withPrefix(i == 0 ? prefix : Space.format(lastLine(prefix))));
            }

            private String lastLine(Space prefix) {
                String whitespace = prefix.getWhitespace();
                int newline = whitespace.lastIndexOf('\n');
                return newline < 0 ? "\n" + whitespace : whitespace.substring(newline);
            }

            private List<Statement> singletonList(Statement statement) {
                List<Statement> statements = new ArrayList<>(1);
                statements.add(statement);
                return statements;
            }
        });
    }

    /**
     * The receiver's source text when the call is one of the deprecated methods on the options of a compile task.
     */
    private static @Nullable String optionsReceiver(J.MethodInvocation m, Cursor cursor) {
        String method = m.getSimpleName();
        if (!("fork".equals(method) || "debug".equals(method) || "define".equals(method))) {
            return null;
        }
        Expression select = m.getSelect();
        if (select instanceof J.Identifier) {
            String name = ((J.Identifier) select).getSimpleName();
            if (!isOptionsName(name, method) || !isInCompileTaskBlock(cursor)) {
                return null;
            }
            return name;
        }
        if (select instanceof J.FieldAccess) {
            J.FieldAccess field = (J.FieldAccess) select;
            if (!isOptionsName(field.getSimpleName(), method) || !isChainOfNames(field.getTarget())) {
                return null;
            }
            if (isCompileTaskName(lastName(field.getTarget())) || isInCompileTaskBlock(cursor)) {
                return select.printTrimmed(cursor);
            }
        }
        return null;
    }

    private static boolean isOptionsName(String name, String method) {
        return "options".equals(name) || "groovyOptions".equals(name) && !"debug".equals(method);
    }

    private static boolean isChainOfNames(Expression expression) {
        return expression instanceof J.Identifier ||
                expression instanceof J.FieldAccess && isChainOfNames(((J.FieldAccess) expression).getTarget());
    }

    private static String lastName(Expression expression) {
        return expression instanceof J.Identifier ? ((J.Identifier) expression).getSimpleName() : ((J.FieldAccess) expression).getSimpleName();
    }

    private static boolean isCompileTaskName(String name) {
        return name.startsWith("compile") && (name.endsWith("Java") || name.endsWith("Groovy"));
    }

    private static boolean isInCompileTaskBlock(Cursor cursor) {
        Iterator<Cursor> path = cursor.getPathAsCursors(c -> c.getValue() instanceof J.Lambda);
        while (path.hasNext()) {
            J.MethodInvocation owner = path.next().firstEnclosing(J.MethodInvocation.class);
            if (owner != null && (isCompileTaskName(owner.getSimpleName()) || namesCompileTask(owner))) {
                return true;
            }
        }
        return false;
    }

    private static boolean namesCompileTask(J.MethodInvocation m) {
        boolean[] found = {false};
        JavaIsoVisitor<Integer> visitor = new JavaIsoVisitor<Integer>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, Integer p) {
                found[0] |= COMPILE_TYPES.contains(identifier.getSimpleName());
                return identifier;
            }

            @Override
            public J.Literal visitLiteral(J.Literal literal, Integer p) {
                found[0] |= literal.getValue() instanceof String && isCompileTaskName((String) literal.getValue());
                return literal;
            }
        };
        if (m.getSelect() != null) {
            visitor.visit(m.getSelect(), 0);
        }
        for (Expression argument : m.getArguments()) {
            if (!(argument instanceof J.Lambda)) {
                visitor.visit(argument, 0);
            }
        }
        return found[0];
    }

    /**
     * The entries of {@code fork(a: 1, b: 2)} or {@code fork([a: 1, b: 2])}, or null when the argument isn't spelled out like that.
     */
    private static @Nullable List<G.MapEntry> namedArguments(J.MethodInvocation m) {
        List<Expression> arguments = m.getArguments();
        List<G.MapEntry> entries = new ArrayList<>(arguments.size());
        if (arguments.size() == 1 && arguments.get(0) instanceof G.MapLiteral) {
            entries.addAll(((G.MapLiteral) arguments.get(0)).getElements());
        } else {
            for (Expression argument : arguments) {
                if (!(argument instanceof G.MapEntry)) {
                    return null;
                }
                entries.add((G.MapEntry) argument);
            }
        }
        if (entries.isEmpty()) {
            return null;
        }
        for (G.MapEntry entry : entries) {
            if (!(entry.getKey() instanceof J.Literal) || !(((J.Literal) entry.getKey()).getValue() instanceof String) ||
                    !((String) ((J.Literal) entry.getKey()).getValue()).matches("[A-Za-z_][A-Za-z0-9_]*")) {
                return null;
            }
        }
        return entries;
    }
}
