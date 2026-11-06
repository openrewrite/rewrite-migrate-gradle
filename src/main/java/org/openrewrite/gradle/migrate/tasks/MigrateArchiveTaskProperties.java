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
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.gradle.migrate.internal.TaskScope;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.marker.OmitParentheses;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JContainer;
import org.openrewrite.java.tree.JLeftPadded;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.Space;
import org.openrewrite.marker.Markers;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static java.util.Collections.emptyList;

@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateArchiveTaskProperties extends Recipe {

    private static final Map<String, String> RENAMES;
    private static final Map<String, String> ARCHIVE_TYPES;
    private static final Set<String> ARCHIVE_TASKS = new HashSet<>(Arrays.asList(
            "bootJar", "bootWar", "distTar", "distZip", "ear", "jar", "shadowJar", "war"));

    static {
        Map<String, String> renames = new HashMap<>();
        renames.put("appendix", "archiveAppendix");
        renames.put("archiveName", "archiveFileName");
        renames.put("baseName", "archiveBaseName");
        renames.put("classifier", "archiveClassifier");
        renames.put("destinationDir", "destinationDirectory");
        renames.put("extension", "archiveExtension");
        renames.put("version", "archiveVersion");
        RENAMES = Collections.unmodifiableMap(renames);

        Map<String, String> types = new HashMap<>();
        types.put("AbstractArchiveTask", "org.gradle.api.tasks.bundling.AbstractArchiveTask");
        types.put("BootJar", "org.springframework.boot.gradle.tasks.bundling.BootJar");
        types.put("BootWar", "org.springframework.boot.gradle.tasks.bundling.BootWar");
        types.put("Ear", "org.gradle.plugins.ear.Ear");
        types.put("Jar", "org.gradle.api.tasks.bundling.Jar");
        types.put("ShadowJar", "com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar");
        types.put("Tar", "org.gradle.api.tasks.bundling.Tar");
        types.put("War", "org.gradle.api.tasks.bundling.War");
        types.put("Zip", "org.gradle.api.tasks.bundling.Zip");
        ARCHIVE_TYPES = Collections.unmodifiableMap(types);
    }

    String displayName = "Migrate archive task properties";

    String description = "Gradle 8.0 removed `baseName`, `appendix`, `archiveName`, `classifier`, `destinationDir` and `extension` from " +
            "`AbstractArchiveTask`, so setting them now fails on Gradle 8.14.4 and 9.8.2. It also removed `version`, which " +
            "doesn't fail: inside an archive task `version = 'x'` now sets the project's version instead. This recipe " +
            "rewrites writes of these properties to `archiveBaseName`, `archiveAppendix`, `archiveFileName`, `archiveClassifier`, " +
            "`destinationDirectory`, `archiveExtension` and `archiveVersion`, in Groovy and Kotlin, but only where the " +
            "script proves the task is an archive task. A string given for `destinationDir` is wrapped in `file(..)`, " +
            "since `destinationDirectory` doesn't accept one before Gradle 9. Reads such as `jar.archivePath` change type " +
            "and are left alone, and a non-string literal for `version` is left alone since `archiveVersion` rejects it on Gradle 8. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/8.14/userguide/upgrading_version_7.html#v7_abstract_archive_task_api_cleanup).";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaVisitor<ExecutionContext>() {
            private @Nullable TaskScope scope;

            private TaskScope scope() {
                JavaSourceFile sourceFile = getCursor().firstEnclosingOrThrow(JavaSourceFile.class);
                if (scope == null || !scope.isFor(sourceFile)) {
                    scope = new TaskScope(sourceFile, ARCHIVE_TYPES, ARCHIVE_TASKS::contains);
                }
                return scope;
            }

            @Override
            public J visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
                J.Assignment a = (J.Assignment) super.visitAssignment(assignment, ctx);
                Expression variable = a.getVariable();
                String property = propertyName(variable);
                if (property == null || !RENAMES.containsKey(property) || !isSafeValue(property, a.getAssignment())) {
                    return a;
                }
                if (variable instanceof J.Identifier ?
                        !scope().isLexical(property) && scope().isInsideTaskClosure(getCursor()) :
                        scope().isTaskReference(((J.FieldAccess) variable).getTarget(), getCursor())) {
                    return a.withVariable(renamed(variable, RENAMES.get(property)))
                            .withAssignment(value(property, a.getAssignment()));
                }
                return a;
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
                String property = setterProperty(m);
                if (property == null || m.getArguments().size() != 1 || m.getArguments().get(0) instanceof J.Lambda ||
                    m.getArguments().get(0) instanceof J.Empty || !isStatement() ||
                    !isSafeValue(property, m.getArguments().get(0))) {
                    return m;
                }
                if (m.getSelect() == null ?
                        !scope().isLexical(m.getSimpleName()) && scope().isInsideTaskClosure(getCursor()) :
                        scope().isTaskReference(m.getSelect(), getCursor())) {
                    Expression target = m.getName().withSimpleName(RENAMES.get(property)).withPrefix(Space.EMPTY)
                            .withType(null).withFieldType(null);
                    if (m.getSelect() != null) {
                        target = new J.FieldAccess(Tree.randomId(), Space.EMPTY, Markers.EMPTY, m.getSelect(),
                                new JLeftPadded<>(Space.EMPTY, m.getName().withSimpleName(RENAMES.get(property))
                                        .withPrefix(Space.EMPTY).withType(null).withFieldType(null), Markers.EMPTY), null);
                    }
                    Expression rhs = value(property, m.getArguments().get(0)).withPrefix(Space.SINGLE_SPACE);
                    return new J.Assignment(Tree.randomId(), m.getPrefix(), Markers.EMPTY, target,
                            new JLeftPadded<>(Space.SINGLE_SPACE, rhs, Markers.EMPTY), null);
                }
                return m;
            }

            private @Nullable String setterProperty(J.MethodInvocation m) {
                String name = m.getSimpleName();
                if (name.length() > 3 && name.startsWith("set")) {
                    String property = Character.toLowerCase(name.charAt(3)) + name.substring(4);
                    return RENAMES.containsKey(property) ? property : null;
                }
                // Only Groovy turns a property into a method of its name
                return getCursor().firstEnclosing(JavaSourceFile.class) instanceof G.CompilationUnit &&
                       RENAMES.containsKey(name) ? name : null;
            }

            private boolean isStatement() {
                Object parent = getCursor().getParentTreeCursor().getValue();
                return parent instanceof J.Block || parent instanceof J.Return || parent instanceof JavaSourceFile;
            }
        });
    }

    private static @Nullable String propertyName(Expression variable) {
        if (variable instanceof J.Identifier) {
            return ((J.Identifier) variable).getSimpleName();
        }
        return variable instanceof J.FieldAccess ? ((J.FieldAccess) variable).getSimpleName() : null;
    }

    private static Expression renamed(Expression variable, String newName) {
        if (variable instanceof J.Identifier) {
            return ((J.Identifier) variable).withSimpleName(newName).withType(null).withFieldType(null);
        }
        J.FieldAccess field = (J.FieldAccess) variable;
        return field.withName(field.getName().withSimpleName(newName).withType(null).withFieldType(null)).withType(null);
    }

    /**
     * The old `version` setter took anything Groovy could cast to a String. `archiveVersion` on Gradle 8 takes only a String.
     */
    private static boolean isSafeValue(String property, Expression value) {
        return !"version".equals(property) || !(value instanceof J.Literal) ||
               ((J.Literal) value).getValue() == null || ((J.Literal) value).getValue() instanceof String;
    }

    /**
     * `destinationDirectory` rejects a String on Gradle 8 and accepts it only from Gradle 9, where a File works too.
     */
    private static Expression value(String property, Expression value) {
        if ("destinationDir".equals(property) && isString(value)) {
            return fileCall(value);
        }
        return value;
    }

    private static boolean isString(Expression value) {
        return value instanceof G.GString || value instanceof J.Literal && ((J.Literal) value).getValue() instanceof String;
    }

    private static J.MethodInvocation fileCall(Expression value) {
        J.Identifier name = new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, emptyList(), "file", null, null);
        return new J.MethodInvocation(Tree.randomId(), value.getPrefix(), Markers.EMPTY, null, null, name,
                JContainer.build(Space.EMPTY, Collections.singletonList(JRightPadded.build(
                        // The value of `destinationDir 'x'` is marked as printed without parentheses, which `file('x')` must not inherit
                        value.withPrefix(Space.EMPTY).withMarkers(value.getMarkers().removeByType(OmitParentheses.class)))), Markers.EMPTY),
                null);
    }
}
