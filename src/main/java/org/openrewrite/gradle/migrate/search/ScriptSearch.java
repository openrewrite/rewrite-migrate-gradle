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

import lombok.experimental.UtilityClass;
import org.openrewrite.Cursor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;

import java.util.Iterator;
import java.util.Set;

/**
 * Scripts are often parsed with little type attribution, so these look at what the source names.
 */
@UtilityClass
class ScriptSearch {

    /**
     * Whether a block with one of these names encloses the cursor, not counting the cursor's own tree.
     */
    static boolean isInside(Cursor cursor, Set<String> blockNames) {
        Iterator<Cursor> path = cursor.getPathAsCursors(c -> c.getValue() instanceof J.MethodInvocation);
        while (path.hasNext()) {
            Cursor c = path.next();
            if (c != cursor && blockNames.contains(c.<J.MethodInvocation>getValue().getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the call's receiver, type arguments or arguments name this type. Its own closure doesn't count.
     */
    static boolean mentions(J.MethodInvocation m, String type) {
        boolean[] found = {false};
        JavaIsoVisitor<Integer> visitor = new JavaIsoVisitor<Integer>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, Integer p) {
                found[0] |= type.equals(identifier.getSimpleName());
                return identifier;
            }
        };
        if (m.getTypeParameters() != null) {
            m.getTypeParameters().forEach(t -> visitor.visit(t, 0));
        }
        for (Expression argument : m.getArguments()) {
            if (!(argument instanceof J.Lambda)) {
                visitor.visit(argument, 0);
            }
        }
        return found[0];
    }
}
