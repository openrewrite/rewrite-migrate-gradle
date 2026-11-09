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
import org.openrewrite.Parser;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.GradleParser;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.kotlin.marker.By;
import org.openrewrite.kotlin.tree.K;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.Collections.singletonList;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseAssembleDependsOnInsteadOfArchives extends Recipe {

    private static final String ARCHIVES = "archives";
    private static final Set<String> TASK_FACTORIES = new HashSet<>(Arrays.asList("register", "create", "named", "getByName", "maybeCreate"));
    private static final Set<String> TASK_DELEGATES = new HashSet<>(Arrays.asList("registering", "creating", "existing", "getting"));

    String displayName = "Make `assemble` depend on a task instead of adding it to the `archives` configuration";

    String description = "Gradle 9.1 deprecates adding artifacts to the `archives` configuration, which will fail in Gradle 10. " +
            "What the declaration does is have `assemble` build the artifact, so this recipe replaces `artifacts { archives x }` with " +
            "`tasks.named(\"assemble\") { dependsOn(x) }`, as the upgrade guide says. Only tasks and task providers declared " +
            "in the script, and `jar`, are replaced. Files, maps and a declaration with a configuration closure are left alone, " +
            "and so is every script that mentions `uploadArchives` or otherwise reads the `archives` configuration, such as " +
            "`sign configurations.archives`, since those depend on its artifacts. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/current/userguide/upgrading_version_9.html#archives-configuration) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaIsoVisitor<ExecutionContext>() {
            private Script script = new Script();
            private @Nullable JavaSourceFile sourceFile;

            @Override
            public @Nullable J visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof JavaSourceFile) {
                    sourceFile = (JavaSourceFile) tree;
                    script = Script.of(sourceFile);
                }
                if (script.skipped) {
                    return (J) tree;
                }
                J visited = super.visit(tree, ctx);
                if (visited instanceof G.CompilationUnit) {
                    G.CompilationUnit cu = (G.CompilationUnit) visited;
                    return cu.withStatements(replace(cu.getStatements(), ctx));
                }
                return visited;
            }

            @Override
            public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block b = super.visitBlock(block, ctx);
                return b.withStatements(replace(b.getStatements(), ctx));
            }

            private List<Statement> replace(List<Statement> statements, ExecutionContext ctx) {
                List<Statement> result = null;
                for (int i = 0; i < statements.size(); i++) {
                    Statement statement = statements.get(i);
                    J.MethodInvocation artifacts = asArtifactsBlock(statement);
                    J.Block body = artifacts == null ? null : (J.Block) ((J.Lambda) artifacts.getArguments().get(0)).getBody();
                    List<String> tasks = new ArrayList<>();
                    List<Statement> remaining = new ArrayList<>();
                    if (body != null) {
                        for (Statement declaration : body.getStatements()) {
                            String task = taskOfArchivesDeclaration(declaration);
                            if (task == null) {
                                remaining.add(declaration);
                            } else {
                                tasks.add(task);
                            }
                        }
                    }
                    if (tasks.isEmpty()) {
                        if (result != null) {
                            result.add(statement);
                        }
                        continue;
                    }
                    if (result == null) {
                        result = new ArrayList<>(statements.subList(0, i));
                    }
                    Statement assemble = assembleDependsOn(tasks, statement.getPrefix(), ctx);
                    if (remaining.isEmpty()) {
                        result.add(statement instanceof J.Return ? ((J.Return) statement).withExpression(((Expression) assemble).withPrefix(Space.EMPTY)) : assemble);
                    } else {
                        result.add(assemble);
                        J.MethodInvocation shortened = artifacts.withArguments(singletonList(
                                ((J.Lambda) artifacts.getArguments().get(0)).withBody(body.withStatements(remaining))));
                        Space separated = Space.format("\n\n" + indent(statement.getPrefix()));
                        result.add(statement instanceof J.Return ?
                                ((J.Return) statement).withExpression(shortened).withPrefix(separated) :
                                shortened.withPrefix(separated));
                    }
                }
                return result == null ? statements : result;
            }

            private @Nullable String taskOfArchivesDeclaration(Statement declaration) {
                Expression expression = unwrap(declaration);
                if (!(expression instanceof J.MethodInvocation)) {
                    return null;
                }
                J.MethodInvocation m = (J.MethodInvocation) expression;
                Expression notation = null;
                if (m.getSelect() == null && ARCHIVES.equals(m.getSimpleName()) && m.getArguments().size() == 1) {
                    notation = m.getArguments().get(0);
                } else if (m.getSelect() == null && "add".equals(m.getSimpleName()) && m.getArguments().size() == 2 &&
                           isArchivesLiteral(m.getArguments().get(0))) {
                    notation = m.getArguments().get(1);
                } else if (isArchivesLiteral(m.getSelect()) && m.getArguments().size() == 1) {
                    // Kotlin's `"archives"(x)`
                    notation = m.getArguments().get(0);
                }
                if (notation == null || !isTask(notation)) {
                    return null;
                }
                return notation.printTrimmed(new Cursor(new Cursor(null, Cursor.ROOT_VALUE), sourceFile));
            }

            private boolean isTask(Expression notation) {
                if (notation instanceof J.Identifier) {
                    String name = ((J.Identifier) notation).getSimpleName();
                    return script.tasks.contains(name) || !script.kotlin && "jar".equals(name) && !script.variables.contains(name);
                }
                return notation instanceof J.FieldAccess && isTasks(((J.FieldAccess) notation).getTarget()) ||
                       notation instanceof J.MethodInvocation && isTaskLookup((J.MethodInvocation) notation);
            }

            private Statement assembleDependsOn(List<String> tasks, Space prefix, ExecutionContext ctx) {
                String indent = indent(prefix);
                String snippet = "tasks.named(\"assemble\") {\n" +
                                 indent + "    dependsOn(" + String.join(", ", tasks) + ")\n" +
                                 indent + "}\n";
                SourceFile parsed = GradleParser.builder().build()
                        .parseInputs(singletonList(Parser.Input.fromString(Paths.get(script.kotlin ? "build.gradle.kts" : "build.gradle"), snippet)), null, ctx)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Unable to parse `tasks.named(\"assemble\")` block"));
                List<Statement> statements = script.kotlin ? ((K.CompilationUnit) parsed).getStatements() : ((G.CompilationUnit) parsed).getStatements();
                return statements.get(0).withPrefix(prefix);
            }
        });
    }

    private static J.@Nullable MethodInvocation asArtifactsBlock(Statement statement) {
        Expression expression = unwrap(statement);
        if (!(expression instanceof J.MethodInvocation)) {
            return null;
        }
        J.MethodInvocation m = (J.MethodInvocation) expression;
        return "artifacts".equals(m.getSimpleName()) && m.getSelect() == null && m.getArguments().size() == 1 &&
               m.getArguments().get(0) instanceof J.Lambda && ((J.Lambda) m.getArguments().get(0)).getBody() instanceof J.Block ? m : null;
    }

    private static @Nullable Expression unwrap(Statement statement) {
        if (statement instanceof J.Return) {
            return ((J.Return) statement).getExpression();
        }
        return statement instanceof Expression ? (Expression) statement : null;
    }

    private static boolean isArchivesLiteral(@Nullable Expression expression) {
        return expression instanceof J.Literal && ARCHIVES.equals(((J.Literal) expression).getValue());
    }

    private static boolean isTasks(@Nullable Expression expression) {
        return expression instanceof J.Identifier && "tasks".equals(((J.Identifier) expression).getSimpleName());
    }

    private static boolean isTaskLookup(J.MethodInvocation m) {
        return isTasks(m.getSelect()) && TASK_FACTORIES.contains(m.getSimpleName());
    }

    private static String indent(Space prefix) {
        String whitespace = prefix.getLastWhitespace();
        return whitespace.substring(whitespace.lastIndexOf('\n') + 1);
    }

    /**
     * What the script says about its tasks and about the `archives` configuration, gathered before anything changes.
     */
    private static class Script {
        final Set<String> tasks = new HashSet<>();
        final Set<String> variables = new HashSet<>();
        boolean kotlin;
        boolean skipped;

        static Script of(JavaSourceFile sourceFile) {
            Script script = new Script();
            script.kotlin = sourceFile instanceof K.CompilationUnit;
            script.skipped = sourceFile.printAll().contains("uploadArchives");
            if (script.skipped) {
                return script;
            }
            Set<String> others = new HashSet<>();
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable variable, Integer p) {
                    J.VariableDeclarations declarations = getCursor().firstEnclosing(J.VariableDeclarations.class);
                    boolean by = declarations != null && declarations.getMarkers().findFirst(By.class).isPresent();
                    Expression initializer = variable.getInitializer();
                    if (!script.variables.add(variable.getSimpleName()) || !isTaskInitializer(initializer, by)) {
                        others.add(variable.getSimpleName());
                    } else {
                        script.tasks.add(variable.getSimpleName());
                    }
                    return super.visitVariable(variable, p);
                }

                @Override
                public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, Integer p) {
                    // `configurations.archives`, which has to go on holding the artifacts for whoever reads it
                    if (ARCHIVES.equals(fieldAccess.getSimpleName())) {
                        script.skipped = true;
                    }
                    return super.visitFieldAccess(fieldAccess, p);
                }

                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, Integer p) {
                    String name = method.getSimpleName();
                    if ("task".equals(name) && method.getSelect() == null && !method.getArguments().isEmpty()) {
                        String declared = declaredTaskName(method.getArguments().get(0));
                        if (declared != null) {
                            script.tasks.add(declared);
                        }
                    } else if (isTaskLookup(method) && !"named".equals(name) && !"getByName".equals(name) &&
                               !method.getArguments().isEmpty() && method.getArguments().get(0) instanceof J.Literal) {
                        Object value = ((J.Literal) method.getArguments().get(0)).getValue();
                        if (value instanceof String) {
                            script.tasks.add((String) value);
                        }
                    } else if (!"add".equals(name) && !method.getArguments().isEmpty() && isArchivesLiteral(method.getArguments().get(0))) {
                        // `configurations.getByName("archives")`
                        script.skipped = true;
                    }
                    return super.visitMethodInvocation(method, p);
                }
            }.visit(sourceFile, 0);
            script.tasks.removeAll(others);
            return script;
        }

        private static @Nullable String declaredTaskName(Expression argument) {
            if (argument instanceof J.Identifier) {
                return ((J.Identifier) argument).getSimpleName();
            }
            if (argument instanceof J.MethodInvocation) {
                return ((J.MethodInvocation) argument).getSimpleName();
            }
            if (argument instanceof J.Literal && ((J.Literal) argument).getValue() instanceof String) {
                return (String) ((J.Literal) argument).getValue();
            }
            return null;
        }

        private static boolean isTaskInitializer(@Nullable Expression initializer, boolean by) {
            if (initializer instanceof J.FieldAccess) {
                J.FieldAccess fieldAccess = (J.FieldAccess) initializer;
                return isTasks(fieldAccess.getTarget()) && (!by || TASK_DELEGATES.contains(fieldAccess.getSimpleName()));
            }
            if (initializer instanceof J.MethodInvocation) {
                J.MethodInvocation m = (J.MethodInvocation) initializer;
                return by ? isTasks(m.getSelect()) && TASK_DELEGATES.contains(m.getSimpleName()) : isTaskLookup(m);
            }
            return false;
        }
    }
}
