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
import org.openrewrite.groovy.tree.G;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.TypeUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static java.util.Arrays.asList;

/**
 * Decides whether a piece of a build script configures a task of a given type, which is what lets a recipe rename a
 * property such as {@code version} or {@code destinationDir} whose name means something else elsewhere in a script.
 * A closure counts only when its owner proves the task by type ({@code tasks.withType(Jar)},
 * {@code task x(type: Jar)}, {@code tasks.register<Jar>("x")}) or by name, either a name the recipe knows or one this
 * script declares with a proven type. Blocks in between must leave the project as the delegate: a task name nested in
 * any other block, such as {@code sourceSets { test { } }}, is not that task.
 */
public final class TaskScope {
    private static final Set<String> PROJECT_BLOCKS = new HashSet<>(asList(
            "afterEvaluate", "allprojects", "configure", "project", "projectsEvaluated", "subprojects", "tasks",
            "withId", "withPlugin"));
    private static final Set<String> TYPED_LOOKUPS = new HashSet<>(asList(
            "create", "creating", "existing", "getByName", "getting", "maybeCreate", "named", "register", "registering"));
    private static final Set<String> CONFIGURE_METHODS = new HashSet<>(asList("all", "configure", "configureEach"));
    private static final List<String> GRADLE_WILDCARD_PREFIXES = asList(
            "groovy.", "java.", "javax.", "kotlin.", "org.apache.tools.", "org.gradle.");

    private final boolean groovy;
    private final Map<String, String> taskTypes;
    private final Predicate<String> knownNames;
    private final JavaSourceFile sourceFile;
    private final Set<String> lexicalNames = new HashSet<>();
    private final Set<String> localTypes = new HashSet<>();
    private final Set<String> provenNames = new HashSet<>();
    private final Set<String> otherNames = new HashSet<>();

