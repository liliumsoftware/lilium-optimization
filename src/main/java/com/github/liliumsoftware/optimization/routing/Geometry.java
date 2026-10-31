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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A GeoJSON geometry holding an ordered list of {@code T} elements in its {@code coordinates} member; the concrete
 * geometries {@link MultiPoint} and {@link LineString} use {@link LngLatAlt} positions.
 *
 * <p>Meant to be used through its subclasses. A raw {@code Geometry} serializes with {@code "type": "Geometry"},
 * which is not a registered subtype of {@link GeoJsonObject}: reading it back as a {@code GeoJsonObject} fails, and
 * reading it as a {@code Geometry} yields untyped coordinates (plain lists of numbers rather than {@code T}).
 *
 * <p>A mutable bean like its superclass: {@code equals}/{@code hashCode} additionally compare the coordinate list by
 * value. Not thread-safe.
 *
 * @param <T> the type of one element of {@code coordinates}
 */
@Setter
@Getter
public class Geometry<T> extends GeoJsonObject {

    /**
     * Pinned to the value the JVM computed for the 3.0.1 release, so instances serialized by it still read back.
     */
    @Serial
    private static final long serialVersionUID = -5502046189612322391L;

    /**
     * The coordinate elements in order, initially an empty, mutable {@link ArrayList}. Held by reference: the getter
     * returns the live list and the setter stores the given list without copying it ({@code null} included; it must be
     * mutable for {@link #add(Object)} to work).
     */
    // Jackson and the default bind an ArrayList; a caller-supplied list must be serializable itself
    // (javac's serial lint; IntelliJ only knows its own, disabled, serial inspection)
    @SuppressWarnings({"serial", "RedundantSuppression"})
    protected List<T> coordinates = new ArrayList<>();

    /**
     * Creates a geometry without coordinates.
     */
    public Geometry() {
    }

    /**
     * Creates a geometry holding {@code elements} in order; the elements themselves are not copied.
     *
     * @param elements the coordinate elements; must not be {@code null} (individual {@code null} elements are
     *                 stored as is)
     * @throws NullPointerException if {@code elements} is {@code null}
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
     * Appends one element to {@code coordinates}.
     *
     * @param element the element to append, stored as given ({@code null} included)
     * @return this geometry, for chaining
     * @throws NullPointerException if the coordinate list was set to {@code null}
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
