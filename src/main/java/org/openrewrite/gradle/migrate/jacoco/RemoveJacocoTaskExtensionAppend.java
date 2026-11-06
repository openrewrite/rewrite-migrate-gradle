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
package org.openrewrite.gradle.migrate.jacoco;

import lombok.Getter;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.gradle.migrate.internal.RemoveDeprecatedPropertyVisitor;

public class RemoveJacocoTaskExtensionAppend extends Recipe {

    @Getter
    final String displayName = "Remove the `append` property of the `jacoco` task extension";

    @Getter
    final String description = "Gradle 6.0 removed `JacocoTaskExtension.append`: the JaCoCo agent now always appends, so setting " +
            "it fails with `Could not set unknown property 'append' for extension 'jacoco'`. This recipe removes it from a " +
            "`jacoco { }` block and drops a block left empty as a result. A build that set `append = false` keeps its " +
            "execution data between runs afterwards, since Gradle offers no replacement. Kotlin scripts, where the " +
            "property was `isAppend` on `configure<JacocoTaskExtension> { }`, are left unchanged. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/current/userguide/upgrading_version_5.html) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new RemoveDeprecatedPropertyVisitor("append", "jacoco"::equals));
    }
}
