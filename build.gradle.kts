plugins {
    id("org.openrewrite.build.recipe-library") version "latest.release"
    id("org.openrewrite.build.moderne-source-available-license") version "latest.release"
}

group = "org.openrewrite.recipe"
description = "Migrate to later Gradle versions. Automatically."

val rewriteVersion = rewriteRecipe.rewriteVersion.get()
dependencies {
    implementation(platform("org.openrewrite:rewrite-bom:${rewriteVersion}"))
    implementation("org.openrewrite:rewrite-gradle")
    implementation("org.openrewrite:rewrite-groovy")
    implementation("org.openrewrite:rewrite-java")
    implementation("org.openrewrite:rewrite-kotlin")
    implementation("org.openrewrite:rewrite-properties")

    testImplementation("org.openrewrite:rewrite-test")
    testImplementation("org.openrewrite.gradle.tooling:model:${rewriteVersion}")
    testImplementation(gradleApi())

    testRuntimeOnly("org.openrewrite:rewrite-java-21")
}
