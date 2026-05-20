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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A GeoJSON geometry holding an ordered list of {@code T} coordinates (a point, a sequence of points, …).
 */
@Setter
@Getter
public class Geometry<T> extends GeoJsonObject {
    protected List<T> coordinates = new ArrayList<>();

    /**
     * An empty geometry.
     */
    public Geometry() {
    }

    /**
     * A geometry holding {@code elements} in order.
     */
    @SafeVarargs
    // Collections.addAll would trip javac's varargs lint
    @SuppressWarnings({"ManualArrayToCollectionCopy", "UseBulkOperation"})
    public Geometry(T... elements) {
        for (T element : elements) {
            coordinates.add(element);
        }
    }

    /**
     * Appends one coordinate and returns {@code this} for chaining.
     */
    public Geometry<T> add(T element) {
        coordinates.add(element);
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Geometry<?> geometry)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        return Objects.equals(coordinates, geometry.coordinates);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (coordinates != null ? coordinates.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "Geometry{" + "coordinates=" + coordinates + "} " + super.toString();
    }

}
