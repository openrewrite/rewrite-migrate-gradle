/*
 * Copyright 2026 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.gradle.migrate.artifacts;

import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.gradle.IsSettingsGradle;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.kotlin.KotlinTemplate;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.marker.Markers;

import java.util.*;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

public class MigrateHttpRepositories extends Recipe {

    /**
     * Public repositories that serve the same content over HTTPS, so switching the scheme keeps the build working.
     */
    private static final Set<String> HTTPS_HOSTS = new HashSet<>(asList(
            "repo.maven.apache.org",
            "repo1.maven.org",
            "repository.apache.org",
            "plugins.gradle.org",
            "repo.gradle.org",
            "oss.sonatype.org",
            "s01.oss.sonatype.org",
            "repo.spring.io",
            "repository.jboss.org",
            "maven.google.com",
            "dl.google.com",
            "jitpack.io",
            "packages.confluent.io",
            "maven.aliyun.com",
            "mirrors.cloud.tencent.com",
            "repo.huaweicloud.com",
            "mirrors.huaweicloud.com"
    ));

    private static final Set<String> REPOSITORY_TYPES = new HashSet<>(asList("maven", "ivy", "jcenter", "mavenCentral"));

    private static final Set<String> INSECURE_PROTOCOL_PROPERTIES = new HashSet<>(asList(
            "allowInsecureProtocol", "setAllowInsecureProtocol", "isAllowInsecureProtocol"));

    @Getter
    final String displayName = "Use HTTPS or allow the insecure protocol for `http://` repositories";

    @Getter
    final String description = "Gradle 7.0 rejects repositories with an `http://` URL unless they opt in to the " +
            "insecure protocol. A repository on a well-known public host that also serves HTTPS, such as Maven " +
            "Central, the Gradle Plugin Portal or the Aliyun mirror, switches to `https://`. Any other `http://` " +
            "repository, such as an internal repository manager or `localhost`, keeps its URL and gets " +
            "`allowInsecureProtocol = true`, or `isAllowInsecureProtocol = true` in the Kotlin DSL, so the build " +
            "keeps resolving from it.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(Preconditions.or(new IsBuildGradle<>(), new IsSettingsGradle<>()), new JavaVisitor<ExecutionContext>() {

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
                if (!REPOSITORY_TYPES.contains(m.getSimpleName()) || !isInsideRepositoriesBlock()) {
                    return m;
                }
                J.Lambda closure = closure(m);
                if (closure != null && !(closure.getBody() instanceof J.Block)) {
                    return m;
                }
                J.Block body = closure == null ? null : (J.Block) closure.getBody();
                if (body != null && allowsInsecureProtocol(body)) {
                    // The build already opted in, so it resolves on Gradle 7 as it is
                    return m;
                }

                for (Expression argument : m.getArguments()) {
                    HttpUrl httpUrl = argument == closure ? null : httpUrl(urlArgument(argument));
                    if (httpUrl == null) {
                        continue;
                    }
                    if (HTTPS_HOSTS.contains(httpUrl.host())) {
                        return m.withArguments(ListUtils.map(m.getArguments(), arg -> useHttps(arg, httpUrl.literal)));
                    }
                    if (closure == null) {
                        m = withEmptyTrailingLambda(m);
                        closure = requireNonNull(closure(m));
                        body = (J.Block) closure.getBody();
                    }
                    return withClosureBody(m, closure, addAfter(body, body.getStatements().size() - 1, allowInsecureProtocol()));
                }

                if (body == null) {
                    return m;
                }
                List<Statement> statements = body.getStatements();
                for (int i = 0; i < statements.size(); i++) {
                    HttpUrl httpUrl = httpUrl(urlStatement(statements.get(i)));
                    if (httpUrl == null) {
                        continue;
                    }
                    J.Block newBody = HTTPS_HOSTS.contains(httpUrl.host()) ?
                            body.withStatements(ListUtils.map(statements, s -> useHttps(s, httpUrl.literal))) :
                            addAfter(body, i, allowInsecureProtocol());
                    return withClosureBody(m, closure, newBody);
                }
                return m;
            }

            private boolean isKotlin() {
                return getCursor().firstEnclosing(K.CompilationUnit.class) != null;
            }

            private boolean isInsideRepositoriesBlock() {
                for (Iterator<Object> path = getCursor().getPath(J.MethodInvocation.class::isInstance); path.hasNext(); ) {
                    if ("repositories".equals(((J.MethodInvocation) path.next()).getSimpleName())) {
                        return true;
                    }
                }
                return false;
            }

            private J.@Nullable Lambda closure(J.MethodInvocation m) {
                List<Expression> args = m.getArguments();
                return !args.isEmpty() && args.get(args.size() - 1) instanceof J.Lambda ?
                        (J.Lambda) args.get(args.size() - 1) : null;
            }

            private J.MethodInvocation withClosureBody(J.MethodInvocation m, J.Lambda closure, J.Block body) {
                J.Lambda newClosure = closure.withBody(body);
                return m.withArguments(ListUtils.mapLast(m.getArguments(), arg -> newClosure));
            }

            /**
             * The URL passed as an argument in the Kotlin DSL, as in {@code maven("..")} or {@code maven(url = "..")}.
             */
            private @Nullable Expression urlArgument(Expression argument) {
                if (argument instanceof J.Assignment) {
                    J.Assignment a = (J.Assignment) argument;
                    return isUrl(a.getVariable()) ? a.getAssignment() : null;
                }
                return argument;
            }

            /**
             * The URL set by {@code url ..}, {@code url(..)}, {@code setUrl(..)} or {@code url = ..}.
             */
            private @Nullable Expression urlStatement(Statement statement) {
                J s = statement instanceof J.Return ? ((J.Return) statement).getExpression() : statement;
                if (s instanceof J.MethodInvocation) {
                    J.MethodInvocation mi = (J.MethodInvocation) s;
                    if (("url".equals(mi.getSimpleName()) || "setUrl".equals(mi.getSimpleName())) &&
                        mi.getSelect() == null && mi.getArguments().size() == 1) {
                        return mi.getArguments().get(0);
                    }
                } else if (s instanceof J.Assignment && isUrl(((J.Assignment) s).getVariable())) {
                    return ((J.Assignment) s).getAssignment();
                }
                return null;
            }

            private boolean isUrl(Expression variable) {
                return variable instanceof J.Identifier && "url".equals(((J.Identifier) variable).getSimpleName());
            }

            /**
             * The string literal that starts the URL, also inside {@code uri(..)}, when it starts with {@code http://}.
             */
            private @Nullable HttpUrl httpUrl(@Nullable Expression value) {
                if (value instanceof J.MethodInvocation) {
                    J.MethodInvocation uri = (J.MethodInvocation) value;
                    if ("uri".equals(uri.getSimpleName()) && uri.getSelect() == null && uri.getArguments().size() == 1) {
                        value = uri.getArguments().get(0);
                    }
                }
                List<J> strings = value instanceof G.GString ? ((G.GString) value).getStrings() :
                        value instanceof K.StringTemplate ? ((K.StringTemplate) value).getStrings() : null;
                boolean interpolated = strings != null;
                if (interpolated) {
                    value = !strings.isEmpty() && strings.get(0) instanceof J.Literal ? (J.Literal) strings.get(0) : null;
                }
                if (value instanceof J.Literal && ((J.Literal) value).getValue() instanceof String &&
                    ((String) ((J.Literal) value).getValue()).startsWith("http://")) {
                    return new HttpUrl((J.Literal) value, interpolated);
                }
                return null;
            }

            @SuppressWarnings("unchecked")
            private <T extends J> T useHttps(T tree, J.Literal scheme) {
                return (T) new JavaIsoVisitor<Integer>() {
                    @Override
                    public J.Literal visitLiteral(J.Literal literal, Integer p) {
                        if (literal.getId().equals(scheme.getId())) {
                            String url = "https://" + ((String) requireNonNull(literal.getValue())).substring("http://".length());
                            String source = literal.getValueSource();
                            return literal.withValue(url)
                                    .withValueSource(source == null ? null : source.replaceFirst("http://", "https://"));
                        }
                        return literal;
                    }
                }.visitNonNull(tree, 0);
            }

            private boolean allowsInsecureProtocol(J.Block body) {
                return new JavaIsoVisitor<Set<Boolean>>() {
                    @Override
                    public J.Identifier visitIdentifier(J.Identifier identifier, Set<Boolean> found) {
                        if (INSECURE_PROTOCOL_PROPERTIES.contains(identifier.getSimpleName())) {
                            found.add(true);
                        }
                        return identifier;
                    }
                }.reduce(body, new HashSet<>()).contains(true);
            }

            private Statement allowInsecureProtocol() {
                String property = isKotlin() ? "isAllowInsecureProtocol" : "allowInsecureProtocol";
                return new J.Assignment(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, emptyList(), property, JavaType.Primitive.Boolean, null),
                        JLeftPadded.<Expression>build(new J.Literal(Tree.randomId(), Space.SINGLE_SPACE, Markers.EMPTY, true, "true", null, JavaType.Primitive.Boolean))
                                .withBefore(Space.SINGLE_SPACE),
                        JavaType.Primitive.Boolean);
            }

            private J.MethodInvocation withEmptyTrailingLambda(J.MethodInvocation m) {
                J.MethodInvocation template = KotlinTemplate.apply("maven(\"\") {}", getCursor(), m.getCoordinates().replace());
                return m.withArguments(ListUtils.concat(m.getArguments(), requireNonNull(closure(template))));
            }

            /**
             * Inserts the statement after the one at {@code index}, on a line of its own. A closure written on a
             * single line is spread over several lines first.
             */
            private J.Block addAfter(J.Block body, int index, Statement statement) {
                List<Statement> statements = new ArrayList<>(body.getStatements());
                String previous = index < 0 ? "" : statements.get(index).getPrefix().getWhitespace();
                if (previous.contains("\n")) {
                    statements.add(index + 1, statement.withPrefix(Space.format("\n" + previous.substring(previous.lastIndexOf('\n') + 1))));
                    return body.withStatements(statements);
                }
                String outer = indentation(statementPrefix().getWhitespace());
                String inner = outer + (outer.contains("\t") ? "\t" : "    ");
                statements = ListUtils.map(statements, s -> s.getPrefix().getWhitespace().contains("\n") ? s :
                        s.withPrefix(Space.format("\n" + inner)));
                statements.add(index + 1, statement.withPrefix(Space.format("\n" + inner)));
                return body.withStatements(statements).withEnd(Space.format("\n" + outer));
            }

            /**
             * The prefix of the statement holding the visited invocation, which is an implicit {@code return} when the
             * invocation is the last statement of a Groovy closure.
             */
            private Space statementPrefix() {
                Object parent = getCursor().getParentTreeCursor().getValue();
                return parent instanceof J.Return ? ((J.Return) parent).getPrefix() :
                        getCursor().<J.MethodInvocation>getValue().getPrefix();
            }

            private String indentation(String whitespace) {
                return whitespace.substring(whitespace.lastIndexOf('\n') + 1);
            }
        });
    }

    private static class HttpUrl {
        final J.Literal literal;
        final boolean interpolated;

        HttpUrl(J.Literal literal, boolean interpolated) {
            this.literal = literal;
            this.interpolated = interpolated;
        }

        /**
         * The host of the URL, or {@code null} when the literal ends before the host does, as in
         * {@code "http://${host}/repo"}, or when the URL names a port, which may not serve HTTPS.
         */
        @Nullable
        String host() {
            String rest = ((String) requireNonNull(literal.getValue())).substring("http://".length());
            int end = 0;
            while (end < rest.length() && "/:?#".indexOf(rest.charAt(end)) < 0) {
                end++;
            }
            if (end == rest.length() ? interpolated : rest.charAt(end) == ':') {
                return null;
            }
            return rest.substring(0, end).toLowerCase(Locale.ROOT);
        }
    }
}
