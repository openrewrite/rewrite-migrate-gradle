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
package org.openrewrite.gradle.migrate.java;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.gradle.IsSettingsGradle;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;

@Value
@EqualsAndHashCode(callSuper = false)
public class UseJvmVendorSpecIbm extends Recipe {

    String displayName = "Use `JvmVendorSpec.IBM` instead of `JvmVendorSpec.IBM_SEMERU`";

    String description = "`JvmVendorSpec.IBM_SEMERU` is deprecated and removed in Gradle 9, where reading it fails in Groovy and does " +
            "not compile in Kotlin. On Gradle 8.14 it is the same vendor as `JvmVendorSpec.IBM`. " +
            "See the [Gradle upgrade guide](https://docs.gradle.org/8.14/userguide/upgrading_version_8.html#ibm_semeru_should_not_be_used) for more information.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
                Preconditions.or(new IsBuildGradle<>(), new IsSettingsGradle<>()),
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, ExecutionContext ctx) {
                        J.FieldAccess f = super.visitFieldAccess(fieldAccess, ctx);
                        if ("IBM_SEMERU".equals(f.getSimpleName()) && isJvmVendorSpec(f.getTarget())) {
                            return f.withName(f.getName().withSimpleName("IBM"));
                        }
                        return f;
                    }

                    private boolean isJvmVendorSpec(Expression target) {
                        if (target instanceof J.Identifier) {
                            return "JvmVendorSpec".equals(((J.Identifier) target).getSimpleName());
                        }
                        return target instanceof J.FieldAccess && "JvmVendorSpec".equals(((J.FieldAccess) target).getSimpleName());
                    }
                });
    }
}
