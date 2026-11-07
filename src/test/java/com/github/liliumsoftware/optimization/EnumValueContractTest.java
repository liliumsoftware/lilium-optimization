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

import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.optimization.ExchangeRelocateType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The persisted {@code value} of these enums is an API/DB contract: reordering constants or changing a value
 * would silently corrupt stored data. These round-trip tests lock the mapping.
 */
class EnumValueContractTest {

    @Test
    void nodeableTypeValuesAreStable() {
        assertThat(Nodeable.Type.ORIGIN.getValue()).isZero();
        assertThat(Nodeable.Type.CUSTOMER.getValue()).isEqualTo(1);
        assertThat(Nodeable.Type.DESTINATION.getValue()).isEqualTo(2);
        for (Nodeable.Type type : Nodeable.Type.values()) {
            assertThat(Nodeable.Type.of(type.getValue())).isEqualTo(type);
        }
        assertThatThrownBy(() -> Nodeable.Type.of(99)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void optimizationTypeValuesAreStable() {
        assertThat(OptimizationType.LOCATION_ZONE.getValue()).isZero();
        assertThat(OptimizationType.NO_RESTRICTION.getValue()).isEqualTo(1);
        for (OptimizationType type : OptimizationType.values()) {
            assertThat(OptimizationType.of(type.getValue())).isEqualTo(type);
        }
        assertThatThrownBy(() -> OptimizationType.of(7)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exchangeRelocateTypeValuesAreStable() {
        assertThat(ExchangeRelocateType.SIMPLE.getValue()).isZero();
        assertThat(ExchangeRelocateType.TWO_OPT.getValue()).isEqualTo(1);
        assertThat(ExchangeRelocateType.INSERTION.getValue()).isEqualTo(2);
        for (ExchangeRelocateType type : ExchangeRelocateType.values()) {
            assertThat(ExchangeRelocateType.of(type.getValue())).isEqualTo(type);
        }
        assertThatThrownBy(() -> ExchangeRelocateType.of(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 3, 99, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void unknownPersistedValuesAreRejectedByEveryEnum(int value) {
        // 0..2 are taken by Type and ExchangeRelocateType, 0..1 by OptimizationType; nothing else may map.
        assertThatThrownBy(() -> Nodeable.Type.of(value)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(value));
        assertThatThrownBy(() -> ExchangeRelocateType.of(value)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(value));
        assertThatThrownBy(() -> OptimizationType.of(value)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

}
