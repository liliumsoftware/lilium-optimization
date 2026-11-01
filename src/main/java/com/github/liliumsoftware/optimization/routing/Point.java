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

import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.util.Objects;

/**
 * GeoJSON {@code Point}: a single {@link LngLatAlt} position, serialized as
 * {@code {"type": "Point", "coordinates": [lon, lat]}}. Also the coordinate type of every {@link RoutingProvider}
 * request, which rejects a point without coordinates or with an out-of-range longitude or latitude; the point itself
 * validates nothing.
 *
 * <p>A mutable bean like its superclass: {@code equals}/{@code hashCode} additionally compare the position by value.
 * Not thread-safe.
 */
@Setter
@Getter
public class Point extends GeoJsonObject {

    /**
     * Pinned to the value the JVM computed for the 3.0.1 release, so instances serialized by it still read back.
     */
    @Serial
    private static final long serialVersionUID = -321829526578979623L;

    /**
     * The position of this point, or {@code null} when none is set. Held by reference: the getter returns it live and
     * the setter stores it without copying.
     */
    private LngLatAlt coordinates;

    /**
     * Creates a point without coordinates (for deserialization); {@link #getCoordinates()} is {@code null} until set.
     */
    public Point() {
    }

    /**
     * Creates a point at the given position.
     *
     * @param coordinates the position, stored as given (not copied); may be {@code null}
     */
    public Point(LngLatAlt coordinates) {
        this.coordinates = coordinates;
    }

    /**
     * Creates a 2-D point (no altitude).
     *
     * @param longitude the longitude in degrees; not validated
     * @param latitude  the latitude in degrees; not validated
     */
    public Point(double longitude, double latitude) {
        coordinates = new LngLatAlt(longitude, latitude);
    }

    /**
     * Creates a 3-D point.
     *
     * @param longitude the longitude in degrees; not validated
     * @param latitude  the latitude in degrees; not validated
     * @param altitude  the altitude; {@code NaN} means none
     */
    public Point(double longitude, double latitude, double altitude) {
        coordinates = new LngLatAlt(longitude, latitude, altitude);
    }

    /**
     * Creates a 3-D point with additional elements; see {@link LngLatAlt#LngLatAlt(double, double, double, double...)}.
     *
     * @param longitude          the longitude in degrees; not validated
     * @param latitude           the latitude in degrees; not validated
     * @param altitude           the altitude, required when additional elements are given
     * @param additionalElements further position elements, copied; {@code null} means none
     * @throws IllegalArgumentException if an additional element is {@code NaN} or infinite, or if additional elements
     *                                  are given while {@code altitude} is {@code NaN}
     */
    public Point(double longitude, double latitude, double altitude, double... additionalElements) {
        coordinates = new LngLatAlt(longitude, latitude, altitude, additionalElements);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Point point)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        return Objects.equals(coordinates, point.coordinates);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (coordinates != null ? coordinates.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "Point{" + "coordinates=" + coordinates + "} " + super.toString();
    }

}