    /**
     * @param taskTypes  simple name to fully qualified name of the task types that count, subclasses included
     * @param knownNames the task names that count without a declaration in this script, such as {@code jar}
     */
    public TaskScope(JavaSourceFile sourceFile, Map<String, String> taskTypes, Predicate<String> knownNames) {
        this.sourceFile = sourceFile;
        this.groovy = sourceFile instanceof G.CompilationUnit;
        this.taskTypes = taskTypes;
        this.knownNames = knownNames;
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations v, Integer p) {
                v.getVariables().forEach(n -> {
                    lexicalNames.add(n.getSimpleName());
                    localTypes.add(n.getSimpleName());
                });
                return super.visitVariableDeclarations(v, p);
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration m, Integer p) {
                lexicalNames.add(m.getSimpleName());
                return super.visitMethodDeclaration(m, p);
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, Integer p) {
                localTypes.add(c.getSimpleName());
                return super.visitClassDeclaration(c, p);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation m, Integer p) {
                recordDeclaration(m);
                return super.visitMethodInvocation(m, p);
            }
        }.visit(sourceFile, 0);
    }

    private void recordDeclaration(J.MethodInvocation m) {
        String name = null;
        Expression type = declaredType(m);
        if ("task".equals(m.getSimpleName()) && m.getSelect() == null) {
            if (!m.getArguments().isEmpty() && m.getArguments().get(0) instanceof J.MethodInvocation) {
                // Groovy's `task x(type: Jar)` and `task x { }` nest the task as a call named for it
                J.MethodInvocation declaration = (J.MethodInvocation) m.getArguments().get(0);
                name = declaration.getSimpleName();
                type = declaredType(declaration);
            } else if (!m.getArguments().isEmpty() && m.getArguments().get(0) instanceof J.Identifier) {
                // `task x`
                name = ((J.Identifier) m.getArguments().get(0)).getSimpleName();
            } else {
                name = literalName(m);
            }
        } else if (("register".equals(m.getSimpleName()) || "create".equals(m.getSimpleName())) && isTasks(m.getSelect())) {
            name = literalName(m);
        }
        if (name != null) {
            (type != null && isProvenType(type) ? provenNames : otherNames).add(name);
        }
    }

    private static @Nullable String literalName(J.MethodInvocation m) {
        if (!m.getArguments().isEmpty() && m.getArguments().get(0) instanceof J.Literal) {
            Object value = ((J.Literal) m.getArguments().get(0)).getValue();
            return value instanceof String ? (String) value : null;
        }
        return null;
    }

    /**
     * @return Whether this scope was built for the source file, as a visitor is reused across source files.
     */
    public boolean isFor(JavaSourceFile other) {
        return sourceFile == other;
    }

    /**
     * @return Whether a script's own variable or method of this name hides what Gradle means by it.
     */
    public boolean isLexical(String name) {
        return lexicalNames.contains(name);
    }

    /**
     * @return Whether the nearest closure around the cursor is one whose delegate is a proven task.
     */
    public boolean isInsideTaskClosure(Cursor cursor) {
        Cursor lambda = nearestLambda(cursor);
        return lambda != null && isTaskClosure(lambda);
    }

    /**
     * @return The cursor of the nearest closure around the cursor, or null outside of one or inside a declaration.
     */
    public static @Nullable Cursor nearestLambda(Cursor cursor) {
        if (cursor.firstEnclosing(J.MethodDeclaration.class) != null || cursor.firstEnclosing(J.ClassDeclaration.class) != null) {
            return null;
        }
        J.Lambda lambda = cursor.firstEnclosing(J.Lambda.class);
        return lambda == null ? null : cursor.dropParentUntil(v -> v == lambda);
    }

    /**
     * @return Whether this closure, without parameters of its own, is the configuration of a proven task.
     */
    public boolean isTaskClosure(Cursor lambdaCursor) {
        J.Lambda lambda = lambdaCursor.getValue();
        if (hasParameters(lambda) || isHidden()) {
            return false;
        }
        Cursor ownerCursor = lambdaCursor.getParentTreeCursor();
        return ownerCursor.getValue() instanceof J.MethodInvocation &&
               isAtProjectLevel(ownerCursor) &&
               configuresProvenTask(ownerCursor.getValue(), ownerCursor);
    }

    /**
     * @return Whether the expression names a proven task, as in {@code jar.x = y} or {@code tasks.jar.x = y}. Only
     * Groovy, where a task is a dynamic property of the project; in Kotlin {@code tasks.jar} is a provider.
     */
    public boolean isTaskReference(@Nullable Expression expression, Cursor cursor) {
        return groovy && !isHidden() && isNamedTask(expression) && isAtProjectLevel(cursor);
    }

    private boolean isNamedTask(@Nullable Expression expression) {
        if (expression instanceof J.Identifier) {
            return isKnown(((J.Identifier) expression).getSimpleName());
        }
        if (expression instanceof J.FieldAccess) {
            J.FieldAccess field = (J.FieldAccess) expression;
            return (isTasks(field.getTarget()) || isProject(field.getTarget())) && isKnown(field.getSimpleName());
        }
        return false;
    }

    private boolean isHidden() {
        return lexicalNames.contains("tasks") || lexicalNames.contains("task") || lexicalNames.contains("project");
    }

    private boolean isKnown(String name) {
        return !lexicalNames.contains(name) && !otherNames.contains(name) &&
               (provenNames.contains(name) || knownNames.test(name));
    }

    /**
     * Blocks from the cursor outward must keep the project as what a bare name resolves against.
     */
    private boolean isAtProjectLevel(Cursor cursor) {
        for (Cursor c = cursor.getParent(); c != null; c = c.getParent()) {
            Object value = c.getValue();
            if (value instanceof J.MethodDeclaration || value instanceof J.ClassDeclaration) {
                return false;
            }
            if (value instanceof J.Lambda) {
                Object owner = c.getParentTreeCursor().getValue();
                if (hasParameters((J.Lambda) value) || !(owner instanceof J.MethodInvocation) ||
                    !isProjectBlock((J.MethodInvocation) owner)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isProjectBlock(J.MethodInvocation m) {
        return PROJECT_BLOCKS.contains(m.getSimpleName()) ||
               "withType".equals(m.getSimpleName()) && isPlugins(m.getSelect());
    }

    private static boolean isPlugins(@Nullable Expression select) {
        return select instanceof J.Identifier && "plugins".equals(((J.Identifier) select).getSimpleName()) ||
               select instanceof J.FieldAccess && "plugins".equals(((J.FieldAccess) select).getSimpleName());
    }

    private static boolean hasParameters(J.Lambda lambda) {
        return lambda.getParameters().getParameters().stream().anyMatch(p -> !(p instanceof J.Empty));
    }

    private boolean configuresProvenTask(J.MethodInvocation m, Cursor cursor) {
        String name = m.getSimpleName();
        Expression select = m.getSelect();
        if (CONFIGURE_METHODS.contains(name)) {
            // tasks.withType(Jar).configureEach { }, tasks.named('jar').configure { }, jar.configure { }
            return select instanceof J.MethodInvocation ?
                    configuresProvenTask((J.MethodInvocation) select, cursor) :
                    isNamedTask(select);
        }
        boolean onTasks = isTasks(select) || select == null && isDirectlyInTasksBlock(cursor);
        if (onTasks && "withType".equals(name)) {
            Expression type = declaredType(m);
            return type != null && isProvenType(type);
        }
        if (onTasks && TYPED_LOOKUPS.contains(name)) {
            Expression type = declaredType(m);
            if (type != null) {
                return isProvenType(type);
            }
            String taskName = literalName(m);
            return taskName != null && ("named".equals(name) || "getByName".equals(name)) && isKnown(taskName);
        }
        if ("task".equals(name) && select == null) {
            // task<Jar>("x") { }, task('x', type: Jar) { }
            Expression type = declaredType(m);
            return type != null && isProvenType(type);
        }
        Object parent = cursor.getParentTreeCursor().getValue();
        if (parent instanceof J.MethodInvocation && "task".equals(((J.MethodInvocation) parent).getSimpleName()) &&
            ((J.MethodInvocation) parent).getSelect() == null) {
            // task x(type: Jar) { }
            Expression type = declaredType(m);
            return type != null && isProvenType(type);
        }
        // jar { }, tasks.jar { }, tasks { jar { } }. In Kotlin a bare `test { }` outside of `tasks { }` is no task.
        return m.getArguments().size() == 1 &&
               (select == null ? groovy || onTasks : onTasks || isProject(select)) && isKnown(name);
    }

    private static boolean isDirectlyInTasksBlock(Cursor cursor) {
        J.Lambda lambda = cursor.getParentTreeCursor().firstEnclosing(J.Lambda.class);
        if (lambda == null) {
            return false;
        }
        Object owner = cursor.dropParentUntil(v -> v == lambda).getParentTreeCursor().getValue();
        return owner instanceof J.MethodInvocation && "tasks".equals(((J.MethodInvocation) owner).getSimpleName());
    }

    /**
     * @return The task type a call states: a Kotlin type argument, a Groovy {@code type:} entry, or an argument that
     * is not the name, as in {@code tasks.register('x', Jar)}, {@code tasks.withType(Jar)} or {@code registering(Jar::class)}.
     */
    private static @Nullable Expression declaredType(J.MethodInvocation m) {
        if (m.getTypeParameters() != null && !m.getTypeParameters().isEmpty()) {
            return m.getTypeParameters().get(0);
        }
        for (Expression argument : m.getArguments()) {
            if (argument instanceof G.MapEntry) {
                G.MapEntry entry = (G.MapEntry) argument;
                if (entry.getKey() instanceof J.Literal && "type".equals(((J.Literal) entry.getKey()).getValue())) {
                    return entry.getValue();
                }
            } else if (!(argument instanceof J.Literal) && !(argument instanceof J.Lambda) && !(argument instanceof J.Empty)) {
                return argument;
            }
        }
        return null;
    }

    private static boolean isTasks(@Nullable Expression select) {
        if (select instanceof J.FieldAccess) {
            J.FieldAccess field = (J.FieldAccess) select;
            return "tasks".equals(field.getSimpleName()) && isProject(field.getTarget());
        }
        return select instanceof J.Identifier && "tasks".equals(((J.Identifier) select).getSimpleName());
    }

    private static boolean isProject(@Nullable Expression select) {
        return select instanceof J.Identifier && "project".equals(((J.Identifier) select).getSimpleName());
    }

    private static Expression unwrapClassLiteral(Expression expression) {
        // Kotlin's Jar::class.java
        if (expression instanceof J.FieldAccess && ((J.FieldAccess) expression).getTarget() instanceof J.MemberReference) {
            return unwrapClassLiteral(((J.FieldAccess) expression).getTarget());
        }
        // Kotlin's Jar::class
        if (expression instanceof J.MemberReference) {
            return ((J.MemberReference) expression).getContaining();
        }
        return expression;
    }

    private static String typeName(Expression expression) {
        if (expression instanceof J.Identifier) {
            return ((J.Identifier) expression).getSimpleName();
        }
        if (expression instanceof J.FieldAccess) {
            J.FieldAccess field = (J.FieldAccess) expression;
            String target = typeName(field.getTarget());
            return target.isEmpty() ? "" : target + "." + field.getSimpleName();
        }
        return "";
    }

    private boolean isProvenType(Expression expression) {
        Expression type = unwrapClassLiteral(expression);
        for (String fqn : taskTypes.values()) {
            if (TypeUtils.isAssignableTo(fqn, type.getType())) {
                return true;
            }
        }
        String name = typeName(type);
        String simple = name.substring(name.lastIndexOf('.') + 1);
        String qualified = taskTypes.get(simple);
        if (qualified == null) {
            return false;
        }
        if (name.equals(qualified)) {
            return true;
        }
        return name.equals(simple) && !localTypes.contains(simple) && !isImportedAsSomethingElse(simple, qualified);
    }

    private boolean isImportedAsSomethingElse(String simple, String qualified) {
        for (J.Import anImport : sourceFile.getImports()) {
            String imported = anImport.getQualid().printTrimmed();
            if (anImport.getAlias() != null) {
                if (simple.equals(anImport.getAlias().getSimpleName())) {
                    return true;
                }
            } else if (imported.endsWith("." + simple)) {
                if (!qualified.equals(imported)) {
                    return true;
                }
            } else if (imported.endsWith(".*") && GRADLE_WILDCARD_PREFIXES.stream().noneMatch(imported::startsWith)) {
                return true;
            }
        }
        return false;
    }
}
