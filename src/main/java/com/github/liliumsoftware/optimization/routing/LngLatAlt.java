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

import java.io.Serializable;
import java.util.Arrays;

/**
 * A GeoJSON coordinate: longitude, latitude, optional altitude, and optional additional elements. Follows the
 * GeoJSON rule that additional elements are only valid alongside an altitude, and rejects NaN/infinite extras.
 * {@code equals}/{@code hashCode} are value-based. The class is mutable, so a coordinate used as a map key or set
 * element must not be changed while it is stored there.
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

    @Setter
    private double longitude;
    @Setter
    private double latitude;
    private double altitude = Double.NaN;
    @Getter(AccessLevel.NONE)
    private double[] additionalElements = new double[0];

    /**
     * The origin {@code (0, 0)} without altitude (for deserialization).
     */
    public LngLatAlt() {
    }

    /**
     * A 2-D coordinate (altitude {@code NaN}).
     */
    public LngLatAlt(double longitude, double latitude) {
        this.longitude = longitude;
        this.latitude = latitude;
    }

    /**
     * A 3-D coordinate.
     */
    public LngLatAlt(double longitude, double latitude, double altitude) {
        this.longitude = longitude;
        this.latitude = latitude;
        this.altitude = altitude;
    }

    /**
     * A 3-D coordinate with additional elements (GeoJSON allows them only alongside an altitude).
     *
     * @throws IllegalArgumentException if an element is NaN or infinite, or if elements are given without an
     *                                  altitude
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
     * A copy of the additional elements (empty when there are none); changing it does not change this coordinate.
     */
    public double[] getAdditionalElements() {
        return additionalElements.clone();
    }

    /**
     * Replaces the additional elements; {@code null} clears them.
     *
     * @throws IllegalArgumentException if an element is NaN or infinite, or if no altitude is present
     */
    public void setAdditionalElements(double... additionalElements) {
        double[] elements = validatedElements(additionalElements);
        checkAltitudeAndAdditionalElements(this.altitude, elements);
        this.additionalElements = elements;
    }

    /**
     * Whether an altitude is present (not {@code NaN}).
     */
    public boolean hasAltitude() {
        return !Double.isNaN(altitude);
    }

    /**
     * Whether any additional element is present.
     */
    public boolean hasAdditionalElements() {
        return additionalElements.length > 0;
    }

    /**
     * Sets the altitude.
     *
     * @throws IllegalArgumentException if set to {@code NaN} while additional elements are present
     */
    public void setAltitude(double altitude) {
        checkAltitudeAndAdditionalElements(altitude, this.additionalElements);
        this.altitude = altitude;
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
