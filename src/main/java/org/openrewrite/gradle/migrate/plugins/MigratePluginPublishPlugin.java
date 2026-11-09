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
package org.openrewrite.gradle.migrate.plugins;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.groovy.GroovyTemplate;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaCoordinates;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Collections.singletonList;

@Value
@EqualsAndHashCode(callSuper = false)
public class MigratePluginPublishPlugin extends Recipe {

    private static final String PLUGIN_ID = "com.gradle.plugin-publish";
    private static final String NEW_VERSION = "1.3.1";

    /** Set on the project's own extension in 1.x. */
    private static final Set<String> BUNDLE_WIDE = new LinkedHashSet<>(java.util.Arrays.asList("website", "vcsUrl"));
    /** Set on each declared plugin in 1.x. */
    private static final Set<String> PER_PLUGIN = new LinkedHashSet<>(java.util.Arrays.asList("displayName", "description", "tags"));

    final String displayName = "Migrate the Plugin Publish plugin to 1.x";

    final String description = "`com.gradle.plugin-publish` before 1.0 calls `Jar.getArchivePath()` and " +
            "`DefaultDecoratedConvention`, removed in Gradle 8 and 9, so applying it fails with " +
            "\"Failed to notify project evaluation listener\" once the wrapper is upgraded. Version 1.0 removed the " +
            "`pluginBundle { }` block in turn, so bumping the version alone fails with " +
            "\"Could not find method pluginBundle()\". This recipe does both at once: it moves `website` and `vcsUrl` " +
            "onto `gradlePlugin`, and `description`, `displayName` and `tags` onto each plugin `gradlePlugin { plugins { } }` " +
            "declares, then removes `pluginBundle` and upgrades the plugin to " + NEW_VERSION + ". `tags = [..]` on the " +
            "declaration needs Gradle 8.2 or later, which `MigrateToGradle8` upgrades to. A script this recipe cannot " +
            "migrate in full keeps its old plugin version too, rather than being left bumped and broken: that is a " +
            "`pluginBundle` holding `mavenCoordinates`, a `version` other than the project's, or anything else " +
            "unrecognized, a declaration in `pluginBundle { plugins { } }` with no counterpart in " +
            "`gradlePlugin { plugins { } }`, or a missing `gradlePlugin { plugins { } }`. Kotlin scripts are left alone. " +
            "See the [plugin-publish 1.0 release notes](https://plugins.gradle.org/docs/publish-plugin) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public @Nullable J visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof G.CompilationUnit)) {
                    return super.visit(tree, ctx);
                }
                G.CompilationUnit cu = (G.CompilationUnit) tree;
                if (!declaresOldPluginPublish(cu)) {
                    return cu;
                }

                Bundle bundle = findBundle(cu);
                if (bundle != null && !bundle.migratable) {
                    // Leaving the version alone as well, so the build is not left bumped and broken.
                    return cu;
                }

                Cursor scope = new Cursor(getCursor(), cu);
                List<Statement> statements = cu.getStatements();
                if (bundle != null) {
                    List<Statement> moved = moveBundle(statements, bundle, scope, ctx);
                    if (moved == null) {
                        return cu;
                    }
                    statements = moved;
                }
                return cu.withStatements(ListUtils.map(statements, s -> upgradePluginVersion(s)));
            }
        });
    }

    /* ---------------------------------------------------------------- plugins { } version */

    private static boolean declaresOldPluginPublish(G.CompilationUnit cu) {
        for (Statement s : cu.getStatements()) {
            J.MethodInvocation block = asBlock(s, "plugins");
            if (block == null) {
                continue;
            }
            for (Statement entry : blockBody(block).getStatements()) {
                String version = declaredVersion(entry);
                if (version != null && isBefore1(version)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The version literal of an {@code id 'com.gradle.plugin-publish' version 'x'} declaration, or null. */
    private static @Nullable String declaredVersion(Statement entry) {
        J.MethodInvocation version = asInvocation(entry);
        if (version == null || !"version".equals(version.getSimpleName()) || version.getArguments().size() != 1) {
            return null;
        }
        if (!(version.getSelect() instanceof J.MethodInvocation)) {
            return null;
        }
        J.MethodInvocation id = (J.MethodInvocation) version.getSelect();
        if (!"id".equals(id.getSimpleName()) || id.getArguments().size() != 1 ||
                !PLUGIN_ID.equals(stringValue(id.getArguments().get(0)))) {
            return null;
        }
        return stringValue(version.getArguments().get(0));
    }

    private static boolean isBefore1(String version) {
        int dot = version.indexOf('.');
        String major = dot < 0 ? version : version.substring(0, dot);
        try {
            return Integer.parseInt(major.trim()) < 1;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Statement upgradePluginVersion(Statement s) {
        J.MethodInvocation block = asBlock(s, "plugins");
        if (block == null) {
            return s;
        }
        J.Block body = blockBody(block);
        List<Statement> entries = ListUtils.map(body.getStatements(), entry -> {
            String version = declaredVersion(entry);
            if (version == null || !isBefore1(version)) {
                return entry;
            }
            J.MethodInvocation call = asInvocation(entry);
            Expression arg = call.getArguments().get(0);
            if (!(arg instanceof J.Literal)) {
                return entry;
            }
            J.Literal literal = (J.Literal) arg;
            String quote = literal.getValueSource() != null && literal.getValueSource().startsWith("\"") ? "\"" : "'";
            J.Literal bumped = literal.withValue(NEW_VERSION).withValueSource(quote + NEW_VERSION + quote);
            J.MethodInvocation withVersion = call.withArguments(singletonList(bumped));
            return entry instanceof J.Return ? ((J.Return) entry).withExpression(withVersion) : withVersion;
        });
        if (entries == body.getStatements()) {
            return s;
        }
        J.MethodInvocation replaced = withBody(block, body.withStatements(entries));
        return s instanceof J.Return ? ((J.Return) s).withExpression(replaced) : replaced;
    }

    /* ---------------------------------------------------------------- pluginBundle { } */

    @Value
    private static class Bundle {
        boolean migratable;
        /** website / vcsUrl, in declaration order. */
        Map<String, Expression> projectWide;
        /** displayName / description / tags declared for every plugin. */
        Map<String, Expression> forEveryPlugin;
        /** The same, declared for one named plugin. */
        Map<String, Map<String, Expression>> perPlugin;

        static Bundle notMigratable() {
            return new Bundle(false, new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
        }
    }

    private static @Nullable Bundle findBundle(G.CompilationUnit cu) {
        Bundle found = null;
        for (Statement s : cu.getStatements()) {
            J.MethodInvocation block = asBlock(s, "pluginBundle");
            if (block == null) {
                continue;
            }
            if (found != null) {
                return Bundle.notMigratable();
            }
            found = readBundle(block);
        }
        return found;
    }

    private static Bundle readBundle(J.MethodInvocation bundle) {
        Map<String, Expression> projectWide = new LinkedHashMap<>();
        Map<String, Expression> everyPlugin = new LinkedHashMap<>();
        Map<String, Map<String, Expression>> perPlugin = new LinkedHashMap<>();

        for (Statement s : blockBody(bundle).getStatements()) {
            J.Assignment assignment = asAssignment(s);
            if (assignment != null) {
                String name = assignedName(assignment);
                if (name == null) {
                    return Bundle.notMigratable();
                }
                if (BUNDLE_WIDE.contains(name)) {
                    projectWide.put(name, assignment.getAssignment());
                } else if (PER_PLUGIN.contains(name)) {
                    everyPlugin.put(name, assignment.getAssignment());
                } else if ("version".equals(name)) {
                    // 1.x publishes the project's own version, so only that value is safe to drop.
                    if (!isProjectVersion(assignment.getAssignment())) {
                        return Bundle.notMigratable();
                    }
                } else {
                    return Bundle.notMigratable();
                }
                continue;
            }

            J.MethodInvocation nested = asBlock(s, "plugins");
            if (nested == null) {
                // mavenCoordinates { }, or anything else this recipe does not understand.
                return Bundle.notMigratable();
            }
            for (Statement declaration : blockBody(nested).getStatements()) {
                J.MethodInvocation named = asAnyBlock(declaration);
                if (named == null) {
                    return Bundle.notMigratable();
                }
                Map<String, Expression> properties = new LinkedHashMap<>();
                for (Statement property : blockBody(named).getStatements()) {
                    J.Assignment a = asAssignment(property);
                    String name = a == null ? null : assignedName(a);
                    if (name == null) {
                        return Bundle.notMigratable();
                    }
                    if ("id".equals(name)) {
                        // gradlePlugin { plugins { } } already declares the id.
                        continue;
                    }
                    if (!PER_PLUGIN.contains(name)) {
                        return Bundle.notMigratable();
                    }
                    properties.put(name, a.getAssignment());
                }
                perPlugin.put(named.getSimpleName(), properties);
            }
        }
        return new Bundle(true, projectWide, everyPlugin, perPlugin);
    }

    /**
     * Removes the {@code pluginBundle { }} block and puts what it held on {@code gradlePlugin}.
     * Returns null when the script has no {@code gradlePlugin { plugins { } }} to move it into, or when
     * {@code pluginBundle} named a plugin that block does not declare.
     */
    private static @Nullable List<Statement> moveBundle(List<Statement> statements, Bundle bundle, Cursor scope, ExecutionContext ctx) {
        J.MethodInvocation gradlePlugin = null;
        for (Statement s : statements) {
            J.MethodInvocation block = asBlock(s, "gradlePlugin");
            if (block != null) {
                gradlePlugin = block;
            }
        }
        if (gradlePlugin == null) {
            return null;
        }
        List<String> declared = declaredPluginNames(gradlePlugin);
        if (declared == null || declared.isEmpty() || !declared.containsAll(bundle.perPlugin.keySet())) {
            return null;
        }

        return ListUtils.map(statements, s -> {
            if (asBlock(s, "pluginBundle") != null) {
                return null;
            }
            J.MethodInvocation block = asBlock(s, "gradlePlugin");
            if (block == null) {
                return s;
            }
            J.MethodInvocation withBundle = addToGradlePlugin(block, bundle, scope, ctx);
            return s instanceof J.Return ? ((J.Return) s).withExpression(withBundle) : withBundle;
        });
    }

    private static @Nullable List<String> declaredPluginNames(J.MethodInvocation gradlePlugin) {
        for (Statement s : blockBody(gradlePlugin).getStatements()) {
            J.MethodInvocation plugins = asBlock(s, "plugins");
            if (plugins == null) {
                continue;
            }
            List<String> names = new ArrayList<>();
            for (Statement declaration : blockBody(plugins).getStatements()) {
                J.MethodInvocation named = asAnyBlock(declaration);
                if (named == null) {
                    return null;
                }
                names.add(named.getSimpleName());
            }
            return names;
        }
        return null;
    }

    private static J.MethodInvocation addToGradlePlugin(J.MethodInvocation gradlePlugin, Bundle bundle, Cursor scope, ExecutionContext ctx) {
        J.MethodInvocation result = gradlePlugin;
        if (!bundle.projectWide.isEmpty()) {
            Map<String, Expression> missing = missingFrom(blockBody(result).getStatements(), bundle.projectWide);
            if (!missing.isEmpty()) {
                result = addAssignments(result, missing, scope, true);
            }
        }

        // The template indents what it inserts from the cursor it is given, so each level's own cursor
        // has to be passed down rather than the compilation unit's.
        Cursor gradlePluginCursor = new Cursor(scope, result);
        J.Block body = blockBody(result);
        List<Statement> withPlugins = ListUtils.map(body.getStatements(), s -> {
            J.MethodInvocation plugins = asBlock(s, "plugins");
            if (plugins == null) {
                return s;
            }
            Cursor pluginsCursor = new Cursor(gradlePluginCursor, plugins);
            J.Block pluginsBody = blockBody(plugins);
            List<Statement> declarations = ListUtils.map(pluginsBody.getStatements(), declaration -> {
                J.MethodInvocation named = asAnyBlock(declaration);
                if (named == null) {
                    return declaration;
                }
                Map<String, Expression> wanted = new LinkedHashMap<>(bundle.forEveryPlugin);
                Map<String, Expression> own = bundle.perPlugin.get(named.getSimpleName());
                if (own != null) {
                    wanted.putAll(own);
                }
                Map<String, Expression> missing = missingFrom(blockBody(named).getStatements(), wanted);
                if (missing.isEmpty()) {
                    return declaration;
                }
                J.MethodInvocation filled = addAssignments(named, missing, pluginsCursor, false);
                return declaration instanceof J.Return ? ((J.Return) declaration).withExpression(filled) : filled;
            });
            J.MethodInvocation replaced = withBody(plugins, pluginsBody.withStatements(declarations));
            return s instanceof J.Return ? ((J.Return) s).withExpression(replaced) : replaced;
        });
        return withBody(result, blockBody(result).withStatements(withPlugins));
    }

    private static Map<String, Expression> missingFrom(List<Statement> statements, Map<String, Expression> wanted) {
        Map<String, Expression> missing = new LinkedHashMap<>(wanted);
        for (Statement s : statements) {
            J.Assignment a = asAssignment(s);
            if (a != null) {
                missing.remove(assignedName(a));
            }
        }
        return missing;
    }

    /**
     * Adds assignments to a {@code name { }} block, at the top of its body or after its last statement.
     * The template is applied with the block itself as the cursor's value, so it is what comes back.
     */
    private static J.MethodInvocation addAssignments(J.MethodInvocation block, Map<String, Expression> assignments,
                                                     Cursor scope, boolean atTop) {
        J.Block body = blockBody(block);
        List<Statement> before = body.getStatements();
        JavaCoordinates coordinates = atTop && !before.isEmpty() ?
                before.get(0).getCoordinates().before() :
                body.getCoordinates().lastStatement();
        J.MethodInvocation applied =
                template(assignments).apply(new Cursor(scope, block), coordinates, values(assignments));

        // The template indents what it inserts one level in from the start of the file rather than from
        // the block it goes into, so each inserted statement takes the indentation of the ones it joins.
        J.Block appliedBody = blockBody(applied);
        List<Statement> after = appliedBody.getStatements();
        int added = after.size() - before.size();
        if (added <= 0 || before.isEmpty()) {
            return applied;
        }
        int first = atTop ? 0 : before.size();
        Space prefix = (atTop ? after.get(added) : after.get(0)).getPrefix();
        List<Statement> indented = new ArrayList<>(after);
        for (int i = first; i < first + added; i++) {
            indented.set(i, indented.get(i).withPrefix(prefix));
        }
        return withBody(applied, appliedBody.withStatements(indented));
    }

    private static GroovyTemplate template(Map<String, Expression> assignments) {
        StringBuilder snippet = new StringBuilder();
        for (String name : assignments.keySet()) {
            if (snippet.length() > 0) {
                snippet.append('\n');
            }
            snippet.append(name).append(" = #{any()}");
        }
        return GroovyTemplate.builder(snippet.toString()).build();
    }

    private static Object[] values(Map<String, Expression> assignments) {
        List<Object> values = new ArrayList<>();
        for (Expression e : assignments.values()) {
            values.add(e.withPrefix(Space.EMPTY));
        }
        return values.toArray();
    }

    /* ---------------------------------------------------------------- AST helpers */

    private static J.@Nullable MethodInvocation asBlock(Statement s, String name) {
        J.MethodInvocation m = asAnyBlock(s);
        return m != null && name.equals(m.getSimpleName()) && m.getSelect() == null ? m : null;
    }

    /** A {@code name { }} call with a single closure argument, however it is named. */
    private static J.@Nullable MethodInvocation asAnyBlock(Statement s) {
        J.MethodInvocation m = asInvocation(s);
        if (m == null || m.getArguments().size() != 1) {
            return null;
        }
        Expression arg = m.getArguments().get(0);
        if (!(arg instanceof J.Lambda) || !(((J.Lambda) arg).getBody() instanceof J.Block)) {
            return null;
        }
        return m;
    }

    private static J.Block blockBody(J.MethodInvocation block) {
        return (J.Block) ((J.Lambda) block.getArguments().get(0)).getBody();
    }

    private static J.MethodInvocation withBody(J.MethodInvocation block, J.Block body) {
        J.Lambda lambda = (J.Lambda) block.getArguments().get(0);
        return block.withArguments(singletonList(lambda.withBody(body)));
    }

    private static J.@Nullable MethodInvocation asInvocation(Statement s) {
        if (s instanceof J.MethodInvocation) {
            return (J.MethodInvocation) s;
        }
        if (s instanceof J.Return) {
            // The last statement of a closure body is wrapped in a return.
            Expression e = ((J.Return) s).getExpression();
            if (e instanceof J.MethodInvocation) {
                return (J.MethodInvocation) e;
            }
        }
        return null;
    }

    private static J.@Nullable Assignment asAssignment(Statement s) {
        if (s instanceof J.Assignment) {
            return (J.Assignment) s;
        }
        if (s instanceof J.Return) {
            Expression e = ((J.Return) s).getExpression();
            if (e instanceof J.Assignment) {
                return (J.Assignment) e;
            }
        }
        return null;
    }

    private static @Nullable String assignedName(J.Assignment a) {
        Expression variable = a.getVariable();
        if (variable instanceof J.Identifier) {
            return ((J.Identifier) variable).getSimpleName();
        }
        return null;
    }

    private static @Nullable String stringValue(Expression e) {
        if (e instanceof J.Literal && ((J.Literal) e).getType() == JavaType.Primitive.String) {
            Object value = ((J.Literal) e).getValue();
            return value == null ? null : value.toString();
        }
        return null;
    }

    /** Whether an expression is {@code project.version}, the one value 1.x publishes by itself. */
    private static boolean isProjectVersion(Expression e) {
        if (!(e instanceof J.FieldAccess)) {
            return false;
        }
        J.FieldAccess access = (J.FieldAccess) e;
        return "version".equals(access.getSimpleName()) &&
               access.getTarget() instanceof J.Identifier &&
               "project".equals(((J.Identifier) access.getTarget()).getSimpleName());
    }
}
