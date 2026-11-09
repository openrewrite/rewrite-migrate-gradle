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
package org.openrewrite.gradle.migrate.search;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.gradle.IsSettingsGradle;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.marker.SearchResult;

@Value
@EqualsAndHashCode(callSuper = false)
public class FindInternalImpldepImports extends Recipe {

    private static final String MESSAGE = "Gradle's relocated internal dependencies are not visible to Kotlin scripts on Gradle 9: add the original library to the build logic";

    String displayName = "Find `org.gradle.internal.impldep` imports in Kotlin scripts";

    String description = "On Gradle 9.8, a Kotlin script that imports from `org.gradle.internal.impldep` fails with `Unresolved reference 'impldep'`. The " +
            "replacement is the original library, added as a dependency of the build logic, and which version is a decision to make. " +
            "Kotlin only: the guide covers Kotlin scripts, and an example Groovy script already failed on Gradle 8.14. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/9.8.0/userguide/upgrading_version_9.html#kotlin_dsl_impldep_compilation) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(Preconditions.or(new IsBuildGradle<>(), new IsSettingsGradle<>()), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.Import visitImport(J.Import anImport, ExecutionContext ctx) {
                J.Import i = super.visitImport(anImport, ctx);
                if (getCursor().firstEnclosing(K.CompilationUnit.class) != null &&
                        i.getQualid().printTrimmed(getCursor()).replaceAll("\\s", "").startsWith("org.gradle.internal.impldep.")) {
                    return SearchResult.found(i, MESSAGE);
                }
                return i;
            }
        });
    }
}
