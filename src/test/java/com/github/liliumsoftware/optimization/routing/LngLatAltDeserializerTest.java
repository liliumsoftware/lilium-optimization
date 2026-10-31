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

package com.github.liliumsoftware.optimization.routing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins {@link LngLatAltDeserializer} on its own, read as a top-level value: malformed coordinates of either form fail
 * with a Jackson {@link DatabindException} (never an unchecked {@link IllegalArgumentException} or a silent 0), and the
 * valid array and legacy-object forms read back to the expected value. Also documents the edge semantics of
 * {@link LngLatAlt#equals}: {@code 0.0} and {@code -0.0} differ, a {@code NaN} altitude equals a {@code NaN} altitude.
 */
class LngLatAltDeserializerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,\"abc\"]",                                                // a string that is not a number
            "[1,null]",                                                   // a null element
            "[1,true]",                                                   // a boolean element
            "[1,[2]]",                                                    // a nested array
            "[]",                                                         // no element at all
            "[1]",                                                        // longitude only
            "[1,2,\"NaN\",4]",                                            // extras without an altitude
            "[1,2,3,\"Infinity\"]",                                       // a non-finite extra
            "5",                                                          // neither an array nor an object
            "\"1,2\"",
            "{}",                                                         // legacy object without coordinates
            "{\"longitude\":51}",                                         // ... without a latitude
            "{\"latitude\":35}",                                          // ... without a longitude
            "{\"longitude\":1,\"latitude\":2,\"additionalElements\":5}",  // extras that are not an array
            "{\"longitude\":1,\"latitude\":2,\"additionalElements\":[4]}", // extras without an altitude
            "{\"longitude\":\"east\",\"latitude\":2}"                     // a longitude that is not a number
    })
    void malformedCoordinatesFailWithADatabindException(String json) {
        assertThatThrownBy(() -> mapper.readValue(json, LngLatAlt.class))
                .as(json)
                .isInstanceOf(DatabindException.class);
    }

    @Test
    void theArrayFormReadsAltitudeAndExtras() {
        assertThat(mapper.readValue("[1.5,2.5]", LngLatAlt.class)).isEqualTo(new LngLatAlt(1.5, 2.5));
        assertThat(mapper.readValue("[1.5,2.5,3]", LngLatAlt.class)).isEqualTo(new LngLatAlt(1.5, 2.5, 3));
        assertThat(mapper.readValue("[1,2,3,4,5]", LngLatAlt.class)).isEqualTo(new LngLatAlt(1, 2, 3, 4, 5));
    }

    @Test
    void quotedNumbersAreReadSoNaNAndInfinitySurviveJson() {
        // An altitude written as the string "NaN" means "no altitude"; a quoted longitude is still a number.
        assertThat(mapper.readValue("[\"1.5\",2,\"NaN\"]", LngLatAlt.class)).isEqualTo(new LngLatAlt(1.5, 2));
        assertThat(mapper.readValue("[1,2,\"Infinity\"]", LngLatAlt.class).getAltitude())
                .isEqualTo(Double.POSITIVE_INFINITY);
    }

    @Test
    void aValidLegacyObjectStillReads() {
        LngLatAlt full = mapper.readValue(
                "{\"longitude\":1,\"latitude\":2,\"altitude\":3,\"additionalElements\":[4,5],\"ignored\":{\"x\":[1]}}",
                LngLatAlt.class);
        LngLatAlt flat = mapper.readValue("{\"latitude\":\"2\",\"longitude\":1}", LngLatAlt.class);

        assertThat(full).isEqualTo(new LngLatAlt(1, 2, 3, 4, 5));
        assertThat(flat).isEqualTo(new LngLatAlt(1, 2));
        assertThat(flat.hasAltitude()).isFalse();
    }

    @Test
    void aCoordinateRoundTripsThroughItsOwnJsonForm() {
        for (LngLatAlt original : new LngLatAlt[] {new LngLatAlt(-0.125, 51.5), new LngLatAlt(1, 2, -3),
                new LngLatAlt(1, 2, 3, 4, 5)}) {
            assertThat(mapper.readValue(mapper.writeValueAsString(original), LngLatAlt.class)).isEqualTo(original);
        }
    }

    // ---------------------------------------------------------------- equality semantics (documented as is)

    @Test
    // the equals contract itself is under test
    @SuppressWarnings("EqualsWithItself")
    void positiveAndNegativeZeroAreDifferentCoordinates() {
        // Double.compare semantics: -0.0 < 0.0, so the two are not equal (and their hash codes differ as well).
        assertThat(new LngLatAlt(0.0, 1.0)).isNotEqualTo(new LngLatAlt(-0.0, 1.0));
        assertThat(new LngLatAlt(1.0, 0.0)).isNotEqualTo(new LngLatAlt(1.0, -0.0));
        assertThat(new LngLatAlt(1.0, 2.0, 0.0)).isNotEqualTo(new LngLatAlt(1.0, 2.0, -0.0));
        assertThat(new LngLatAlt(0.0, 1.0).equals(new LngLatAlt(0.0, 1.0))).isTrue();
    }

    @Test
    // the equals contract itself is under test
    @SuppressWarnings("EqualsWithItself")
    void aMissingAltitudeEqualsAMissingAltitude() {
        // Both altitudes are NaN, which Double.compare treats as equal to itself; so do NaN coordinates.
        assertThat(new LngLatAlt(1.0, 2.0)).isEqualTo(new LngLatAlt(1.0, 2.0, Double.NaN))
                .hasSameHashCodeAs(new LngLatAlt(1.0, 2.0, Double.NaN));
        assertThat(new LngLatAlt(Double.NaN, 2.0)).isEqualTo(new LngLatAlt(Double.NaN, 2.0));
    }

    @Test
    void everyComponentParticipatesInEqualityAndHashCode() {
        LngLatAlt base = new LngLatAlt(1, 2, 3, 4, 5);

        assertThat(base).isEqualTo(new LngLatAlt(1, 2, 3, 4, 5)).hasSameHashCodeAs(new LngLatAlt(1, 2, 3, 4, 5));
        assertThat(base).isNotEqualTo(new LngLatAlt(9, 2, 3, 4, 5));      // longitude
        assertThat(base).isNotEqualTo(new LngLatAlt(1, 9, 3, 4, 5));      // latitude
        assertThat(base).isNotEqualTo(new LngLatAlt(1, 2, 9, 4, 5));      // altitude
        assertThat(base).isNotEqualTo(new LngLatAlt(1, 2, 3, 4, 9));      // an extra
        assertThat(base).isNotEqualTo(new LngLatAlt(1, 2, 3, 4));         // fewer extras
        assertThat(base.hashCode()).isNotEqualTo(new LngLatAlt(1, 2, 3, 4, 9).hashCode());
        assertThat(base.hashCode()).isNotEqualTo(new LngLatAlt(1, 2, 3).hashCode());
    }

    @Test
    void settingNullAdditionalElementsClearsThem() {
        LngLatAlt coordinate = new LngLatAlt(1, 2, 3, 4);

        coordinate.setAdditionalElements((double[]) null);

        assertThat(coordinate.hasAdditionalElements()).isFalse();
        assertThat(coordinate.getAdditionalElements()).isEmpty();
        assertThat(new LngLatAlt(1, 2, 3, (double[]) null)).isEqualTo(new LngLatAlt(1, 2, 3));
    }

    @Test
    void theAltitudeCanBeRemovedWhenNoExtrasDependOnIt() {
        LngLatAlt coordinate = new LngLatAlt(1, 2, 3);

        coordinate.setAltitude(Double.NaN);

        assertThat(coordinate.hasAltitude()).isFalse();
        assertThat(coordinate).isEqualTo(new LngLatAlt(1, 2));
        assertThat(new LngLatAlt(1, 2, Double.NaN, new double[0])).isEqualTo(new LngLatAlt(1, 2));
    }

    @Test
    void theAltitudeCannotBeRemovedWhileExtrasDependOnIt() {
        LngLatAlt coordinate = new LngLatAlt(1, 2, 3, 4);

        assertThatThrownBy(() -> coordinate.setAltitude(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThat(coordinate.getAltitude()).isEqualTo(3);
        assertThatThrownBy(() -> coordinate.setAdditionalElements(Double.NEGATIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("infinite");
        assertThat(coordinate.getAdditionalElements()).containsExactly(4.0);
    }

}
