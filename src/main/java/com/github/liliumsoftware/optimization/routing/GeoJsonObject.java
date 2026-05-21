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

import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;

/**
 * Base of the GeoJSON object hierarchy: carries the optional {@code crs} and {@code bbox} common to all
 * geometries and drives Jackson polymorphic (de)serialization by the JSON {@code "type"} property. Unknown
 * properties are ignored so responses from varied providers deserialize leniently.
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

    private Crs crs;
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
