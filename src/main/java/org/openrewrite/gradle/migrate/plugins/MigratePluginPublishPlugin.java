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
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
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

import org.openrewrite.properties.tree.Properties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;

@Value
@EqualsAndHashCode(callSuper = false)
public class MigratePluginPublishPlugin extends ScanningRecipe<MigratePluginPublishPlugin.Accumulator> {

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
            "declares, then removes `pluginBundle` and upgrades the plugin to " + NEW_VERSION + ". A script already on " +
            "1.x that kept its `pluginBundle` block has that block moved too, without its version being touched: such a " +
            "build fails as it stands with \"Could not find method pluginBundle()\". `tags = [..]` on the " +
            "declaration needs Gradle 8.2 or later, which `MigrateToGradle8` upgrades to. A script with no " +
            "`gradlePlugin { plugins { } }` of its own, which declares its plugins only in " +
            "`pluginBundle { plugins { } }`, has that block written for it, taking each `implementationClass` from the " +
            "checked-in `META-INF/gradle-plugins/<id>.properties` descriptor, which is then deleted: Gradle generates " +
            "it from the declaration, and a checked-in copy of what it generates makes `processResources` fail on a " +
            "duplicate entry from Gradle 7 on. A redundant descriptor is deleted for a plugin the script already " +
            "declares, too. A `pluginBundle` this recipe cannot migrate leaves the whole repository alone, its other " +
            "scripts and their plugin versions included, rather than leaving it bumped and broken: Gradle resolves the " +
            "plugin for the build, so a block left behind in one script breaks the build whichever script declares the " +
            "version. What it cannot migrate is a `pluginBundle` holding `mavenCoordinates`, a `version` other than the " +
            "project's, or anything else unrecognized, a declaration in `pluginBundle { plugins { } }` with no " +
            "counterpart in `gradlePlugin { plugins { } }`, and a plugin declared in neither block or with no " +
            "descriptor to take its implementation class from. Kotlin scripts are left alone. " +
            "See the [plugin-publish 1.0 release notes](https://plugins.gradle.org/docs/publish-plugin) for more information.";

    /**
     * What the scan of every file found: the checked-in plugin descriptors, and what each build script
     * would need from them. A descriptor has to be read before a script that needs it can be migrated,
     * and the order files are visited in says nothing about which comes first, so the two phases are
     * reconciled in {@link #generate}.
     */
    public static class Accumulator {
        /** Each {@code META-INF/gradle-plugins/<id>.properties}, and the implementation class it names. */
        final Map<Path, String> descriptors = new HashMap<>();
        /** Each migratable build script that declares plugin-publish 0.x. */
        final Map<Path, Request> requests = new HashMap<>();
        /** The implementation class of each plugin a script has to declare itself, by declaration name. */
        final Map<Path, Map<String, String>> implementationClasses = new HashMap<>();
        /** Descriptors Gradle generates once the script declares the plugin, so the checked-in copy goes. */
        final Set<Path> deletable = new HashSet<>();
        /** Every script holding a {@code pluginBundle} block, and whether the block can move where it is. */
        final Map<Path, Boolean> bundles = new HashMap<>();
        /** The scripts whose {@code pluginBundle} this recipe would leave behind, settled in {@link #generate}. */
        final Set<Path> leftBehind = new HashSet<>();
    }

