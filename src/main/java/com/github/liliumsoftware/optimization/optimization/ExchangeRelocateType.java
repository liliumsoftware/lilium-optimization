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
 * How exchange/relocate moves re-add a removed segment to the target plan: {@code SIMPLE} splices it in at
 * the fixed position; {@code TWO_OPT} re-optimizes each touched plan with local search; {@code INSERTION}
 * finds the cheapest insertion point via the insertion algorithm. Carries a persisted {@code Integer value};
 * use {@link #of(int)}.
 */
@RequiredArgsConstructor
@Getter
public enum ExchangeRelocateType {

    SIMPLE(0), TWO_OPT(1), INSERTION(2);

    private final Integer value;

    /**
     * The constant persisted as {@code value}.
     *
     * @throws IllegalArgumentException if no constant carries the given persisted {@code value}
     */
    public static ExchangeRelocateType of(int value) {
        return Stream.of(ExchangeRelocateType.values()).filter(type -> type.value == value).findAny()
                .orElseThrow(() -> new IllegalArgumentException("No ExchangeRelocateType found with value: " + value));
    }

}
