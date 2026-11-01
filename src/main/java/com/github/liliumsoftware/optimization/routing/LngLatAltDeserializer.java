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

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a {@link LngLatAlt} from the GeoJSON coordinate form — a JSON <em>array</em>
 * {@code [longitude, latitude]}, optionally {@code [longitude, latitude, altitude, extra…]}.
 *
 * <p>Without this, Jackson treats {@code LngLatAlt} as a plain bean and expects
 * {@code {"longitude":…,"latitude":…}}, which no GeoJSON producer emits: every OSRM {@code geometry} payload
 * would fail to deserialize and {@link RoutingProvider#getRoute} could never return a route.
 *
 * <p>The legacy object form is still accepted on input so payloads persisted before the array form became the
 * output format keep deserializing; it must name both {@code longitude} and {@code latitude}.
 *
 * <p>Malformed input — another token than an array or object, fewer than two array elements, a missing longitude or
 * latitude, an element that is not a number (numbers may also be quoted, which is how {@code NaN} and
 * {@code Infinity} survive JSON), or additional elements the {@link LngLatAlt} rules reject — is reported through the
 * {@link DeserializationContext}, so it always surfaces as a {@link tools.jackson.core.JacksonException} subtype.
 * Stateless and thread-safe.
 */
public class LngLatAltDeserializer extends ValueDeserializer<LngLatAlt> {

    /**
     * Creates the deserializer; Jackson instantiates it through {@link LngLatAlt}'s {@code @JsonDeserialize}.
     */
    public LngLatAltDeserializer() {
    }

    /**
     * Accepts a coordinate array or the legacy {@code {"longitude": …}} object.
     *
     * @param parser  positioned at the first token of the value
     * @param context reports malformed input
     * @return the coordinate
     * @throws tools.jackson.databind.DatabindException if the input is not a valid coordinate (see the class
     *                                                  description)
     * @throws tools.jackson.core.JacksonException       if the underlying JSON cannot be read
     */
    @Override
    public LngLatAlt deserialize(JsonParser parser, DeserializationContext context) {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.START_ARRAY) {
            return readCoordinateArray(parser, context);
        }
        if (token == JsonToken.START_OBJECT) {
            return readLegacyObject(parser, context);
        }
        return (LngLatAlt) context.handleUnexpectedToken(LngLatAlt.class, parser);
    }

    private LngLatAlt readCoordinateArray(JsonParser parser, DeserializationContext context) {
        List<Double> values = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            values.add(readDouble(parser, context));
        }
        if (values.size() < 2) {
            return context.reportInputMismatch(LngLatAlt.class,
                    "A GeoJSON coordinate needs at least a longitude and a latitude, got %d element(s)", values.size());
        }
        return build(context, values.get(0), values.get(1),
                values.size() > 2 ? values.get(2) : Double.NaN,
                values.subList(Math.min(3, values.size()), values.size()));
    }

    private LngLatAlt readLegacyObject(JsonParser parser, DeserializationContext context) {
        Double longitude = null;
        Double latitude = null;
        double altitude = Double.NaN;
        List<Double> additionalElements = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String name = parser.currentName();
            parser.nextToken();
            switch (name) {
                case "longitude" -> longitude = readDouble(parser, context);
                case "latitude" -> latitude = readDouble(parser, context);
                // null (an unset altitude written by a bean serializer) means no altitude
                case "altitude" -> altitude = parser.currentToken() == JsonToken.VALUE_NULL ? Double.NaN
                        : readDouble(parser, context);
                case "additionalElements" -> readAdditionalElements(parser, context, additionalElements);
                default -> parser.skipChildren();
            }
        }
        if (longitude == null || latitude == null) {
            // a missing key used to read as 0, a plausible coordinate in the Gulf of Guinea
            return context.reportInputMismatch(LngLatAlt.class,
                    "A coordinate object needs both a longitude and a latitude");
        }
        return build(context, longitude, latitude, altitude, additionalElements);
    }

    /**
     * Reads the legacy {@code additionalElements} array; {@code null} (as a bean with an unset array may have been
     * written) means none.
     */
    private void readAdditionalElements(JsonParser parser, DeserializationContext context, List<Double> elements) {
        if (parser.currentToken() == JsonToken.VALUE_NULL) {
            return;
        }
        if (parser.currentToken() != JsonToken.START_ARRAY) {
            context.reportInputMismatch(LngLatAlt.class, "additionalElements must be an array, got %s",
                    parser.currentToken());
        }
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            elements.add(readDouble(parser, context));
        }
    }

    private LngLatAlt build(DeserializationContext context, double longitude, double latitude, double altitude,
            List<Double> additionalElements) {
        if (additionalElements.isEmpty()) {
            return Double.isNaN(altitude)
                    ? new LngLatAlt(longitude, latitude)
                    : new LngLatAlt(longitude, latitude, altitude);
        }
        double[] extras = new double[additionalElements.size()];
        for (int i = 0; i < extras.length; i++) {
            extras[i] = additionalElements.get(i);
        }
        try {
            return new LngLatAlt(longitude, latitude, altitude, extras);
        } catch (IllegalArgumentException e) {
            return context.reportInputMismatch(LngLatAlt.class, "Invalid GeoJSON coordinate: %s", e.getMessage());
        }
    }

    /**
     * Numbers arrive as numeric tokens; {@code NaN}/{@code Infinity} survive JSON only as quoted strings, which
     * is how the altitude of an altitude-less coordinate used to be written.
     */
    private double readDouble(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() == JsonToken.VALUE_STRING) {
            String text = parser.getString();
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException e) {
                return (Double) context.handleWeirdStringValue(Double.class, text, "not a number");
            }
        }
        if (!parser.currentToken().isNumeric()) {
            return (Double) context.handleUnexpectedToken(Double.class, parser);
        }
        return parser.getDoubleValue();
    }

}
