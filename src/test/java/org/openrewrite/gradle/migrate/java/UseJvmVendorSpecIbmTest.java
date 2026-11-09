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

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;
import static org.openrewrite.gradle.Assertions.settingsGradleKts;

class UseJvmVendorSpecIbmTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseJvmVendorSpecIbm());
    }

    @DocumentExample
    @Test
    void groovy() {
        rewriteRun(
          buildGradle(
            """
              java {
                  toolchain {
                      vendor = JvmVendorSpec.IBM_SEMERU
                  }
              }
              """,
            """
              java {
                  toolchain {
                      vendor = JvmVendorSpec.IBM
                  }
              }
              """
          )
        );
    }

    @Test
    void kotlin() {
        rewriteRun(
          buildGradleKts(
            """
              java {
                  toolchain {
                      vendor.set(JvmVendorSpec.IBM_SEMERU)
                  }
              }
              """,
            """
              java {
                  toolchain {
                      vendor.set(JvmVendorSpec.IBM)
                  }
              }
              """
          )
        );
    }

    @Test
    void qualified() {
        rewriteRun(
          buildGradle(
            """
              def v = org.gradle.jvm.toolchain.JvmVendorSpec.IBM_SEMERU
              """,
            """
              def v = org.gradle.jvm.toolchain.JvmVendorSpec.IBM
              """
          )
        );
    }

    @Test
    void settingsScript() {
        rewriteRun(
          settingsGradleKts(
            """
              val v = JvmVendorSpec.IBM_SEMERU
              """,
            """
              val v = JvmVendorSpec.IBM
              """
          )
        );
    }

    @Test
    void doNotChangeOtherVendorsOrReceivers() {
        rewriteRun(
          buildGradle(
            """
              def a = JvmVendorSpec.IBM
              def b = JvmVendorSpec.ADOPTOPENJDK
              def c = myVendors.IBM_SEMERU
              def d = 'JvmVendorSpec.IBM_SEMERU'
              """
          )
        );
    }
}
