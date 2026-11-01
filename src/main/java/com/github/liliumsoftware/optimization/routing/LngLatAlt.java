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

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * A GeoJSON position: longitude, latitude, optional altitude ({@code NaN} when absent), and optional additional
 * elements. Enforces the GeoJSON rule that additional elements are only valid alongside an altitude, and rejects
 * {@code NaN}/infinite additional elements; longitude, latitude and altitude themselves are not validated (range
 * checks happen where a position is used, for example in {@link RoutingProvider}).
 *
 * <p>{@code equals}/{@code hashCode} are value-based and compare the components with {@link Double#compare}, so
 * {@code 0.0} and {@code -0.0} are <em>not</em> equal while {@code NaN} equals {@code NaN} (two positions without
 * altitude are equal when the rest matches). The class is mutable, so a position used as a map key or set element
 * must not be changed while it is stored there; it is not thread-safe.
 *
 * <p>Java serialization keeps the default serialized form (the four fields, unchanged since 3.0.1), and reading an
 * instance back restores the invariants above: missing ({@code null}) additional elements become an empty array, the
 * elements are copied so the instance never shares an array with the stream, and additional elements that are
 * {@code NaN} or infinite, or present without an altitude, make deserialization fail with
 * {@link InvalidObjectException}.
 *
 * <p>JSON form is the GeoJSON <em>array</em> {@code [longitude, latitude(, altitude, extra…)]}, not a bean
 * object — see {@link LngLatAltSerializer} / {@link LngLatAltDeserializer}. Without those handlers Jackson
 * would expect {@code {"longitude":…}}, which no GeoJSON producer emits, so every routing geometry response
 * would fail to parse.
 */
@JsonSerialize(using = LngLatAltSerializer.class)
@JsonDeserialize(using = LngLatAltDeserializer.class)
@Getter
public class LngLatAlt implements Serializable {

    /**
     * Pinned to the value the JVM computed for the 3.0.1 release, so instances serialized by it still read back.
     */
    @Serial
    private static final long serialVersionUID = 1457764513099864671L;

    /**
     * The longitude in degrees; the setter does not validate it.
     */
    @Setter
    private double longitude;
    /**
     * The latitude in degrees; the setter does not validate it.
     */
    @Setter
    private double latitude;
    /**
     * The altitude, or {@code NaN} when there is none (see {@link #hasAltitude()}). Set through
     * {@link #setAltitude(double)}, which keeps the rule that additional elements need an altitude.
     */
    private double altitude = Double.NaN;
    /**
     * Additional elements, never shared with callers ({@link #getAdditionalElements()} returns a copy); empty when
     * there are none.
     */
    @Getter(AccessLevel.NONE)
    private double[] additionalElements = new double[0];

    /**
     * Creates the position {@code (0, 0)} without altitude (for deserialization).
     */
    public LngLatAlt() {
    }

    /**
     * Creates a 2-D position (altitude {@code NaN}).
     *
     * @param longitude the longitude in degrees; not validated
     * @param latitude  the latitude in degrees; not validated
     */
    public LngLatAlt(double longitude, double latitude) {
        this.longitude = longitude;
        this.latitude = latitude;
    }

    /**
     * Creates a 3-D position.
     *
     * @param longitude the longitude in degrees; not validated
     * @param latitude  the latitude in degrees; not validated
     * @param altitude  the altitude; {@code NaN} means none
     */
    public LngLatAlt(double longitude, double latitude, double altitude) {
        this.longitude = longitude;
        this.latitude = latitude;
        this.altitude = altitude;
    }

    /**
     * Creates a 3-D position with additional elements (GeoJSON allows them only alongside an altitude).
     *
     * @param longitude          the longitude in degrees; not validated
     * @param latitude           the latitude in degrees; not validated
     * @param altitude           the altitude; must not be {@code NaN} when additional elements are given
     * @param additionalElements further position elements, copied; {@code null} or empty means none
     * @throws IllegalArgumentException if an element is {@code NaN} or infinite, or if elements are given while
     *                                  {@code altitude} is {@code NaN}
     */
    public LngLatAlt(double longitude, double latitude, double altitude, double... additionalElements) {
        this.longitude = longitude;
        this.latitude = latitude;
        this.altitude = altitude;
        this.additionalElements = validatedElements(additionalElements);
        checkAltitudeAndAdditionalElements(this.altitude, this.additionalElements);
    }

    private static double[] validatedElements(double[] additionalElements) {
        double[] elements = additionalElements == null ? new double[0] : additionalElements.clone();
        for (double element : elements) {
            if (Double.isNaN(element)) {
                throw new IllegalArgumentException("No additional elements may be NaN.");
            }
            if (Double.isInfinite(element)) {
                throw new IllegalArgumentException("No additional elements may be infinite.");
            }
        }
        return elements;
    }

    private static void checkAltitudeAndAdditionalElements(double altitude, double[] additionalElements) {
        if (Double.isNaN(altitude) && additionalElements.length > 0) {
            throw new IllegalArgumentException("Additional Elements are only valid if Altitude is also provided.");
        }
    }

    /**
     * The additional elements, as a copy: changing the returned array does not change this position.
     *
     * @return the additional elements; empty, never {@code null}, when there are none
     */
    public double[] getAdditionalElements() {
        return additionalElements.clone();
    }

    /**
     * Replaces the additional elements with a copy of {@code additionalElements}; {@code null} or an empty array
     * clears them. On failure the position is left unchanged.
     *
     * @param additionalElements the new additional elements
     * @throws IllegalArgumentException if an element is {@code NaN} or infinite, or if elements are given while no
     *                                  altitude is present
     */
    public void setAdditionalElements(double... additionalElements) {
        double[] elements = validatedElements(additionalElements);
        checkAltitudeAndAdditionalElements(this.altitude, elements);
        this.additionalElements = elements;
    }

    /**
     * Whether an altitude is present.
     *
     * @return {@code true} if the altitude is not {@code NaN}
     */
    public boolean hasAltitude() {
        return !Double.isNaN(altitude);
    }

    /**
     * Whether any additional element is present.
     *
     * @return {@code true} if there is at least one additional element
     */
    public boolean hasAdditionalElements() {
        return additionalElements.length > 0;
    }

    /**
     * Sets the altitude; {@code NaN} removes it. On failure the position is left unchanged.
     *
     * @param altitude the altitude, or {@code NaN} for none; infinite values are accepted
     * @throws IllegalArgumentException if {@code altitude} is {@code NaN} while additional elements are present
     */
    public void setAltitude(double altitude) {
        checkAltitudeAndAdditionalElements(altitude, this.additionalElements);
        this.altitude = altitude;
    }

    /**
     * Reads the default serialized form, then restores the invariants a crafted stream could break: {@code null}
     * additional elements become an empty array, the elements are copied, and invalid ones are rejected.
     *
     * @param in the stream to read from
     * @throws IOException            if reading fails, or (as {@link InvalidObjectException}) if an additional element
     *                                is {@code NaN} or infinite or additional elements are present without an
     *                                altitude
     * @throws ClassNotFoundException if a class of the serialized form cannot be found
     */
    @Serial
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        try {
            additionalElements = validatedElements(additionalElements);
            checkAltitudeAndAdditionalElements(altitude, additionalElements);
        } catch (IllegalArgumentException e) {
            throw new InvalidObjectException(e.getMessage(), e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LngLatAlt lngLatAlt)) {
            return false;
        }
        return Double.compare(lngLatAlt.latitude, latitude) == 0 && Double.compare(lngLatAlt.longitude, longitude) == 0
                && Double.compare(lngLatAlt.altitude, altitude) == 0
                && Arrays.equals(lngLatAlt.additionalElements, additionalElements);
    }

    @Override
    public int hashCode() {
        int result = Double.hashCode(longitude);
        result = 31 * result + Double.hashCode(latitude);
        result = 31 * result + Double.hashCode(altitude);
        for (double element : additionalElements) {
            result = 31 * result + Double.hashCode(element);
        }
        return result;
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder("LngLatAlt{" + "longitude=" + longitude + ", latitude=" + latitude
                + ", altitude=" + altitude);

        if (hasAdditionalElements()) {
            s.append(", additionalElements=[");

            String suffix = "";
            for (Double element : additionalElements) {
                s.append(suffix).append(element);
                suffix = ", ";
            }
            s.append(']');
        }

        s.append('}');

        return s.toString();
    }

}
