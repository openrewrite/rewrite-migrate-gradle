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

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.openrewrite.Recipe;
import org.openrewrite.Validated;
import org.openrewrite.config.DeclarativeRecipe;
import org.openrewrite.config.Environment;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.SourceSpecs;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeLoadingTest implements RewriteTest {

    /**
     * What {@link RewriteTest#assertRecipesConfigure()} does, except that a recipe class left with nothing but
     * unset required options is not run, since it cannot be until it is configured. Its declarative callers still
     * are, with the options they pass.
     */
    @Test
    void loadRecipeFromFile() {
        String packageName = getClass().getPackage().getName();
        List<Recipe> recipes = Environment.builder()
                .scanRuntimeClasspath(packageName)
                .build()
                .listRecipes();
        assertThat(recipes).as("No recipes found in %s", packageName).isNotEmpty();
        SoftAssertions softly = new SoftAssertions();
        for (Recipe recipe : recipes) {
            if (!recipe.getName().startsWith(packageName) || needsOptions(recipe)) {
                continue;
            }
            softly.assertThatCode(() -> rewriteRun(spec -> spec.recipe(recipe), new SourceSpecs[0]))
                    .as("Recipe %s failed to configure", recipe.getName())
                    .doesNotThrowAnyException();
        }
        softly.assertAll();
    }

    /**
     * Never true of a declarative recipe, whose failures include a sub-recipe's missing option, which is a real
     * mistake in the YAML.
     */
    private static boolean needsOptions(Recipe recipe) {
        if (recipe instanceof DeclarativeRecipe) {
            return false;
        }
        List<Validated.Invalid<Object>> failures = recipe.validate().failures();
        return !failures.isEmpty() && failures.stream().allMatch(Validated.Missing.class::isInstance);
    }
}
