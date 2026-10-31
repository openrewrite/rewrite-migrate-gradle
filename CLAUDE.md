# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

OpenRewrite recipes that migrate Gradle builds to newer Gradle versions and to the build conventions those versions expect.

This module is the migration counterpart to `rewrite-gradle` in the `openrewrite/rewrite` monorepo. `rewrite-gradle` stays focused on parsing and modeling Gradle builds — parser, traits, markers, the `GradleProject` model. Recipes that *change* a build to suit a newer Gradle belong here. Keep that split in mind when deciding where something goes.

Licensed under the Moderne Source Available License, published as `org.openrewrite.recipe:rewrite-migrate-gradle`.

## Build and Test Commands

```bash
./gradlew build          # full build; see "Known issues" about javadoc
./gradlew test
./gradlew test --tests "UseVersionClosureTest"
./gradlew test --tests "*Plugins*"
./gradlew licenseFormat  # apply MSAL headers to new files
```

Plugin resolution goes through the `codegenome` Maven repository, which requires `codegenomeUsername`/`codegenomePassword` Gradle properties or `CODEGENOME_USERNAME`/`CODEGENOME_TOKEN` environment variables. Without them the build cannot resolve `org.openrewrite.build.recipe-library`.

There is no CI workflow that builds this module. Builds and releases come from the Code Genome Project's nightly train; `repository-backup.yml` is the only GitHub Actions workflow here.

## Package Layout

Base package is `org.openrewrite.gradle.migrate`. Organize subpackages by subject area — the plugin, API or build concept a recipe touches — not by Gradle version. This mirrors `rewrite-migrate-java`, which groups by `lang`, `io`, `jakarta`, `guava` and composes version targets declaratively in `java-version-*.yml`.

Gradle version targeting belongs in YAML composites, not in package names.

Recipe identifiers derive from the package, so moving or renaming a recipe class renames the recipe. Recipes already shipped elsewhere are referenced by customers and by other modules; treat a rename as a breaking change and check who depends on it first.

## Writing Recipes

Use `@Value` and `@EqualsAndHashCode(callSuper = false)` from Lombok for recipe classes. Lombok comes from the build plugin — don't redeclare it.

Most recipes here visit Groovy or Kotlin build scripts rather than Java sources. A build file is parsed as Groovy (`build.gradle`) or Kotlin (`build.gradle.kts`), so a recipe that handles only one leaves the other untouched — decide deliberately which you support and test both when you claim both.

Prefer the traits and markers `rewrite-gradle` already provides (`GradleProject`, `GradleDependency`, `GradlePlugin`, `FindPlugins`) over re-deriving build structure from the AST. If something you need there is not public, that is a change to `rewrite-gradle`, not a reason to copy its logic.

Use `MethodMatcher` for method matching and `SemanticallyEqual.areEqual()` for comparing expressions.

No Refaster templates exist here yet, so the build declares neither `rewrite-templating` nor error-prone. Adding the first template means adding both — and pinning error-prone, since releases from 2.51.0 onward pull transitive dependencies that the plugin's `--release 8` compile cannot resolve.

## Testing

Tests implement `RewriteTest` and set the recipe in `defaults(RecipeSpec)`. Use the assertion helpers for the source type being changed:

```java
rewriteRun(
  buildGradle(
    """
      // before
      """,
    """
      // after
      """
  )
);
```

`buildGradle`, `settingsGradle`, `properties` and `java` come from the respective modules' `Assertions` classes. Mark the clearest case `@DocumentExample`, prefix negative cases `doNotChange`/`unchanged`, and add `@Issue(...)` where a test pins a reported bug.

## YAML Configuration

Declarative recipes live in `src/main/resources/META-INF/rewrite/`. `category.yml` names packages for the recipe catalog. Comment out any recipe that is unsafe to run unattended, with the reason — a recipe that changes task cache keys, for instance, belongs out of a composite even when it is correct.

## Known Issues

`./gradlew javadoc` fails with "No public or protected classes found to document" while the module has no public types. It resolves itself once the first recipe lands.
