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
package org.openrewrite.gradle.migrate.model;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.Flag;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JContainer;
import org.openrewrite.java.tree.JLeftPadded;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.marker.Markers;

import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Collections.emptyList;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseObjectFactoryDomainObjectContainer extends Recipe {

    private static final MethodMatcher PROJECT_CONTAINER = new MethodMatcher("org.gradle.api.Project container(java.lang.Class)", true);
    private static final JavaType.FullyQualified OBJECT_FACTORY = JavaType.ShallowClass.build("org.gradle.api.model.ObjectFactory");

    String displayName = "Use `objects.domainObjectContainer(Foo)` instead of `Project.container(Foo)`";

    String description = "`Project.container(Class)` is deprecated in Gradle 9.2 and scheduled for removal in Gradle 10. " +
            "This recipe replaces it with `ObjectFactory.domainObjectContainer(Class)`, in Groovy and Kotlin scripts and in " +
            "the Java, Kotlin and Groovy sources of plugins. `ObjectFactory` decorates the elements and only takes a public " +
            "constructor, so an element class that worked before fails on it if it is final, with `Class Foo is final`, " +
            "or if its constructor is not public, with `No constructors of type Foo match parameters`. Such a class is left " +
            "alone, and so is a Kotlin or Java element class not known to be fit, since Kotlin classes are final by " +
            "default. The overloads that take a factory or a closure are left alone too. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/current/userguide/upgrading_version_9.html#project_container_methods) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                if (!"container".equals(m.getSimpleName()) || m.getArguments().size() != 1) {
                    return m;
                }
                JavaSourceFile source = getCursor().firstEnclosingOrThrow(JavaSourceFile.class);
                Expression element = m.getArguments().get(0);
                if (!isOnProject(m, source) || !canBeDecorated(element, source)) {
                    return m;
                }
                JavaType.Method type = m.getMethodType() == null ? null : m.getMethodType()
                        .withDeclaringType(OBJECT_FACTORY)
                        .withName("domainObjectContainer");
                return m.withSelect(objectFactoryOf(m, source))
                        .withName(m.getName().withSimpleName("domainObjectContainer").withType(type))
                        .withMethodType(type);
            }

            private boolean isOnProject(J.MethodInvocation m, JavaSourceFile source) {
                if (m.getMethodType() != null && PROJECT_CONTAINER.matches(m)) {
                    return m.getSelect() != null || source instanceof K.CompilationUnit || isScript(source);
                }
                if (source instanceof J.CompilationUnit || m.getMethodType() != null) {
                    return false;
                }
                Expression select = m.getSelect();
                if (select == null) {
                    return isScript(source) && getCursor().firstEnclosing(J.ClassDeclaration.class) == null;
                }
                return select instanceof J.Identifier && "project".equals(((J.Identifier) select).getSimpleName());
            }
        };
    }

    private static boolean isScript(JavaSourceFile source) {
        String path = source.getSourcePath().toString();
        return path.endsWith(".gradle") || path.endsWith(".gradle.kts");
    }

    private static Expression objectFactoryOf(J.MethodInvocation m, JavaSourceFile source) {
        Expression select = m.getSelect();
        J.Identifier objects = new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, emptyList(),
                "objects", OBJECT_FACTORY, null);
        if (select == null) {
            return objects;
        }
        if (source instanceof J.CompilationUnit) {
            JavaType.Method type = m.getMethodType() == null ? null : m.getMethodType()
                    .withName("getObjects")
                    .withReturnType(OBJECT_FACTORY)
                    .withParameterNames(emptyList())
                    .withParameterTypes(emptyList());
            return new J.MethodInvocation(Tree.randomId(), select.getPrefix(), Markers.EMPTY,
                    JRightPadded.build(select.withPrefix(Space.EMPTY)), null,
                    objects.withSimpleName("getObjects").withType(type),
                    JContainer.empty(),
                    type);
        }
        return new J.FieldAccess(Tree.randomId(), select.getPrefix(), Markers.EMPTY, select.withPrefix(Space.EMPTY),
                JLeftPadded.build(objects), OBJECT_FACTORY);
    }

    /**
     * {@code ObjectFactory} generates a subclass of the element, which a final class does not allow, and looks only at
     * public constructors, where {@code Project.container} takes any. A Groovy class is open and its constructors are
     * public unless it says otherwise. A Kotlin class is final unless it says otherwise, and a Java class has a type to
     * ask, so in those two the class has to be known to be fit.
     */
    private static boolean canBeDecorated(Expression element, JavaSourceFile source) {
        String name = elementName(element);
        if (name == null) {
            return false;
        }
        JavaType.FullyQualified type = elementType(element);
        if (source instanceof J.CompilationUnit) {
            return type != null && !(type instanceof JavaType.Unknown) && !(type instanceof JavaType.ShallowClass) &&
                    !type.hasFlags(Flag.Final) && hasPublicNamedConstructor(type);
        }
        Boolean declaredOpen = declaredOpen(name, source);
        if (declaredOpen != null) {
            return declaredOpen;
        }
        return !(source instanceof K.CompilationUnit);
    }

    private static boolean hasPublicNamedConstructor(JavaType.FullyQualified type) {
        if (type.getKind() == JavaType.FullyQualified.Kind.Interface) {
            return true;
        }
        for (JavaType.Method method : type.getMethods()) {
            if (method.isConstructor() && method.hasFlags(Flag.Public) && method.getParameterTypes().size() == 1 &&
                TypeUtils.isString(method.getParameterTypes().get(0))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The simple name of {@code Foo}, {@code Foo.class}, {@code a.b.Foo} or {@code Foo::class.java}, and null for
     * anything that is not visibly a class, such as a variable holding one.
     */
    private static @Nullable String elementName(Expression element) {
        if (element instanceof J.FieldAccess) {
            J.FieldAccess access = (J.FieldAccess) element;
            if ("class".equals(access.getSimpleName()) || "java".equals(access.getSimpleName())) {
                return elementName(access.getTarget());
            }
            return startsUpperCase(access.getSimpleName()) ? access.getSimpleName() : null;
        }
        if (element instanceof J.MemberReference) {
            J.MemberReference reference = (J.MemberReference) element;
            return "class".equals(reference.getReference().getSimpleName()) ? elementName(reference.getContaining()) : null;
        }
        if (element instanceof J.Identifier) {
            String name = ((J.Identifier) element).getSimpleName();
            return startsUpperCase(name) ? name : null;
        }
        return null;
    }

    private static boolean startsUpperCase(String name) {
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    private static JavaType.@Nullable FullyQualified elementType(Expression element) {
        if (element instanceof J.FieldAccess) {
            J.FieldAccess access = (J.FieldAccess) element;
            if ("class".equals(access.getSimpleName()) || "java".equals(access.getSimpleName())) {
                return elementType(access.getTarget());
            }
        } else if (element instanceof J.MemberReference) {
            return elementType(((J.MemberReference) element).getContaining());
        }
        return TypeUtils.asFullyQualified(element.getType());
    }

    /**
     * Whether a class of this name declared in this file can be subclassed, or null if it declares none.
     */
    private static @Nullable Boolean declaredOpen(String name, JavaSourceFile source) {
        AtomicBoolean found = new AtomicBoolean();
        AtomicBoolean open = new AtomicBoolean();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, Integer p) {
                if (name.equals(classDecl.getSimpleName())) {
                    found.set(true);
                    open.set(!isFinal(classDecl, source));
                }
                return super.visitClassDeclaration(classDecl, p);
            }
        }.visit(source, 0);
        return found.get() ? open.get() : null;
    }

    private static boolean isFinal(J.ClassDeclaration classDecl, JavaSourceFile source) {
        if (source instanceof K.CompilationUnit) {
            // The Kotlin parser marks an abstract class final as well, so this reads only what opens a class
            return classDecl.getKind() != J.ClassDeclaration.Kind.Type.Interface &&
                    classDecl.getModifiers().stream().noneMatch(mod -> mod.getType() == J.Modifier.Type.Abstract ||
                            "open".equals(mod.getKeyword()));
        }
        return classDecl.getModifiers().stream().anyMatch(mod -> mod.getType() == J.Modifier.Type.Final);
    }
}
