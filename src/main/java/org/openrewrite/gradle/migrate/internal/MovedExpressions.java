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
package org.openrewrite.gradle.migrate.internal;

import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.Tree;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.format.ShiftFormat;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;

public final class MovedExpressions {

    private MovedExpressions() {
    }

    /**
     * Re-indent an expression a template moved into a block it did not use to be in.
     * <p>
     * A template does not shift the expression it is handed uniformly. Where the value spans lines, it indents the
     * inner lines by two levels rather than one and pulls a trailing bracket onto their column, so
     * <pre>
     * applicationDefaultJvmArgs = [
     *     '-Xmx1g'
     * ]
     * </pre>
     * comes out of {@code application { }} with its element at twelve columns and its {@code ]} at twelve rather than
     * at eight and four. Whitespace with no line break is untouched either way, so a single-line value is unaffected
     * and this is a no-op for it.
     * <p>
     * The expression is therefore put back as it was written, shifted by the levels the move actually added, keeping
     * the prefix the template gave it, since that prefix places the value against its new surroundings and is right.
     * A template keeps the id of the expression it is handed, which is how the one to put back is found.
     *
     * @param applied  what {@code JavaTemplate.apply} returned
     * @param original the expression handed to the template, before it was applied
     * @param cursor   a cursor into the source file being changed, for its indentation style
     * @param shift    the indentation levels the expression moved right, usually one per block wrapped around it, and
     *                 zero where it did not move at all, which still needs putting back because a template re-indents
     *                 the expression whether it moved or not
     * @return {@code applied}, with the moved expression's own line breaks indented for where it now sits
     */
    public static J reindent(J applied, Expression original, Cursor cursor, int shift) {
        Expression shifted = ShiftFormat.indent(original, cursor, shift);
        return new JavaVisitor<Integer>() {
            @Override
            public @Nullable J visit(@Nullable Tree tree, Integer p) {
                if (tree instanceof J && ((J) tree).getId().equals(original.getId())) {
                    return shifted.withPrefix(((J) tree).getPrefix());
                }
                return super.visit(tree, p);
            }
        }.visitNonNull(applied, 0);
    }
}
