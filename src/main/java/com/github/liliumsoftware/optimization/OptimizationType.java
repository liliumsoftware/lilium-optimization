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
 * Whether zone restrictions constrain moves. {@code LOCATION_ZONE} keeps moves within/between neighbouring
 * zones (and makes Clarke-Wright plan each zone independently); {@code NO_RESTRICTION} disables zoning so
 * every zone predicate returns {@code true}. Carries a persisted {@code Integer value}; use {@link #of(int)}.
 */
@RequiredArgsConstructor
@Getter
public enum OptimizationType {

    LOCATION_ZONE(0), NO_RESTRICTION(1);

    private final Integer value;

    /**
     * The constant persisted as {@code value}.
     *
     * @throws IllegalArgumentException if no constant carries the given persisted {@code value}
     */
    public static OptimizationType of(int value) {
        return Stream.of(OptimizationType.values()).filter(type -> type.value == value).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("OptimizationType value not found"));
    }

}
