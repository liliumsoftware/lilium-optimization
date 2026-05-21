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

import java.util.Objects;

/**
 * GeoJSON {@code Point}: a single {@link LngLatAlt} coordinate. Used as the routing request coordinate type.
 */
@Setter
@Getter
public class Point extends GeoJsonObject {

    private LngLatAlt coordinates;

    /**
     * A point without coordinates (for deserialization).
     */
    public Point() {
    }

    /**
     * A point at the given coordinate.
     */
    public Point(LngLatAlt coordinates) {
        this.coordinates = coordinates;
    }

    /**
     * A 2-D point.
     */
    public Point(double longitude, double latitude) {
        coordinates = new LngLatAlt(longitude, latitude);
    }

    /**
     * A 3-D point.
     */
    public Point(double longitude, double latitude, double altitude) {
        coordinates = new LngLatAlt(longitude, latitude, altitude);
    }

    /**
     * A 3-D point with additional elements; see {@link LngLatAlt#LngLatAlt(double, double, double, double...)}.
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
