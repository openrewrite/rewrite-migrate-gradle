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
package org.openrewrite.gradle.migrate.properties;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.FindSourceFiles;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.properties.AddProperty;
import org.openrewrite.properties.PropertiesVisitor;
import org.openrewrite.properties.tree.Properties;

@Value
@EqualsAndHashCode(callSuper = false)
public class AddToolingParallelProperty extends Recipe {

    private static final String PARALLEL = "org.gradle.parallel";
    private static final String TOOLING_PARALLEL = "org.gradle.tooling.parallel";

    String displayName = "Set `org.gradle.tooling.parallel` where `org.gradle.parallel` is enabled";

    String description = "Gradle 9.8 deprecates relying on `org.gradle.parallel=true` to also turn on parallel model " +
            "building for Tooling API clients such as IDEs, and says this will fail in Gradle 10. Gradle 9.4 added " +
            "`org.gradle.tooling.parallel` to control it. This recipe adds `org.gradle.tooling.parallel=true`, which " +
            "keeps today's behavior, to a `gradle.properties` that enables `org.gradle.parallel` and sets no explicit " +
            "value. It never creates a file, and leaves a file alone where parallel execution is disabled or absent. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/current/userguide/upgrading_version_9.html#deprecate_implicit_parallel_model_building) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new FindSourceFiles("**/gradle.properties"), new PropertiesVisitor<ExecutionContext>() {
            @Override
            public Properties visitFile(Properties.File file, ExecutionContext ctx) {
                // Core's org.openrewrite.gradle.AddProperty generates a gradle.properties when there is none;
                // this one only ever edits the file it is given.
                return needsToolingParallel(file) ?
                        new AddProperty(TOOLING_PARALLEL, "true", null, null, null, AddProperty.InsertMode.After, PARALLEL)
                                .getVisitor().visitFile(file, ctx) :
                        file;
            }
        });
    }

    private static boolean needsToolingParallel(Properties.File file) {
        boolean parallel = false;
        for (Properties.Content content : file.getContent()) {
            if (content instanceof Properties.Entry) {
                Properties.Entry entry = (Properties.Entry) content;
                if (TOOLING_PARALLEL.equals(entry.getKey())) {
                    return false;
                }
                if (PARALLEL.equals(entry.getKey())) {
                    // A repeated key takes its last value, and Gradle reads the value ignoring case
                    parallel = "true".equalsIgnoreCase(entry.getValue().getText().trim());
                }
            }
        }
        return parallel;
    }
}
