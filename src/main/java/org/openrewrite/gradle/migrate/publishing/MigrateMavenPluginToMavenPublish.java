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
package org.openrewrite.gradle.migrate.publishing;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.GradleParser;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.gradle.plugins.ChangePlugin;
import org.openrewrite.gradle.plugins.RemoveBuildPlugin;
import org.openrewrite.gradle.style.Autodetect;
import org.openrewrite.groovy.GroovyIsoVisitor;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.SemanticallyEqual;
import org.openrewrite.java.style.TabsAndIndentsStyle;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JLeftPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.marker.Markers;
import org.openrewrite.style.Style;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Collections.singletonList;

@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateMavenPluginToMavenPublish extends ScanningRecipe<AtomicBoolean> {

    private static final String MAVEN = "maven";
    private static final String MAVEN_PUBLISH = "maven-publish";

    /**
     * Names that only exist while the legacy `maven` plugin is applied.
     */
    private static final Set<String> LEGACY_NAMES = new HashSet<>(Arrays.asList(
            "uploadArchives", "install", "mavenDeployer", "mavenInstaller", "MavenDeployment",
            "Upload", "conf2ScopeMappings", "signPom"));

    /**
     * Properties that `MavenPom` offers at its top level.
     */
    private static final Set<String> POM_PROPERTIES = new HashSet<>(Arrays.asList(
            "name", "description", "url", "inceptionYear", "packaging"));

    /**
     * Nested `pom.project { }` blocks that `MavenPom` offers under the same name.
     */
    private static final Set<String> POM_BLOCKS = new HashSet<>(Arrays.asList(
            "licenses", "license", "developers", "developer", "contributors", "contributor",
            "organization", "scm", "issueManagement", "ciManagement", "distributionManagement",
            "relocation", "mailingLists", "mailingList"));

    /**
     * Methods whose string arguments name tasks.
     */
    private static final Set<String> TASK_REFERENCES = new HashSet<>(Arrays.asList(
            "defaultTasks", "dependsOn", "finalizedBy", "mustRunAfter", "shouldRunAfter",
            "named", "getByName", "findByName", "getByPath", "findByPath", "hasTask"));

    /**
     * Blocks that configure other projects than the one the script belongs to.
     */
    private static final Set<String> SHARED_CONFIGURATION = new HashSet<>(Arrays.asList(
            "subprojects", "allprojects", "configure"));

    /**
     * Collection properties that cannot take the single value the legacy syntax passed.
     */
    private static final Set<String> POM_COLLECTION_PROPERTIES = new HashSet<>(Arrays.asList(
            "roles", "otherArchives"));

    String displayName = "Migrate the removed `maven` plugin to `maven-publish`";

    String description = "Gradle 7.0 removed the legacy `maven` plugin together with the `uploadArchives` task, " +
            "`mavenDeployer`, `MavenDeployment` and the `install` task, so a build that still applies it fails " +
            "before any task runs. In a Groovy build script whose `uploadArchives` task deploys through " +
            "`mavenDeployer` to a release repository and an optional snapshot repository, with an optional " +
            "`pom.project { }` and `signing.signPom(..)`, the plugin is switched to `maven-publish` and " +
            "`uploadArchives` becomes a `publishing { }` block. Its publication holds the Java component and the " +
            "other artifacts of the `archives` configuration, and `signing` signs the publications instead of the " +
            "`archives` configuration. Where the plugin is applied but nothing of it is used, it is removed. A build " +
            "that uses the plugin in any other way is left unchanged, so that it keeps Gradle's error message. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/current/userguide/upgrading_version_6.html#removal_of_the_legacy_maven_plugin).";

    @Override
    public AtomicBoolean getInitialValue(ExecutionContext ctx) {
        return new AtomicBoolean();
    }

    /**
     * Records whether a build script uses the plugin without applying it, as a subproject does when its parent
     * applies the plugin for it. The parent's plugin is then not unused, even when the parent itself uses nothing of it.
     */
    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(AtomicBoolean usedElsewhere) {
        return Preconditions.check(new IsBuildGradle<>(), new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!usedElsewhere.get() && tree instanceof G.CompilationUnit) {
                    G.CompilationUnit cu = (G.CompilationUnit) tree;
                    if (!appliesPlugin(cu, MAVEN) && usesLegacyPlugin(cu, null)) {
                        usedElsewhere.set(true);
                    }
                }
                return tree;
            }
        });
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(AtomicBoolean usedElsewhere) {
        // Removing or converting the plugin here would leave the other script's uses of it behind
        if (usedElsewhere.get()) {
            return TreeVisitor.noop();
        }
        return Preconditions.check(new IsBuildGradle<>(), new GroovyIsoVisitor<ExecutionContext>() {
            @Override
            public G.CompilationUnit visitCompilationUnit(G.CompilationUnit cu, ExecutionContext ctx) {
                if (!appliesPlugin(cu, MAVEN)) {
                    return cu;
                }

                List<J.MethodInvocation> uploads = findInvocations(cu, "uploadArchives");
                if (uploads.isEmpty()) {
                    if (usesLegacyPlugin(cu, null)) {
                        return cu;
                    }
                    doAfterVisit(new RemoveBuildPlugin(MAVEN).getVisitor());
                    return cu;
                }

                if (uploads.size() > 1 || usesLegacyPlugin(cu, uploads.get(0)) ||
                    !findInvocations(cu, "publishing").isEmpty()) {
                    return cu;
                }
                // `uploadArchives` deployed the war or ear in place of the jar that `components.java` publishes
                if (appliesPlugin(cu, "war") || appliesPlugin(cu, "ear")) {
                    return cu;
                }
                // Without the Java plugin there is no `components.java` to publish
                if (!appliesPlugin(cu, "java") && !appliesPlugin(cu, "java-library")) {
                    return cu;
                }
                J.MethodInvocation upload = uploads.get(0);
                Deployment deployment = Deployment.of(upload);
                if (deployment == null) {
                    return cu;
                }
                // `mavenDeployer` chose the snapshot repository when publishing, the ternary does so while the
                // block is configured: in a shared block that is before a subproject sets its own version
                if (deployment.snapshot != null && deployment.snapshotCondition == null &&
                    (isInSharedConfiguration(cu, upload) || setsVersionAfter(cu, upload))) {
                    return cu;
                }
                boolean signsArchives = signsArchives(cu);
                if (deployment.signPom && !signsArchives) {
                    return cu;
                }
                List<String> artifacts = archivesArtifacts(cu);
                if (artifacts == null) {
                    return cu;
                }
                String snippet = deployment.render(artifacts, getCursor());
                if (snippet == null) {
                    return cu;
                }
                Statement publishing = parse(snippet, ctx);
                if (publishing == null) {
                    return cu;
                }

                G.CompilationUnit g = (G.CompilationUnit) new ReplaceStatement(upload, publishing)
                        .visitNonNull(withDetectedStyle(cu), ctx);
                doAfterVisit(appliesPlugin(cu, MAVEN_PUBLISH) ?
                        new RemoveBuildPlugin(MAVEN).getVisitor() :
                        new ChangePlugin(MAVEN, MAVEN_PUBLISH, null).getVisitor());
                return ((G.CompilationUnit) new SignPublications().visitNonNull(g, ctx)).withMarkers(cu.getMarkers());
            }
        });
    }

    private static boolean appliesPlugin(G.CompilationUnit cu, String pluginId) {
        return new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, AtomicBoolean f) {
                if (pluginLiteral(method, getCursor(), pluginId) != null) {
                    f.set(true);
                }
                return super.visitMethodInvocation(method, f);
            }
        }.reduce(cu, new AtomicBoolean()).get();
    }

    /**
     * The literal naming the plugin when the invocation is `apply plugin: '<id>'` or `id '<id>'` in a `plugins` block.
     */
    private static J.@Nullable Literal pluginLiteral(J.MethodInvocation m, Cursor cursor, String pluginId) {
        if ("apply".equals(m.getSimpleName())) {
            for (Expression arg : m.getArguments()) {
                if (arg instanceof G.MapEntry && "plugin".equals(keyName((G.MapEntry) arg)) &&
                    isString(((G.MapEntry) arg).getValue(), pluginId)) {
                    return (J.Literal) ((G.MapEntry) arg).getValue();
                }
            }
        } else if ("id".equals(m.getSimpleName()) && m.getSelect() == null && m.getArguments().size() == 1 &&
                   isString(m.getArguments().get(0), pluginId)) {
            J.MethodInvocation enclosing = cursor.getParentTreeCursor().firstEnclosing(J.MethodInvocation.class);
            if (enclosing != null && "plugins".equals(enclosing.getSimpleName())) {
                return (J.Literal) m.getArguments().get(0);
            }
        }
        return null;
    }

    /**
     * Whether the script refers to a task, type or method of the legacy plugin outside of {@code except}.
     * The name `uploadArchives` passed to `gradle.taskGraph.hasTask(..)` is not a use: it is rewritten.
     */
    private static boolean usesLegacyPlugin(G.CompilationUnit cu, J.@Nullable MethodInvocation except) {
        return new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, AtomicBoolean f) {
                if (LEGACY_NAMES.contains(identifier.getSimpleName())) {
                    f.set(true);
                }
                return identifier;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, AtomicBoolean f) {
                if (except != null && method.getId().equals(except.getId())) {
                    return method;
                }
                // `pom { project { .. } }.writeTo(..)`: the plugin's POM factory, unlike `pom { }` in a publication
                if ("pom".equals(method.getSimpleName()) && method.getSelect() == null && !isInPublishing(getCursor())) {
                    f.set(true);
                }
                return super.visitMethodInvocation(method, f);
            }

            @Override
            public J.Literal visitLiteral(J.Literal literal, AtomicBoolean f) {
                if (literal.getValue() instanceof String) {
                    String value = (String) literal.getValue();
                    if (value.contains("uploadArchives") && !isHasTaskArgument(getCursor()) ||
                        ("install".equals(value) || value.endsWith(":install")) && isTaskReference(getCursor())) {
                        f.set(true);
                    }
                }
                return literal;
            }
        }.reduce(cu, new AtomicBoolean()).get();
    }

    /**
     * Whether the script sets the project version after the invocation, as in `version = '1.0-SNAPSHOT'` at the
     * end of the script, so that the version is not known yet where the invocation is evaluated.
     */
    private static boolean setsVersionAfter(G.CompilationUnit cu, J.MethodInvocation m) {
        return new JavaIsoVisitor<AtomicBoolean>() {
            boolean after;

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, AtomicBoolean f) {
                if (method.getId().equals(m.getId())) {
                    after = true;
                    return method;
                }
                if (after && "version".equals(method.getSimpleName()) && method.getArguments().size() == 1 &&
                    (method.getSelect() == null || isProject(method.getSelect()))) {
                    f.set(true);
                }
                return super.visitMethodInvocation(method, f);
            }

            @Override
            public J.Assignment visitAssignment(J.Assignment assignment, AtomicBoolean f) {
                Expression variable = assignment.getVariable();
                if (after && (variable instanceof J.Identifier && "version".equals(((J.Identifier) variable).getSimpleName()) ||
                              variable instanceof J.FieldAccess && "version".equals(((J.FieldAccess) variable).getSimpleName()) &&
                              isProject(((J.FieldAccess) variable).getTarget()))) {
                    f.set(true);
                }
                return super.visitAssignment(assignment, f);
            }

            private boolean isProject(Expression e) {
                return e instanceof J.Identifier && "project".equals(((J.Identifier) e).getSimpleName());
            }
        }.reduce(cu, new AtomicBoolean()).get();
    }

    /**
     * Whether the invocation sits in a block that configures other projects, such as `subprojects { }`.
     */
    private static boolean isInSharedConfiguration(G.CompilationUnit cu, J.MethodInvocation m) {
        return new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, AtomicBoolean f) {
                if (method.getId().equals(m.getId())) {
                    getCursor().getPathAsStream()
                            .filter(J.MethodInvocation.class::isInstance)
                            .map(J.MethodInvocation.class::cast)
                            .filter(enclosing -> SHARED_CONFIGURATION.contains(enclosing.getSimpleName()))
                            .findAny()
                            .ifPresent(enclosing -> f.set(true));
                    return method;
                }
                return super.visitMethodInvocation(method, f);
            }
        }.reduce(cu, new AtomicBoolean()).get();
    }

    /**
     * Whether the cursor is in the configuration of a `maven-publish` publication: in `publishing { }`, or in a
     * call on `publishing.publications`, such as `publishing.publications.withType(MavenPublication) { }`.
     */
    private static boolean isInPublishing(Cursor cursor) {
        return cursor.getPathAsStream()
                .filter(J.MethodInvocation.class::isInstance)
                .map(J.MethodInvocation.class::cast)
                .anyMatch(m -> isPublishingName(m.getSimpleName()) || refersToPublishing(m.getSelect()));
    }

    private static boolean refersToPublishing(@Nullable Expression select) {
        if (select instanceof J.Identifier) {
            return isPublishingName(((J.Identifier) select).getSimpleName());
        }
        if (select instanceof J.FieldAccess) {
            J.FieldAccess fieldAccess = (J.FieldAccess) select;
            return isPublishingName(fieldAccess.getSimpleName()) || refersToPublishing(fieldAccess.getTarget());
        }
        if (select instanceof J.MethodInvocation) {
            J.MethodInvocation m = (J.MethodInvocation) select;
            return isPublishingName(m.getSimpleName()) || refersToPublishing(m.getSelect());
        }
        return false;
    }

    private static boolean isPublishingName(String name) {
        return "publishing".equals(name) || "publications".equals(name);
    }

    /**
     * Whether the literal names a task, as in `defaultTasks 'clean', 'install'` or `tasks.named('install')`, rather
     * than being any string that happens to read `install`, such as a file name or a command line argument.
     */
    private static boolean isTaskReference(Cursor literalCursor) {
        J.MethodInvocation enclosing = literalCursor.firstEnclosing(J.MethodInvocation.class);
        return enclosing != null && TASK_REFERENCES.contains(enclosing.getSimpleName());
    }

    private static boolean isHasTaskArgument(Cursor literalCursor) {
        J.MethodInvocation enclosing = literalCursor.firstEnclosing(J.MethodInvocation.class);
        return enclosing != null && "hasTask".equals(enclosing.getSimpleName());
    }

    private static List<J.MethodInvocation> findInvocations(G.CompilationUnit cu, String name) {
        return new JavaIsoVisitor<List<J.MethodInvocation>>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, List<J.MethodInvocation> f) {
                if (name.equals(method.getSimpleName()) && method.getSelect() == null) {
                    f.add(method);
                }
                return super.visitMethodInvocation(method, f);
            }
        }.reduce(cu, new ArrayList<>());
    }

    /**
     * Whether a `signing { }` block signs the `archives` configuration, which `uploadArchives` deployed the signatures of.
     */
    private static boolean signsArchives(G.CompilationUnit cu) {
        return new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, AtomicBoolean f) {
                if (isSignArchives(method, getCursor())) {
                    f.set(true);
                }
                return super.visitMethodInvocation(method, f);
            }
        }.reduce(cu, new AtomicBoolean()).get();
    }

    private static boolean isSignArchives(J.MethodInvocation m, Cursor cursor) {
        if (!"sign".equals(m.getSimpleName()) || m.getSelect() != null || m.getArguments().size() != 1 ||
            !(m.getArguments().get(0) instanceof J.FieldAccess)) {
            return false;
        }
        J.FieldAccess arg = (J.FieldAccess) m.getArguments().get(0);
        if (!"archives".equals(arg.getSimpleName()) || !(arg.getTarget() instanceof J.Identifier) ||
            !"configurations".equals(((J.Identifier) arg.getTarget()).getSimpleName())) {
            return false;
        }
        J.MethodInvocation enclosing = cursor.getParentTreeCursor().firstEnclosing(J.MethodInvocation.class);
        return enclosing != null && "signing".equals(enclosing.getSimpleName());
    }

    /**
     * The artifacts other than the jar that `artifacts { archives .. }` added for `uploadArchives` to deploy,
     * or null when one of them is declared in a form the recipe does not carry over.
     */
    private static @Nullable List<String> archivesArtifacts(G.CompilationUnit cu) {
        List<String> artifacts = new ArrayList<>();
        for (J.MethodInvocation block : findInvocations(cu, "artifacts")) {
            J.Block body = closureBody(block);
            if (body == null) {
                continue;
            }
            for (Statement s : body.getStatements()) {
                J.MethodInvocation archives = asInvocation(s);
                if (archives == null || !"archives".equals(archives.getSimpleName()) || archives.getSelect() != null) {
                    continue;
                }
                if (archives.getArguments().size() != 1 || !(archives.getArguments().get(0) instanceof J.Identifier)) {
                    return null;
                }
                String artifact = ((J.Identifier) archives.getArguments().get(0)).getSimpleName();
                if (!"jar".equals(artifact) && !artifacts.contains(artifact)) {
                    artifacts.add(artifact);
                }
            }
        }
        return artifacts;
    }

    /**
     * Without a style, formatting the `publishing { }` block would indent by four spaces whatever the script uses.
     */
    private static G.CompilationUnit withDetectedStyle(G.CompilationUnit cu) {
        if (Style.from(TabsAndIndentsStyle.class, cu) != null) {
            return cu;
        }
        Autodetect.Detector detector = Autodetect.detector();
        detector.sample(cu);
        return cu.withMarkers(cu.getMarkers().add(detector.build()));
    }

    private static @Nullable Statement parse(String snippet, ExecutionContext ctx) {
        return GradleParser.builder().build()
                .parse(ctx, snippet)
                .findFirst()
                .filter(G.CompilationUnit.class::isInstance)
                .map(G.CompilationUnit.class::cast)
                .filter(parsed -> parsed.getStatements().size() == 1)
                .map(parsed -> parsed.getStatements().get(0))
                .orElse(null);
    }

    private static J.@Nullable Block closureBody(J.MethodInvocation m) {
        List<Expression> args = m.getArguments();
        if (args.isEmpty() || !(args.get(args.size() - 1) instanceof J.Lambda)) {
            return null;
        }
        J body = ((J.Lambda) args.get(args.size() - 1)).getBody();
        return body instanceof J.Block ? (J.Block) body : null;
    }

    private static J.@Nullable MethodInvocation asInvocation(Statement s) {
        if (s instanceof J.MethodInvocation) {
            return (J.MethodInvocation) s;
        }
        if (s instanceof J.Return && ((J.Return) s).getExpression() instanceof J.MethodInvocation) {
            return (J.MethodInvocation) ((J.Return) s).getExpression();
        }
        return null;
    }

    private static J.@Nullable MethodInvocation onlyInvocation(J.@Nullable Block body, String name) {
        if (body == null || body.getStatements().size() != 1) {
            return null;
        }
        J.MethodInvocation m = asInvocation(body.getStatements().get(0));
        return m != null && name.equals(m.getSimpleName()) && m.getSelect() == null ? m : null;
    }

    private static @Nullable String keyName(G.MapEntry entry) {
        Expression key = entry.getKey();
        if (key instanceof J.Literal && ((J.Literal) key).getValue() instanceof String) {
            return (String) ((J.Literal) key).getValue();
        }
        if (key instanceof J.Identifier) {
            return ((J.Identifier) key).getSimpleName();
        }
        return null;
    }

    private static boolean isString(Expression e, String value) {
        return e instanceof J.Literal && value.equals(((J.Literal) e).getValue());
    }

    /**
     * A repository of `mavenDeployer`: `repository(url: ..) { authentication(userName: .., password: ..) }`.
     */
    @Value
    private static class Repository {
        Expression url;

        @Nullable
        Expression userName;

        @Nullable
        Expression password;

        static @Nullable Repository of(J.MethodInvocation m) {
            Expression url = null;
            for (Expression arg : m.getArguments()) {
                if (arg instanceof G.MapEntry && "url".equals(keyName((G.MapEntry) arg)) && url == null) {
                    url = ((G.MapEntry) arg).getValue();
                } else if (!(arg instanceof J.Lambda)) {
                    return null;
                }
            }
            if (url == null) {
                return null;
            }
            J.Block body = closureBody(m);
            if (body == null || body.getStatements().isEmpty()) {
                return new Repository(url, null, null);
            }
            J.MethodInvocation authentication = onlyInvocation(body, "authentication");
            if (authentication == null) {
                return null;
            }
            Expression userName = null;
            Expression password = null;
            for (Expression arg : authentication.getArguments()) {
                if (!(arg instanceof G.MapEntry)) {
                    return null;
                }
                String key = keyName((G.MapEntry) arg);
                if ("userName".equals(key) && userName == null) {
                    userName = ((G.MapEntry) arg).getValue();
                } else if ("password".equals(key) && password == null) {
                    password = ((G.MapEntry) arg).getValue();
                } else {
                    return null;
                }
            }
            return userName == null || password == null ? null : new Repository(url, userName, password);
        }
    }

    /**
     * What an `uploadArchives { repositories { mavenDeployer { .. } } }` block deploys.
     */
    private static class Deployment {
        @Nullable
        Repository release;

        @Nullable
        Repository snapshot;

        @Nullable
        Expression snapshotCondition;

        boolean snapshotWhenTrue;

        J.@Nullable Block pom;

        boolean signPom;

        J.@Nullable MethodInvocation repositories;

        static @Nullable Deployment of(J.MethodInvocation upload) {
            if (upload.getArguments().size() != 1) {
                return null;
            }
            Deployment deployment = new Deployment();
            J.Block uploadBody = closureBody(upload);
            deployment.repositories = onlyInvocation(uploadBody, "repositories");
            if (uploadBody == null || deployment.repositories == null ||
                deployment.repositories.getArguments().size() != 1) {
                return null;
            }
            J.MethodInvocation deployer = onlyInvocation(closureBody(deployment.repositories), "mavenDeployer");
            J.Block body = deployer == null || deployer.getArguments().size() != 1 ? null : closureBody(deployer);
            if (body == null) {
                return null;
            }
            for (Statement s : body.getStatements()) {
                if (!deployment.accept(s)) {
                    return null;
                }
            }
            return deployment.release == null ? null : deployment;
        }

        private boolean accept(Statement s) {
            if (s instanceof J.If) {
                // `if (..) { snapshotRepository(..) } else { repository(..) }`, or the other way around
                J.If iff = (J.If) s;
                if (iff.getElsePart() == null || !(iff.getThenPart() instanceof J.Block) ||
                    !(iff.getElsePart().getBody() instanceof J.Block)) {
                    return false;
                }
                J.Block then = (J.Block) iff.getThenPart();
                J.Block otherwise = (J.Block) iff.getElsePart().getBody();
                if (then.getStatements().size() != 1 || otherwise.getStatements().size() != 1 ||
                    !isRepository(then.getStatements().get(0)) || !isRepository(otherwise.getStatements().get(0)) ||
                    !accept(then.getStatements().get(0)) || !accept(otherwise.getStatements().get(0))) {
                    return false;
                }
                snapshotCondition = iff.getIfCondition().getTree();
                J.MethodInvocation thenRepository = asInvocation(then.getStatements().get(0));
                snapshotWhenTrue = thenRepository != null && "snapshotRepository".equals(thenRepository.getSimpleName());
                return true;
            }
            J.MethodInvocation m = asInvocation(s);
            if (m == null) {
                return false;
            }
            switch (m.getSimpleName()) {
                case "repository":
                    if (release != null || m.getSelect() != null) {
                        return false;
                    }
                    release = Repository.of(m);
                    return release != null;
                case "snapshotRepository":
                    if (snapshot != null || m.getSelect() != null) {
                        return false;
                    }
                    snapshot = Repository.of(m);
                    return snapshot != null;
                case "beforeDeployment":
                    signPom = isSignPom(m);
                    return signPom;
                case "project":
                    if (pom != null || m.getArguments().size() != 1 || !(m.getSelect() instanceof J.Identifier) ||
                        !"pom".equals(((J.Identifier) m.getSelect()).getSimpleName())) {
                        return false;
                    }
                    J.Block body = closureBody(m);
                    pom = body == null ? null : convertPom(body, true);
                    return pom != null;
                default:
                    return false;
            }
        }

        private static boolean isRepository(Statement s) {
            J.MethodInvocation m = asInvocation(s);
            return m != null && ("repository".equals(m.getSimpleName()) || "snapshotRepository".equals(m.getSimpleName()));
        }

        /**
         * `beforeDeployment { MavenDeployment deployment -> signing.signPom(deployment) }`
         */
        private static boolean isSignPom(J.MethodInvocation m) {
            J.Block body = m.getSelect() == null && m.getArguments().size() == 1 ? closureBody(m) : null;
            J.MethodInvocation sign = body == null || body.getStatements().size() != 1 ? null :
                    asInvocation(body.getStatements().get(0));
            return sign != null && "signPom".equals(sign.getSimpleName()) &&
                   sign.getSelect() instanceof J.Identifier &&
                   "signing".equals(((J.Identifier) sign.getSelect()).getSimpleName());
        }

        /**
         * Rewrites `pom.project { }` content for `MavenPom`, whose properties take `=` rather than the method
         * call syntax `pom.project { }` used, or returns null for content `MavenPom` has no counterpart for.
         */
        private static J.@Nullable Block convertPom(J.Block body, boolean topLevel) {
            List<Statement> converted = new ArrayList<>(body.getStatements().size());
            for (Statement s : body.getStatements()) {
                Statement c = convertPomStatement(s, topLevel);
                if (c == null) {
                    return null;
                }
                converted.add(c);
            }
            return body.withStatements(converted);
        }

        private static @Nullable Statement convertPomStatement(Statement s, boolean topLevel) {
            if (s instanceof J.Return && ((J.Return) s).getExpression() != null) {
                J.Return r = (J.Return) s;
                Expression e = r.getExpression();
                Statement c = e instanceof Statement ? convertPomStatement((Statement) e, topLevel) : null;
                return c instanceof Expression ? r.withExpression((Expression) c) : null;
            }
            if (s instanceof J.Assignment) {
                J.Assignment a = (J.Assignment) s;
                return a.getVariable() instanceof J.Identifier &&
                       isPomProperty(((J.Identifier) a.getVariable()).getSimpleName(), topLevel) ? a : null;
            }
            if (!(s instanceof J.MethodInvocation)) {
                return null;
            }
            J.MethodInvocation m = (J.MethodInvocation) s;
            if (m.getSelect() != null || m.getArguments().size() != 1) {
                return null;
            }
            Expression arg = m.getArguments().get(0);
            String name = m.getSimpleName();
            if (arg instanceof J.Lambda) {
                J.Lambda lambda = (J.Lambda) arg;
                if (!POM_BLOCKS.contains(name) || !(lambda.getBody() instanceof J.Block)) {
                    return null;
                }
                J.Block nested = convertPom((J.Block) lambda.getBody(), false);
                return nested == null ? null : m.withArguments(singletonList(lambda.withBody(nested)));
            }
            if (arg instanceof J.Empty || arg instanceof G.ListLiteral || arg instanceof G.MapLiteral ||
                !isPomProperty(name, topLevel)) {
                return null;
            }
            Expression rhs = arg.withPrefix(Space.SINGLE_SPACE);
            return new J.Assignment(
                    Tree.randomId(),
                    m.getPrefix(),
                    m.getMarkers(),
                    m.getName().withPrefix(Space.EMPTY),
                    new JLeftPadded<>(Space.SINGLE_SPACE, rhs, Markers.EMPTY),
                    rhs.getType());
        }

        private static boolean isPomProperty(String name, boolean topLevel) {
            return topLevel ? POM_PROPERTIES.contains(name) : !POM_COLLECTION_PROPERTIES.contains(name);
        }

        @Nullable
        String render(List<String> artifacts, Cursor cursor) {
            Repository rel = release;
            if (rel == null || repositories == null) {
                return null;
            }

            String url = choose(rel.getUrl(), snapshot == null ? null : snapshot.getUrl(), cursor);
            String userName;
            String password;
            if (snapshot == null || rel.getUserName() != null && snapshot.getUserName() != null) {
                userName = rel.getUserName() == null ? null :
                        choose(rel.getUserName(), snapshot == null ? null : snapshot.getUserName(), cursor);
                password = rel.getPassword() == null ? null :
                        choose(rel.getPassword(), snapshot == null ? null : snapshot.getPassword(), cursor);
            } else if (rel.getUserName() == null && snapshot.getUserName() == null) {
                userName = null;
                password = null;
            } else {
                return null;
            }

            StringBuilder s = new StringBuilder("publishing {\n");
            s.append("publications {\n");
            s.append("mavenJava(MavenPublication) {\n");
            s.append("from components.java\n");
            for (String artifact : artifacts) {
                s.append("artifact ").append(artifact).append('\n');
            }
            if (pom != null && !pom.getStatements().isEmpty()) {
                s.append("pom {");
                for (Statement statement : pom.getStatements()) {
                    s.append(statement.print(cursor));
                }
                s.append("\n}\n");
            }
            s.append("}\n");
            s.append("}\n");
            s.append("repositories {\n");
            s.append("maven {\n");
            s.append("url = ").append(url).append('\n');
            if (userName != null && password != null) {
                s.append("credentials {\n");
                s.append("username = ").append(userName).append('\n');
                s.append("password = ").append(password).append('\n');
                s.append("}\n");
            }
            s.append("}\n");
            s.append("}\n");
            s.append('}');
            return s.toString();
        }

        /**
         * `mavenDeployer` deploys a `-SNAPSHOT` version to the snapshot repository when it has one, unless the
         * build itself chose between the repositories with an `if`.
         */
        private String choose(Expression forRelease, @Nullable Expression forSnapshot, Cursor cursor) {
            String rel = forRelease.printTrimmed(cursor);
            if (forSnapshot == null || SemanticallyEqual.areEqual(forRelease, forSnapshot)) {
                return rel;
            }
            String snap = forSnapshot.printTrimmed(cursor);
            if (snapshotCondition == null) {
                return "version.toString().endsWith('SNAPSHOT') ? " + snap + " : " + rel;
            }
            String condition = snapshotCondition.printTrimmed(cursor);
            if (!(snapshotCondition instanceof J.MethodInvocation || snapshotCondition instanceof J.Identifier ||
                  snapshotCondition instanceof J.FieldAccess)) {
                condition = "(" + condition + ")";
            }
            return snapshotWhenTrue ? condition + " ? " + snap + " : " + rel : condition + " ? " + rel + " : " + snap;
        }
    }

    /**
     * Puts the `publishing { }` block where the `uploadArchives { }` block was.
     */
    @Value
    @EqualsAndHashCode(callSuper = false)
    private static class ReplaceStatement extends GroovyIsoVisitor<ExecutionContext> {
        J.MethodInvocation upload;
        Statement replacement;

        @Override
        public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
            J.Block b = super.visitBlock(block, ctx);
            return b.withStatements(ListUtils.map(b.getStatements(), s -> replace(s, ctx, getCursor())));
        }

        @Override
        public G.CompilationUnit visitCompilationUnit(G.CompilationUnit cu, ExecutionContext ctx) {
            G.CompilationUnit g = super.visitCompilationUnit(cu, ctx);
            return g.withStatements(ListUtils.map(g.getStatements(), s -> replace(s, ctx, getCursor())));
        }

        private Statement replace(Statement s, ExecutionContext ctx, Cursor parent) {
            J.MethodInvocation m = asInvocation(s);
            if (m != null && m.getId().equals(upload.getId())) {
                return autoFormat(replacement.withPrefix(s.getPrefix()), ctx, parent);
            }
            return s;
        }
    }

    /**
     * `signing` signs the publications rather than the `archives` configuration, and requires signatures when
     * `publish` runs rather than `uploadArchives`.
     */
    private static class SignPublications extends JavaIsoVisitor<ExecutionContext> {
        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
            if (isSignArchives(m, getCursor())) {
                J.FieldAccess archives = (J.FieldAccess) m.getArguments().get(0);
                J.Identifier configurations = (J.Identifier) archives.getTarget();
                return m.withArguments(singletonList(archives
                        .withTarget(configurations.withSimpleName("publishing").withType(null).withFieldType(null))
                        .withName(archives.getName().withSimpleName("publications").withType(null).withFieldType(null))
                        .withType(null)));
            }
            return m;
        }

        @Override
        public J.Literal visitLiteral(J.Literal literal, ExecutionContext ctx) {
            if (literal.getType() == JavaType.Primitive.String && literal.getValue() instanceof String &&
                literal.getValueSource() != null && isHasTaskArgument(getCursor())) {
                String value = (String) literal.getValue();
                if ("uploadArchives".equals(value) || value.endsWith(":uploadArchives")) {
                    String publish = value.substring(0, value.length() - "uploadArchives".length()) + "publish";
                    return literal.withValue(publish)
                            .withValueSource(literal.getValueSource().replace(value, publish));
                }
            }
            return literal;
        }
    }
}
