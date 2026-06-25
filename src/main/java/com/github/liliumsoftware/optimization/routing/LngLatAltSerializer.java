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

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Writes a {@link LngLatAlt} in the GeoJSON coordinate form {@code [longitude, latitude]}, appending the
 * altitude and any additional elements when an altitude is present (GeoJSON only allows extra elements
 * alongside an altitude, which is the invariant {@code LngLatAlt} itself enforces).
 *
 * <p>Paired with {@link LngLatAltDeserializer} so a coordinate round-trips through the standard form rather
 * than through a non-standard {@code {"longitude":…}} object that no GeoJSON consumer understands.
 */
public class LngLatAltSerializer extends ValueSerializer<LngLatAlt> {

    /**
     * Writes {@code [longitude, latitude]} plus altitude and additional elements when an altitude is present.
     */
    @Override
    public void serialize(LngLatAlt value, JsonGenerator generator, SerializationContext context) {
        generator.writeStartArray();
        generator.writeNumber(value.getLongitude());
        generator.writeNumber(value.getLatitude());
        if (value.hasAltitude()) {
            generator.writeNumber(value.getAltitude());
            for (double element : value.getAdditionalElements()) {
                generator.writeNumber(element);
            }
        }
        generator.writeEndArray();
    }

}
