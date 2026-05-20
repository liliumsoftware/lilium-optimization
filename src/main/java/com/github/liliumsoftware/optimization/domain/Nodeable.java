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
 * A stop on a plan — a depot endpoint or a customer to be served.
 *
 * <p>Identity is by {@link #getCode()}: the algorithms locate, exclude and de-duplicate nodes by code
 * rather than by reference, so codes must be unique within a planning run and {@code equals}/{@code hashCode}
 * must be consistent with that identity. As with {@link Planable}, every candidate move copies nodes, so
 * {@link #copy()} must be a real deep copy.
 *
 * @param <N> the concrete node type (self type)
 */
public interface Nodeable<N extends Nodeable<N>> {

    /**
     * Unique identity of this stop within a run.
     */
    String getCode();

    /**
     * Classifies the stop as depot start, customer, or depot end; drives most feasibility/support checks.
     */
    Type getType();

    /**
     * A deep, independent copy; see {@link Planable#copy()} for why deep copies are required.
     */
    N copy();

    /**
     * Zone used for zone-restricted optimization; defaults to the shared {@code "NoZone"}.
     */
    default String getZone() {
        return "NoZone";
    }

    /**
     * Role of a stop within a plan. Carries a stable {@code Integer value} because it crosses a
     * persistence/API boundary; use {@link #of(int)} to reconstruct it from that value.
     */
    @RequiredArgsConstructor
    @Getter
    enum Type {

        ORIGIN(0), CUSTOMER(1), DESTINATION(2);

        private final Integer value;

        /**
         * The type persisted as {@code value}.
         *
         * @throws IllegalArgumentException if no type carries the given persisted {@code value}
         */
        public static Type of(int value) {
            return Stream.of(values()).filter(type -> type.value.equals(value)).findAny()
                    .orElseThrow(() -> new IllegalArgumentException("No Type found with value: " + value));
        }

    }

}
