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
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A GeoJSON (2008) coordinate reference system member: a {@link CrsType} plus free-form properties, for example
 * {@code {"name": "urn:ogc:def:crs:EPSG::4326"}} for a named system or {@code href}/{@code type} for a linked one.
 * Serialized by Jackson as {@code {"properties": {…}, "type": "name"}}.
 *
 * <p>A mutable bean: {@code equals}/{@code hashCode} compare the type and the properties map by value, so an instance
 * must not be changed while it is used as a map key or set element. Not thread-safe; treat an instance as immutable
 * once it is shared.
 */
@Setter
@Getter
public class Crs implements Serializable {

    /**
     * Pinned to the value the JVM computed for the 3.0.1 release, so instances serialized by it still read back.
     */
    @Serial
    private static final long serialVersionUID = 5184262350990860845L;

    /**
     * The kind of reference; {@link CrsType#NAME} unless changed. {@code null} is stored as is.
     */
    private CrsType type = CrsType.NAME;
    /**
     * The CRS properties, initially an empty, mutable {@link HashMap}. Held by reference: the getter returns the live
     * map and the setter stores the given map without copying it ({@code null} included). Values are whatever Jackson
     * binds the JSON values to (strings, numbers, nested maps or lists).
     */
    // Jackson and the default bind a HashMap; a caller-supplied map must be serializable itself
    // (javac's serial lint; IntelliJ only knows its own, disabled, serial inspection)
    @SuppressWarnings({"serial", "RedundantSuppression"})
    private Map<String, Object> properties = new HashMap<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Crs crs)) {
            return false;
        }
        if (!Objects.equals(properties, crs.properties)) {
            return false;
        }
        return Objects.equals(type, crs.type);
    }

    @Override
    public int hashCode() {
        int result = type != null ? type.hashCode() : 0;
        result = 31 * result + (properties != null ? properties.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "Crs{" + "type='" + type + '\'' + ", properties=" + properties + '}';
    }

}
