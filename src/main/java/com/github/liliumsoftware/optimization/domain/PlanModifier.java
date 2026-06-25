/*
 * Copyright the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.liliumsoftware.optimization.domain;

import java.util.List;

/**
 * Post-processing hook applied to finished plans (e.g. renumbering, annotating, code generation). Runners
 * invoke these after planning completes. The {@code List} overload tolerates a {@code null} list so callers
 * need not guard it.
 */
public interface PlanModifier<P extends Planable<P, ?, ?>> {

    /**
     * Post-processes one finished plan in place.
     */
    void modify(P plan);

    /**
     * Applies {@link #modify(Planable)} to every plan; tolerates a {@code null} list.
     */
    default void modify(List<P> plans) {
        if (plans == null) {
            return;
        }
        plans.forEach(this::modify);
    }

}