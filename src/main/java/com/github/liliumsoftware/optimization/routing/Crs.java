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

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A GeoJSON coordinate reference system: a {@link CrsType} plus free-form properties (e.g. an EPSG name).
 */
@Setter
@Getter
public class Crs implements Serializable {

    private CrsType type = CrsType.NAME;
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