    /** What one build script needs looked up, before any descriptor has been read. */
    @Value
    static class Request {
        /** The script's own directory, so a sibling module's descriptor is not taken for this one. */
        @Nullable
        Path dir;
        /** The id of each plugin the script has to declare itself, by declaration name. */
        Map<String, String> idsToDeclare;
        /** The implementation class the script already declares for an id, which makes a descriptor redundant. */
        Map<String, String> declaredImplementationClasses;
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof Properties.File) {
                    Properties.File file = (Properties.File) tree;
                    String implementationClass = implementationClassOf(file);
                    if (isPluginDescriptor(file.getSourcePath()) && implementationClass != null) {
                        acc.descriptors.put(file.getSourcePath(), implementationClass);
                    }
                    return tree;
                }
                if (!(tree instanceof G.CompilationUnit) || !isGradleScript(((G.CompilationUnit) tree).getSourcePath())) {
                    return tree;
                }
                G.CompilationUnit cu = (G.CompilationUnit) tree;
                Bundle bundle = findBundle(cu);
                if (bundle == null) {
                    return tree;
                }
                J.MethodInvocation gradlePlugin = findGradlePlugin(cu.getStatements());
                // Recorded for every block, migratable or not, because one this recipe cannot move
                // decides what happens to the whole repository.
                acc.bundles.put(cu.getSourcePath(), bundle.migratable && declaresPluginPublish(cu) &&
                        gradlePlugin != null && canTakeBundle(gradlePlugin, bundle));
                if (!bundle.migratable || !declaresPluginPublish(cu)) {
                    return tree;
                }
                acc.requests.put(cu.getSourcePath(), gradlePlugin == null ?
                        new Request(cu.getSourcePath().getParent(), bundle.declaredIds, emptyMap()) :
                        new Request(cu.getSourcePath().getParent(), emptyMap(), declaredImplementationClasses(gradlePlugin)));
                return tree;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        for (Map.Entry<Path, Request> entry : acc.requests.entrySet()) {
            Request request = entry.getValue();

            // Where the script already declares the plugin, Gradle generates the descriptor from that
            // declaration, and a checked-in copy of it makes processResources fail on a duplicate entry.
            for (Map.Entry<String, String> declared : request.declaredImplementationClasses.entrySet()) {
                Path descriptor = descriptorIn(acc, request.dir, declared.getKey());
                if (descriptor != null && declared.getValue().equals(acc.descriptors.get(descriptor))) {
                    acc.deletable.add(descriptor);
                }
            }

            if (request.idsToDeclare.isEmpty()) {
                continue;
            }
            Map<String, String> implementationClasses = new LinkedHashMap<>();
            for (Map.Entry<String, String> toDeclare : request.idsToDeclare.entrySet()) {
                Path descriptor = descriptorIn(acc, request.dir, toDeclare.getValue());
                String implementationClass = descriptor == null ? null : acc.descriptors.get(descriptor);
                if (implementationClass == null) {
                    // No implementation class to declare, so this script is not migratable after all.
                    implementationClasses.clear();
                    break;
                }
                implementationClasses.put(toDeclare.getKey(), implementationClass);
            }
            if (!implementationClasses.isEmpty()) {
                acc.implementationClasses.put(entry.getKey(), implementationClasses);
                for (String id : request.idsToDeclare.values()) {
                    Path descriptor = descriptorIn(acc, request.dir, id);
                    if (descriptor != null) {
                        acc.deletable.add(descriptor);
                    }
                }
            }
        }

