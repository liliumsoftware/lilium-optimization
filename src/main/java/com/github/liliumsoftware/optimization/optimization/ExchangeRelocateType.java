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

package com.github.liliumsoftware.optimization.optimization;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.stream.Stream;

/**
 * How {@link AbstractExchangeAlgorithm} and {@link AbstractRelocateAlgorithm} re-add a removed segment, and when the
 * touched plans go through the configured local search. Whatever the type, every plan of the list is run through the
 * local search once a move has been committed. Each constant carries a persisted {@link #getValue() value}; use
 * {@link #of(int)} to resolve one.
 */
@RequiredArgsConstructor
@Getter
public enum ExchangeRelocateType {

    /**
     * Splices the segment in at the fixed target position. The move is judged as spliced; the two touched plans are
     * run through the local search only after the move has been judged an improvement. A relocation into a plan
     * without customers uses its only slot (behind the origin, in front of the destination) and skips the neighbour
     * limit. Persisted value {@code 0}.
     */
    SIMPLE(0),
    /**
     * Splices the segment in at the fixed target position like {@link #SIMPLE}, then runs both touched plans through
     * the local search <em>before</em> the move is judged, so a move is judged by its re-optimized cost. A relocation
     * into a plan without customers works as under {@link #SIMPLE}. Persisted value {@code 1}.
     */
    TWO_OPT(1),
    /**
     * Re-inserts the segment's customers into the target plan with the configured insertion algorithm, which picks
     * each position itself (cheapest or regret insertion, as configured); for an exchange the second segment then
     * goes into the first plan the same way. The move fails unless every customer is placed. No local search runs
     * before the move is judged. A relocation accepts any target plan, whatever its number of stops. Persisted value
     * {@code 2}.
     */
    INSERTION(2);

    /**
     * The stable value this constant is persisted as ({@code 0}, {@code 1}, {@code 2} in declaration order); never
     * {@code null}. {@link #of(int)} maps it back.
     */
    private final Integer value;

    /**
     * The constant persisted as {@code value}.
     *
     * @param value the persisted value of a constant
     * @return the constant carrying {@code value}
     * @throws IllegalArgumentException if no constant carries the given persisted {@code value}
     */
    public static ExchangeRelocateType of(int value) {
        return Stream.of(ExchangeRelocateType.values()).filter(type -> type.value == value).findAny()
                .orElseThrow(() -> new IllegalArgumentException("No ExchangeRelocateType found with value: " + value));
    }

}
