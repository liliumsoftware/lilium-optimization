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
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins Java serialization of {@link LngLatAlt}: the serialized form is byte for byte the one 3.0.1 wrote, and reading
 * a stream back restores the class invariants even when the stream was crafted (here: an instance whose private
 * fields were forced into an invalid state by reflection before it was written, which yields exactly the stream an
 * attacker could hand-craft).
 */
class LngLatAltSerializationTest {

    /**
     * {@code new LngLatAlt(51.5, -0.12, 42.0, 7.0)} as serialized by the 3.0.1 release.
     */
    private static final String SERIALIZED_BY_3_0_1 = String.join("",
            "aced000573720038636f6d2e6769746875622e6c696c69756d736f6674776172652e6f7074696d697a6174696f6e2e72",
            "6f7574696e672e4c6e674c6174416c74143b0519e17cda5f020004440008616c7469747564654400086c617469747564",
            "654400096c6f6e6769747564655b00126164646974696f6e616c456c656d656e74737400025b44787040450000000000",
            "00bfbeb851eb851eb84049c00000000000757200025b443ea68c14ab635a1e020000787000000001401c000000000000");

    private static byte[] serialize(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }

    private static Object deserialize(byte[] bytes) throws Exception {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return in.readObject();
        }
    }

    /**
     * Forces a private field past the setters' validation, as a crafted stream would.
     */
    private static void force(LngLatAlt target, String name, Object value) throws Exception {
        Field field = LngLatAlt.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object field(LngLatAlt target, String name) throws Exception {
        Field field = LngLatAlt.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    @Test
    void theSerializedFormIsTheOneOf301() throws Exception {
        LngLatAlt position = new LngLatAlt(51.5, -0.12, 42.0, 7.0);

        assertThat(HexFormat.of().formatHex(serialize(position))).isEqualTo(SERIALIZED_BY_3_0_1);
        assertThat(deserialize(HexFormat.of().parseHex(SERIALIZED_BY_3_0_1))).isEqualTo(position);
    }

    @Test
    void validPositionsSurviveARoundTrip() throws Exception {
        for (LngLatAlt position : List.of(new LngLatAlt(), new LngLatAlt(1.0, 2.0), new LngLatAlt(1.0, 2.0, 3.0),
                new LngLatAlt(1.0, 2.0, Double.POSITIVE_INFINITY), new LngLatAlt(1.0, 2.0, 3.0, 4.0, -5.0))) {
            LngLatAlt copy = (LngLatAlt) deserialize(serialize(position));

            assertThat(copy).isEqualTo(position).hasSameHashCodeAs(position);
            assertThat(copy.getAdditionalElements()).containsExactly(position.getAdditionalElements());
        }
    }

    @Test
    void nullAdditionalElementsAreRestoredAsEmpty() throws Exception {
        // Used to come back as null, so equals, hashCode, toString and getAdditionalElements threw
        // NullPointerException.
        LngLatAlt crafted = new LngLatAlt(1.0, 2.0, 3.0);
        force(crafted, "additionalElements", null);

        LngLatAlt read = (LngLatAlt) deserialize(serialize(crafted));

        assertThat(read.getAdditionalElements()).isEmpty();
        assertThat(read.hasAdditionalElements()).isFalse();
        assertThat(read).isEqualTo(new LngLatAlt(1.0, 2.0, 3.0)).hasSameHashCodeAs(new LngLatAlt(1.0, 2.0, 3.0));
        assertThat(read.toString()).isEqualTo(new LngLatAlt(1.0, 2.0, 3.0).toString());
    }

    @Test
    void additionalElementsWithoutAnAltitudeAreRejected() throws Exception {
        LngLatAlt crafted = new LngLatAlt(1.0, 2.0, 3.0, 4.0);
        force(crafted, "altitude", Double.NaN);
        byte[] stream = serialize(crafted);

        assertThatThrownBy(() -> deserialize(stream))
                .isInstanceOf(InvalidObjectException.class)
                .hasMessageContaining("only valid if Altitude is also provided")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonFiniteAdditionalElementsAreRejected() throws Exception {
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            LngLatAlt crafted = new LngLatAlt(1.0, 2.0, 3.0, 4.0);
            force(crafted, "additionalElements", new double[] {4.0, bad});
            byte[] stream = serialize(crafted);

            assertThatThrownBy(() -> deserialize(stream)).as("element %s", bad)
                    .isInstanceOf(InvalidObjectException.class)
                    .hasMessageContaining("No additional elements may be");
        }
    }

    @Test
    void aDeserializedPositionDoesNotShareItsElementsWithAnotherObjectOfTheStream() throws Exception {
        // A stream may refer to one array from two objects (a back-reference); each position must own its copy.
        LngLatAlt first = new LngLatAlt(1.0, 2.0, 3.0, 4.0);
        LngLatAlt second = new LngLatAlt(5.0, 6.0, 7.0, 8.0);
        force(second, "additionalElements", field(first, "additionalElements"));

        List<?> read = (List<?>) deserialize(serialize(new java.util.ArrayList<>(List.of(first, second))));

        assertThat(field((LngLatAlt) read.get(0), "additionalElements"))
                .isNotSameAs(field((LngLatAlt) read.get(1), "additionalElements"));
        assertThat(((LngLatAlt) read.get(1)).getAdditionalElements()).containsExactly(4.0);
    }

}
