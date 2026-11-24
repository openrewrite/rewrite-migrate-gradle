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
package org.openrewrite.gradle.migrate;

import org.openrewrite.Tree;
import org.openrewrite.gradle.marker.GradleBuildscript;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.maven.tree.MavenRepository;

import java.util.List;

/// A [GradleProject] that resolves plugins from the Gradle Plugin Portal and dependencies from Maven Central,
/// as a real build would, so that version selectors such as `1.0.x` resolve without running the Tooling API.
public final class GradleProjectMarkers {
    private static final MavenRepository PLUGIN_PORTAL = new MavenRepository(
      "Gradle Central Plugin Repository", "https://plugins.gradle.org/m2", "true", "false", true, null, null, null, null);

    private GradleProjectMarkers() {
    }

    public static GradleBuildscript buildscript() {
        return GradleBuildscript.builder().mavenRepositories(List.of(PLUGIN_PORTAL)).build();
    }

    public static GradleProject project() {
        return GradleProject.builder()
          .id(Tree.randomId())
          .buildscript(buildscript())
          .mavenRepositories(List.of(MavenRepository.MAVEN_CENTRAL))
          .build();
    }
}
