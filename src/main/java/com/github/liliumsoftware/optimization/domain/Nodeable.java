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

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.stream.Stream;

/**
 * A stop on a plan: a depot endpoint ({@link Type#ORIGIN}, {@link Type#DESTINATION}) or a customer to be served.
 *
 * <p><strong>Identity.</strong> {@link #getCode()} identifies a node: the cost matrix is indexed by code, and codes
 * must be unique within a planning run ({@code PathCostCalculation#initializeCostMatrix} rejects duplicates across all
 * nodes, and the Clarke-Wright, insertion and k-medoids entry points reject a customer batch with a duplicate code,
 * both with {@link IllegalArgumentException}). {@code equals} may compare by value or by code, but a {@link #copy()}
 * must be {@code equals} to its source, and {@code equals}/{@code hashCode} must be consistent: customers are still
 * matched by {@code equals}, in hash-based lookups (unplanned customers in {@code BaseAlgorithmRunner}, medoid sets in
 * k-medoids) and when a move locates a node in a plan's node list ({@code indexOf}, {@code remove(Object)}).
 *
 * <p><strong>Copies.</strong> Every candidate move works on copies of plans and nodes and is accepted by comparing
 * costs, so {@link #copy()} must be a real deep copy: the library mutates only the copies it makes.
 *
 * @param <N> the concrete node type (self type)
 */
public interface Nodeable<N extends Nodeable<N>> {

    /**
     * Unique identity of this stop within a run; the key of the cost matrix.
     *
     * @return the code; never {@code null}
     */
    String getCode();

    /**
     * Classifies the stop as depot start, customer or depot end. Only {@link Type#CUSTOMER} stops are moved between
     * positions; depot stops pin the ends of a plan.
     *
     * @return the role of this stop; never {@code null}
     */
    Type getType();

    /**
     * A deep, independent copy that is {@code equals} to this node; see {@link Planable#copy()} for why deep copies
     * are required.
     *
     * @return the copy
     */
    N copy();

    /**
     * Zone used for zone-restricted optimization (see
     * {@link com.github.liliumsoftware.optimization.OptimizationType#LOCATION_ZONE}). Under that policy the default
     * {@code "NoZone"} is an ordinary zone name, subject to the neighbours matrix like any other, and the un-merge
     * algorithms never separate it; a {@code null} zone is unrestricted on either side of
     * {@code PathCostCalculation#isSameZone} / {@code isNeighbor}.
     *
     * <p>The default returns {@code "NoZone"}.
     *
     * @return the zone name, or {@code null}
     */
    default String getZone() {
        return "NoZone";
    }

    /**
     * Role of a stop within a plan. Carries a stable {@code Integer} {@link #getValue() value} because it crosses a
     * persistence/API boundary; {@link #of(int)} reconstructs the constant from that value.
     */
    @RequiredArgsConstructor
    @Getter
    enum Type {

        /**
         * The depot a plan starts from; when present it is the first stop. Persisted value {@code 0}.
         */
        ORIGIN(0),
        /**
         * A stop to be served; the only kind of stop the algorithms move between positions. Persisted value
         * {@code 1}.
         */
        CUSTOMER(1),
        /**
         * The depot a plan ends at; when present it is the last stop, and an open plan has none. Persisted value
         * {@code 2}.
         */
        DESTINATION(2);

        /**
         * The stable value this type is persisted as ({@code 0} for {@link #ORIGIN}, {@code 1} for {@link #CUSTOMER},
         * {@code 2} for {@link #DESTINATION}); never {@code null}. {@link #of(int)} maps it back.
         */
        private final Integer value;

        /**
         * The type persisted as {@code value}.
         *
         * @param value a persisted value, as returned by {@link #getValue()}
         * @return the type carrying {@code value}
         * @throws IllegalArgumentException if no type carries the given persisted {@code value}
         */
        public static Type of(int value) {
            return Stream.of(values()).filter(type -> type.value.equals(value)).findAny()
                    .orElseThrow(() -> new IllegalArgumentException("No Type found with value: " + value));
        }

    }

}
