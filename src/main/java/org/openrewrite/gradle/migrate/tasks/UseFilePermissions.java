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
import org.openrewrite.marker.Markers;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseFilePermissions extends Recipe {

    /**
     * Blocks that are a copy spec by name. Tasks of the core plugins and of the plugins that add archive tasks.
     */
    private static final Set<String> COPY_BLOCKS = new HashSet<>(Arrays.asList(
            "copy", "sync", "copySpec", "contents", "jar", "war", "ear", "bootJar", "bootWar", "shadowJar", "sourcesJar", "javadocJar",
            "distZip", "distTar", "bootDistZip", "bootDistTar", "installDist", "processResources", "processTestResources"));
    /**
     * Blocks that are a copy spec only when they sit in another one.
     */
    private static final Set<String> NESTED_COPY_BLOCKS = new HashSet<>(Arrays.asList("from", "into", "metaInf", "webInf", "lib"));
    private static final Set<String> COPY_TYPES = new HashSet<>(Arrays.asList(
            "Copy", "Sync", "Zip", "Tar", "Jar", "War", "Ear", "AbstractArchiveTask", "AbstractCopyTask", "applicationDistribution"));
    /**
     * Nebula's packaging tasks have `fileMode` and `dirMode` of their own, which do not go away with Gradle's.
     */
    private static final Set<String> OWN_MODE_TYPES = new HashSet<>(Arrays.asList(
            "ospackage", "Rpm", "Deb", "buildRpm", "buildDeb", "SystemPackagingTask", "AbstractPackagingCopyAction"));

    String displayName = "Use `filePermissions` and `dirPermissions` instead of `fileMode` and `dirMode`";

    String description = "`CopyProcessingSpec.fileMode` and `dirMode` are deprecated since Gradle 8.8 and removed in Gradle 9, " +
            "where setting them fails. `filePermissions { unix(..) }` and `dirPermissions { unix(..) }` exist since Gradle 8.3. " +
            "Integer literals inside `Copy`, `Sync`, archive task and copy spec blocks are moved as they are written, so Groovy's " +
            "octal `0644` stays `0644` and Kotlin's decimal `420` stays `420`, both the same permission bits. Computed values such as " +
            "`\"755\".toInt(8)`, reads, `details.mode`, unknown task types and Nebula's packaging tasks are left alone. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/9.0.0/userguide/upgrading_major_version_9.html#removal_of_unix_mode_based_file_permissions) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaVisitor<ExecutionContext>() {
            private Set<String> shadowed = new HashSet<>();

            @Override
            public J preVisit(J tree, ExecutionContext ctx) {
                if (tree instanceof JavaSourceFile) {
                    shadowed = findOwnVariables(tree);
                }
                return tree;
            }

            @Override
            public J visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
                if (assignment.getVariable() instanceof J.Identifier) {
                    J replaced = replace(assignment, ((J.Identifier) assignment.getVariable()).getSimpleName(), assignment.getAssignment());
                    if (replaced != null) {
                        return replaced;
                    }
                }
                return super.visitAssignment(assignment, ctx);
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                if (method.getSelect() == null && method.getArguments().size() == 1) {
                    String name = method.getSimpleName();
                    String property = name.startsWith("set") && name.length() > 3 ?
                            Character.toLowerCase(name.charAt(3)) + name.substring(4) : name;
                    J replaced = replace(method, property, method.getArguments().get(0));
                    if (replaced != null) {
                        return replaced;
                    }
                }
                return super.visitMethodInvocation(method, ctx);
            }

            private @Nullable J replace(Statement statement, String property, Expression value) {
                if (!("fileMode".equals(property) || "dirMode".equals(property)) || shadowed.contains(property) ||
                        !(value instanceof J.Literal) || !(((J.Literal) value).getValue() instanceof Integer) ||
                        !isInCopySpec(getCursor())) {
                    return null;
                }
                String block = "fileMode".equals(property) ? "filePermissions" : "dirPermissions";
                String snippet = block + " {\n    unix(#{any()})\n}";
                boolean kotlin = getCursor().firstEnclosing(JavaSourceFile.class) instanceof K.CompilationUnit;
                JavaTemplate template = kotlin ? KotlinTemplate.builder(snippet).build() : GroovyTemplate.builder(snippet).build();
                // The literal travels as a parameter, keeping its source text and so its radix. It loses its markers since
                // `fileMode 0644` marks the argument as having no parentheses, which would print as `unix 0644`.
                return template.apply(getCursor(), statement.getCoordinates().replace(),
                        value.withPrefix(Space.EMPTY).withMarkers(Markers.EMPTY));
            }
        });
    }

    private static boolean isInCopySpec(Cursor cursor) {
        // Scan outward so that a `from { }` in a Nebula task is recognized even though `from` alone looks fine
        Iterator<Cursor> path = cursor.getPathAsCursors(c -> c.getValue() instanceof J.MethodInvocation);
        boolean nearest = true;
        boolean copySpec = false;
        boolean nesting = false;
        while (path.hasNext()) {
            Cursor c = path.next();
            if (c == cursor) {
                continue;
            }
            J.MethodInvocation m = c.getValue();
            if (mentions(m, OWN_MODE_TYPES, OWN_MODE_TYPES) || OWN_MODE_TYPES.contains(m.getSimpleName())) {
                return false;
            }
            boolean isCopy = COPY_BLOCKS.contains(m.getSimpleName()) || mentions(m, COPY_TYPES, COPY_BLOCKS);
            if (nearest) {
                if (isCopy) {
                    copySpec = true;
                } else if (NESTED_COPY_BLOCKS.contains(m.getSimpleName())) {
                    nesting = true;
                } else {
                    return false;
                }
                nearest = false;
            } else if (nesting && !copySpec) {
                if (isCopy) {
                    copySpec = true;
                } else if (!NESTED_COPY_BLOCKS.contains(m.getSimpleName())) {
                    return false;
                }
            }
        }
        return copySpec;
    }

    /**
     * Whether anything the block is called on, or with, names one of these types or tasks. Its own closure doesn't count.
     */
    private static boolean mentions(J.MethodInvocation m, Set<String> types, Set<String> taskNames) {
        boolean[] found = {false};
        JavaIsoVisitor<Integer> visitor = new JavaIsoVisitor<Integer>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, Integer p) {
                found[0] |= types.contains(identifier.getSimpleName());
                return identifier;
            }

            @Override
            public J.Literal visitLiteral(J.Literal literal, Integer p) {
                found[0] |= literal.getValue() instanceof String && taskNames.contains(literal.getValue());
                return literal;
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

    private static Set<String> findOwnVariables(J tree) {
        Set<String> variables = new HashSet<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable variable, Integer p) {
                variables.add(variable.getSimpleName());
                return super.visitVariable(variable, p);
            }
        }.visit(tree, 0);
        return variables;
    }
}
