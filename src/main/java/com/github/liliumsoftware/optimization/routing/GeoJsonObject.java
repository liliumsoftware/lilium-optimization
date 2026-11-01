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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;

/**
 * Base of the GeoJSON object hierarchy: carries the optional {@code crs} and {@code bbox} members common to all
 * GeoJSON objects and drives Jackson polymorphic (de)serialization by the JSON {@code "type"} property.
 *
 * <p>The type id is the simple class name ({@link JsonTypeInfo.Id#NAME}) resolved against a closed list of subtypes:
 * {@link Point}, {@link MultiPoint}, {@link LineString} and this class itself ({@code "GeoJsonObject"}). No class-name
 * based typing is involved, so JSON from an untrusted source cannot make Jackson instantiate an arbitrary class through
 * this hierarchy; an unknown {@code "type"} fails with an {@code InvalidTypeIdException}. A raw {@link Geometry} is not
 * in the list (see there). {@code null} members are omitted on output and unknown properties are ignored on input, so
 * responses from varied providers deserialize leniently.
 *
 * <p>A mutable bean: the setters exist for Jackson binding, {@code equals}/{@code hashCode} are value-based over the
 * runtime class, {@code crs} and {@code bbox} (subclasses add their coordinates). Not thread-safe; treat an instance
 * as immutable once it is shared and do not change it while it is used as a map key or set element.
 */
@JsonTypeInfo(property = "type", use = JsonTypeInfo.Id.NAME)
@JsonSubTypes({
        @JsonSubTypes.Type(Point.class),
        @JsonSubTypes.Type(MultiPoint.class),
        @JsonSubTypes.Type(LineString.class)
})
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Setter
@Getter
public class GeoJsonObject implements Serializable {

    /**
     * Pinned to the value the JVM computed for the 3.0.1 release, so instances serialized by it still read back.
     */
    @Serial
    private static final long serialVersionUID = -5113668758283588289L;

    /**
     * The coordinate reference system member, or {@code null} when absent (the usual case: GeoJSON defaults to WGS 84).
     * Stored as given, not copied.
     */
    private Crs crs;
    /**
     * The bounding box member, {@code [minLon, minLat, maxLon, maxLat]} (or with altitudes, {@code [minLon, minLat,
     * minAlt, maxLon, maxLat, maxAlt]}), or {@code null} when absent. Its length is not checked, and the array is held
     * by reference: the getter returns it live and the setter stores it without copying.
     */
    private double[] bbox;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        GeoJsonObject that = (GeoJsonObject) o;
        if (!Objects.equals(crs, that.crs)) {
            return false;
        }
        return Arrays.equals(bbox, that.bbox);
    }

    @Override
    public int hashCode() {
        int result = crs != null ? crs.hashCode() : 0;
        result = 31 * result + (bbox != null ? Arrays.hashCode(bbox) : 0);
        return result;
    }

    @Override
    public String toString() {
        return "GeoJsonObject{}";
    }

}