        // Which blocks are left behind can only be settled once the descriptors are reconciled above,
        // since a script with no gradlePlugin { plugins { } } of its own is migratable exactly when one
        // was found for every plugin it declares.
        for (Map.Entry<Path, Boolean> bundle : acc.bundles.entrySet()) {
            if (!bundle.getValue() && !acc.implementationClasses.containsKey(bundle.getKey())) {
                acc.leftBehind.add(bundle.getKey());
            }
        }
        if (!acc.leftBehind.isEmpty()) {
            // Gradle resolves plugin-publish for the build rather than for one script, so a block left
            // anywhere in the repository calls a method 1.x does not have wherever the version is
            // declared. The repository is the unit: all of it migrates, or none of it does.
            acc.deletable.clear();
            acc.implementationClasses.clear();
        }
        return emptyList();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof Properties.File) {
                    return acc.deletable.contains(((Properties.File) tree).getSourcePath()) ? null : tree;
                }
                if (!(tree instanceof G.CompilationUnit) || !isGradleScript(((G.CompilationUnit) tree).getSourcePath())) {
                    return tree;
                }
                G.CompilationUnit cu = (G.CompilationUnit) tree;
                if (!acc.leftBehind.isEmpty()) {
                    // A block this recipe cannot move, anywhere in the repository. See generate.
                    return cu;
                }

                Bundle bundle = findBundle(cu);
                // A `pluginBundle { }` left behind on 1.x fails the build as it stands, with
                // "Could not find method pluginBundle()", so the block is moved whatever version the
                // script asks for; only a version below 1 is bumped. With no block to move, there is
                // nothing to do unless the version itself is old.
                if (!(bundle == null ? declaresOldPluginPublish(cu) : declaresPluginPublish(cu))) {
                    return cu;
                }

                if (bundle != null && !bundle.migratable) {
                    // Leaving the version alone as well, so the build is not left bumped and broken.
                    return cu;
                }

                Cursor scope = new Cursor(getCursor(), cu);
                List<Statement> statements = cu.getStatements();
                if (bundle != null) {
                    Map<String, String> implementationClasses = acc.implementationClasses.get(cu.getSourcePath());
                    List<Statement> moved = implementationClasses == null ?
                            moveBundle(statements, bundle, scope, ctx) :
                            declareBundle(statements, bundle, implementationClasses, scope);
                    if (moved == null) {
                        return cu;
                    }
                    statements = moved;
                }
                return cu.withStatements(ListUtils.map(statements, s -> upgradePluginVersion(s)));
            }
        };
    }

    /* ---------------------------------------------------------------- plugin descriptors */

    private static boolean isGradleScript(Path path) {
        return path.getFileName().toString().endsWith(".gradle");
    }

    private static boolean isPluginDescriptor(Path path) {
        Path parent = path.getParent();
        return path.getFileName().toString().endsWith(".properties") &&
               parent != null && parent.toString().replace('\\', '/').endsWith("META-INF/gradle-plugins");
    }

    private static @Nullable String implementationClassOf(Properties.File file) {
        for (Properties.Content content : file.getContent()) {
            if (content instanceof Properties.Entry) {
                Properties.Entry entry = (Properties.Entry) content;
                if ("implementation-class".equals(entry.getKey())) {
                    return entry.getValue().getText().trim();
                }
            }
        }
        return null;
    }

    /**
     * The descriptor of one plugin id belonging to the module the script is in. Anything below a
     * directory of its own, such as a subproject, belongs to that subproject's script instead, so only
     * what is under the module's own {@code src} counts.
     */
    private static @Nullable Path descriptorIn(Accumulator acc, @Nullable Path dir, String id) {
        String name = id + ".properties";
        for (Path descriptor : acc.descriptors.keySet()) {
            if (!name.equals(descriptor.getFileName().toString())) {
                continue;
            }
            Path relative = dir == null ? descriptor : (descriptor.startsWith(dir) ? dir.relativize(descriptor) : null);
            if (relative != null && "src".equals(relative.getName(0).toString())) {
                return descriptor;
            }
        }
        return null;
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

    /** Whether {@code plugins { }} asks for plugin-publish at all, whatever version it names. */
    private static boolean declaresPluginPublish(G.CompilationUnit cu) {
        for (Statement s : cu.getStatements()) {
            J.MethodInvocation block = asBlock(s, "plugins");
            if (block == null) {
                continue;
            }
            for (Statement entry : blockBody(block).getStatements()) {
                if (declaresPluginPublishId(entry)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether one {@code plugins { }} entry names plugin-publish. The id call is the innermost of the
     * chain, so this walks down the selects of {@code id 'x' version 'y' apply false} alike, and matches
     * {@code id 'x'} on its own, where the version is declared somewhere else.
     */
    private static boolean declaresPluginPublishId(Statement entry) {
        J.MethodInvocation call = asInvocation(entry);
        while (call != null) {
            if ("id".equals(call.getSimpleName()) && call.getArguments().size() == 1 &&
                    PLUGIN_ID.equals(stringValue(call.getArguments().get(0)))) {
                return true;
            }
            call = call.getSelect() instanceof J.MethodInvocation ? (J.MethodInvocation) call.getSelect() : null;
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
        /** The plugin id each declaration names, where it names one as a literal. */
        Map<String, String> declaredIds;

        static Bundle notMigratable() {
            return new Bundle(false, new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
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
        Map<String, String> declaredIds = new LinkedHashMap<>();

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
                        // Where gradlePlugin { plugins { } } declares the plugin, it holds the id already.
                        // Where it does not, this is the only place the id is written down.
                        String id = stringValue(a.getAssignment());
                        if (id != null) {
                            declaredIds.put(named.getSimpleName(), id);
                        }
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
        return new Bundle(true, projectWide, everyPlugin, perPlugin, declaredIds);
    }

    /**
     * Removes the {@code pluginBundle { }} block and puts what it held on {@code gradlePlugin}.
     * Returns null when the script has no {@code gradlePlugin { plugins { } }} to move it into, or when
     * {@code pluginBundle} named a plugin that block does not declare.
     */
    private static J.@Nullable MethodInvocation findGradlePlugin(List<Statement> statements) {
        J.MethodInvocation found = null;
        for (Statement s : statements) {
            J.MethodInvocation block = asBlock(s, "gradlePlugin");
            if (block != null && declaredPluginNames(block) != null) {
                found = block;
            }
        }
        return found;
    }

    /** The implementation class each declared plugin id names, for the ids declared as literals. */
    private static Map<String, String> declaredImplementationClasses(J.MethodInvocation gradlePlugin) {
        Map<String, String> byId = new LinkedHashMap<>();
        for (Statement s : blockBody(gradlePlugin).getStatements()) {
            J.MethodInvocation plugins = asBlock(s, "plugins");
            if (plugins == null) {
                continue;
            }
            for (Statement declaration : blockBody(plugins).getStatements()) {
                J.MethodInvocation named = asAnyBlock(declaration);
                if (named == null) {
                    continue;
                }
                String id = null;
                String implementationClass = null;
                for (Statement property : blockBody(named).getStatements()) {
                    J.Assignment a = asAssignment(property);
                    String name = a == null ? null : assignedName(a);
                    if ("id".equals(name)) {
                        id = stringValue(a.getAssignment());
                    } else if ("implementationClass".equals(name)) {
                        implementationClass = stringValue(a.getAssignment());
                    }
                }
                if (id != null && implementationClass != null) {
                    byId.put(id, implementationClass);
                }
            }
        }
        return byId;
    }

    private static @Nullable List<Statement> moveBundle(List<Statement> statements, Bundle bundle, Cursor scope, ExecutionContext ctx) {
        J.MethodInvocation gradlePlugin = findGradlePlugin(statements);
        if (gradlePlugin == null || !canTakeBundle(gradlePlugin, bundle)) {
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

    /**
     * Replaces the {@code pluginBundle { }} block with the {@code gradlePlugin { }} block 1.x reads, for a
     * script that declares its plugins nowhere else. Each implementation class comes from the checked-in
     * {@code META-INF/gradle-plugins} descriptor, which goes, since Gradle generates it from the
     * declaration. Returns null when a declaration names no plugin id, so there is nothing to look up.
     */
    private static @Nullable List<Statement> declareBundle(List<Statement> statements, Bundle bundle,
                                                           Map<String, String> implementationClasses, Cursor scope) {
        if (bundle.perPlugin.isEmpty() ||
            !bundle.declaredIds.keySet().containsAll(bundle.perPlugin.keySet()) ||
            !implementationClasses.keySet().containsAll(bundle.perPlugin.keySet())) {
            return null;
        }

        StringBuilder snippet = new StringBuilder("gradlePlugin {\n");
        List<Object> values = new ArrayList<>();
        for (Map.Entry<String, Expression> property : bundle.projectWide.entrySet()) {
            snippet.append("    ").append(property.getKey()).append(" = #{any()}\n");
            values.add(property.getValue().withPrefix(Space.EMPTY));
        }
        snippet.append("    plugins {\n");
        for (Map.Entry<String, Map<String, Expression>> declaration : bundle.perPlugin.entrySet()) {
            String name = declaration.getKey();
            snippet.append("        ").append(name).append(" {\n");
            snippet.append("            id = ").append(quote(bundle.declaredIds.get(name))).append('\n');
            snippet.append("            implementationClass = ").append(quote(implementationClasses.get(name))).append('\n');
            Map<String, Expression> properties = new LinkedHashMap<>(bundle.forEveryPlugin);
            properties.putAll(declaration.getValue());
            for (Map.Entry<String, Expression> property : properties.entrySet()) {
                snippet.append("            ").append(property.getKey()).append(" = #{any()}\n");
                values.add(property.getValue().withPrefix(Space.EMPTY));
            }
            snippet.append("        }\n");
        }
        snippet.append("    }\n}");

        GroovyTemplate template = GroovyTemplate.builder(snippet.toString()).build();
        return ListUtils.map(statements, s -> {
            if (asBlock(s, "pluginBundle") == null) {
                return s;
            }
            return (Statement) template.apply(new Cursor(scope, s), s.getCoordinates().replace(), values.toArray());
        });
    }

    /** A Groovy single-quoted string, for the two values that come from a properties file. */
    private static String quote(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /**
     * Whether {@code gradlePlugin { plugins { } }} declares every plugin the bundle names, which is what
     * the bundle's per-plugin values are moved onto. Asked of the scan as well as of the edit, so that
     * what the scan counts as left behind is what the edit leaves behind.
     */
    private static boolean canTakeBundle(J.MethodInvocation gradlePlugin, Bundle bundle) {
        List<String> declared = declaredPluginNames(gradlePlugin);
        return declared != null && !declared.isEmpty() && declared.containsAll(bundle.perPlugin.keySet());
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
                J.MethodInvocation held = overwriteAssignments(named, wanted, pluginsCursor);
                Map<String, Expression> missing = missingFrom(blockBody(held).getStatements(), wanted);
                J.MethodInvocation filled = missing.isEmpty() ? held : addAssignments(held, missing, pluginsCursor, false);
                if (filled == named) {
                    return declaration;
                }
                return declaration instanceof J.Return ? ((J.Return) declaration).withExpression(filled) : filled;
            });
            J.MethodInvocation replaced = withBody(plugins, pluginsBody.withStatements(declarations));
            return s instanceof J.Return ? ((J.Return) s).withExpression(replaced) : replaced;
        });
        return withBody(result, blockBody(result).withStatements(withPlugins));
    }

    /**
     * Gives each value the bundle names to the declaration that already assigns it.
     * In 0.x the portal took displayName, description and tags from {@code pluginBundle} and only the
     * id from the {@code gradlePlugin} declaration, so where the two name the same property it is the
     * bundle's value that was published, and the one to keep. A declaration that was written for 1.x
     * ahead of this migration, and a stale {@code pluginBundle} beside it, are the one case where that
     * loses the newer value, and the published description is the stale one either way.
     */
    private static J.MethodInvocation overwriteAssignments(J.MethodInvocation block,
                                                           Map<String, Expression> wanted, Cursor scope) {
        J.Block body = blockBody(block);
        List<Statement> statements = ListUtils.map(body.getStatements(), s -> {
            J.Assignment existing = asAssignment(s);
            String name = existing == null ? null : assignedName(existing);
            Expression value = name == null ? null : wanted.get(name);
            if (value == null) {
                return s;
            }
            Expression was = existing.getAssignment();
            if (was.printTrimmed(scope).equals(value.printTrimmed(scope))) {
                return s;
            }
            J.Assignment replaced = existing.withAssignment(value.withPrefix(was.getPrefix()));
            return s instanceof J.Return ? ((J.Return) s).withExpression(replaced) : (Statement) replaced;
        });
        return statements == body.getStatements() ? block : withBody(block, body.withStatements(statements));
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
