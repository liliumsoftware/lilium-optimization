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

package com.github.liliumsoftware.optimization;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.stream.Stream;

/**
 * Whether zone restrictions constrain the search. The policy is handed to {@code PathCostCalculation} (whose
 * {@link com.github.liliumsoftware.optimization.calculation.PathCostCalculation#isSameZone isSameZone} and
 * {@link com.github.liliumsoftware.optimization.calculation.PathCostCalculation#isNeighbor isNeighbor} predicates every
 * move consults), to Clarke-Wright and to the un-merge algorithms. Each constant carries a stable persisted
 * {@link #getValue() value}; {@link #of(int)} maps it back.
 */
@RequiredArgsConstructor
@Getter
public enum OptimizationType {

    /**
     * Zones restrict the search: the zone predicates confine moves to a zone or to the zones its neighbours-matrix
     * entry lists (taken literally: a zone is not its own neighbour unless listed), Clarke-Wright forms savings only
     * within a zone, each zone with its own λ/β, and the un-merge algorithms may separate routes along zone
     * boundaries. Persisted value {@code 0}.
     */
    LOCATION_ZONE(0),
    /**
     * Zoning is disabled: every zone predicate of {@code PathCostCalculation} returns {@code true} and the un-merge
     * algorithms never act. Persisted value {@code 1}.
     */
    NO_RESTRICTION(1);

    /**
     * The stable value this constant is persisted as ({@code 0} for {@link #LOCATION_ZONE}, {@code 1} for
     * {@link #NO_RESTRICTION}); never {@code null}. {@link #of(int)} maps it back.
     */
    private final Integer value;

    /**
     * The constant persisted as {@code value}.
     *
     * @param value a persisted value, as returned by {@link #getValue()}
     * @return the constant carrying {@code value}
     * @throws IllegalArgumentException if no constant carries the given persisted {@code value}
     */
    public static OptimizationType of(int value) {
        return Stream.of(OptimizationType.values()).filter(type -> type.value == value).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("OptimizationType value not found"));
    }

}
