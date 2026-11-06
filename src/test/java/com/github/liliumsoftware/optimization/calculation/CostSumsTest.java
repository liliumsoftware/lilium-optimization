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

package com.github.liliumsoftware.optimization.calculation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the overflow check every cost sum of the library goes through: sums of mixed signs are exact, and only a result
 * outside the {@code long} range is rejected, in either direction.
 */
class CostSumsTest {

    @ParameterizedTest
    @CsvSource({
            "5, -3, 2",
            "-5, 3, -2",
            "-5, -3, -8",
            "9223372036854775807, -1, 9223372036854775806",
            "-9223372036854775808, 1, -9223372036854775807",
            "9223372036854775806, 1, 9223372036854775807",
            "-9223372036854775807, -1, -9223372036854775808"
    })
    void sumsThatFitALongAreExactWhateverTheSigns(long sum, long cost, long expected) {
        assertThat(CostSums.add(sum, cost, "plan", "p")).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "9223372036854775807, 1",
            "9223372036854775000, 9223372036854775000",
            "-9223372036854775808, -1",
            "-9223372036854775000, -9223372036854775000"
    })
    void aSumOutsideTheLongRangeIsRejectedNamingWhatWasSummed(long sum, long cost) {
        assertThatThrownBy(() -> CostSums.add(sum, cost, "plan", "p1"))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("plan p1");
    }

}
