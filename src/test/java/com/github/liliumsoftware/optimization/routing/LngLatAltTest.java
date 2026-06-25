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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the {@link LngLatAlt} value contract: equality, altitude/element validation, and Java serialization.
 */
class LngLatAltTest {

    @Test
    void equalityIsValueBased() {
        LngLatAlt coordinate = new LngLatAlt(1.0, 2.0);
        LngLatAlt sameValue = new LngLatAlt(1.0, 2.0);

        assertThat(coordinate).isEqualTo(sameValue);
        assertThat(coordinate).hasSameHashCodeAs(sameValue);
        assertThat(new LngLatAlt(1.0, 2.0)).isNotEqualTo(new LngLatAlt(1.0, 2.5));
        assertThat(new LngLatAlt(1.0, 2.0, 3.0)).isNotEqualTo(new LngLatAlt(1.0, 2.0));
    }

    @Test
    void altitudePresenceIsReported() {
        assertThat(new LngLatAlt(1.0, 2.0).hasAltitude()).isFalse();
        assertThat(new LngLatAlt(1.0, 2.0, 3.0).hasAltitude()).isTrue();
    }

    @Test
    void additionalElementsRequireAltitude() {
        assertThatThrownBy(() -> new LngLatAlt(1.0, 2.0).setAdditionalElements(5.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonFiniteAdditionalElementsAreRejected() {
        assertThatThrownBy(() -> new LngLatAlt(1.0, 2.0, 3.0, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LngLatAlt(1.0, 2.0, 3.0, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validAdditionalElementsAreKept() {
        LngLatAlt coordinate = new LngLatAlt(1.0, 2.0, 3.0, 4.0, 5.0);
        assertThat(coordinate.hasAdditionalElements()).isTrue();
        assertThat(coordinate.getAdditionalElements()).containsExactly(4.0, 5.0);
    }

    @Test
    void aCoordinateIsMutableThroughItsSetters() {
        LngLatAlt coordinate = new LngLatAlt();

        assertThat(coordinate.getLongitude()).isZero();
        assertThat(coordinate.getLatitude()).isZero();
        assertThat(coordinate.hasAltitude()).as("altitude defaults to absent (NaN)").isFalse();
        assertThat(coordinate.hasAdditionalElements()).isFalse();

        coordinate.setLongitude(4.5);
        coordinate.setLatitude(51.2);
        coordinate.setAltitude(12.0);

        assertThat(coordinate).isEqualTo(new LngLatAlt(4.5, 51.2, 12.0));
    }

    @Test
    void clearingTheAdditionalElementsIsAllowedWhileAnAltitudeIsSet() {
        LngLatAlt coordinate = new LngLatAlt(1.0, 2.0, 3.0, 4.0);

        coordinate.setAdditionalElements();

        assertThat(coordinate.hasAdditionalElements()).isFalse();
        assertThat(coordinate).isEqualTo(new LngLatAlt(1.0, 2.0, 3.0));
    }

    @Test
    void settingAnAltitudeOnACoordinateThatHasExtrasKeepsThemValid() {
        LngLatAlt coordinate = new LngLatAlt(1.0, 2.0, 3.0, 4.0);

        coordinate.setAltitude(9.0);

        assertThat(coordinate.getAltitude()).isEqualTo(9.0);
        assertThat(coordinate.getAdditionalElements()).containsExactly(4.0);
    }

    @Test
    // the equals contract under test
    @SuppressWarnings({"AssertBetweenInconvertibleTypes", "EqualsWithItself"})
    void isNeverEqualToNullOrAnotherType() {
        LngLatAlt coordinate = new LngLatAlt(1.0, 2.0);

        assertThat(coordinate).isNotEqualTo(null).isNotEqualTo("1.0,2.0");
        assertThat(coordinate.equals(coordinate)).as("reflexive").isTrue();
    }

    @Test
    void theAdditionalElementsCannotBeChangedThroughTheGetter() {
        // The getter used to hand out the internal array, so a caller could write NaN into a validated coordinate.
        LngLatAlt coordinate = new LngLatAlt(1, 2, 3, 4);

        coordinate.getAdditionalElements()[0] = Double.NaN;

        assertThat(coordinate.getAdditionalElements()).containsExactly(4.0);
        assertThat(coordinate).isEqualTo(new LngLatAlt(1, 2, 3, 4));
    }

    @Test
    void toStringShowsTheCoordinateAndAnyExtras() {
        assertThat(new LngLatAlt(1.0, 2.0).toString())
                .contains("longitude=1.0").contains("latitude=2.0").doesNotContain("additionalElements");
        assertThat(new LngLatAlt(1.0, 2.0, 3.0, 4.0, 5.0).toString())
                .contains("additionalElements=[4.0, 5.0]");
    }

    @Test
    void survivesJavaSerializationRoundTrip() throws Exception {
        LngLatAlt original = new LngLatAlt(51.5, -0.12, 42.0, 7.0);
        assertThat(roundTrip(original)).isEqualTo(original);
    }

    private LngLatAlt roundTrip(LngLatAlt value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (LngLatAlt) in.readObject();
        }
    }

}
