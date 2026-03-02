package ir.baho.algorithm.routing;

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
 * output format keep deserializing.
 */
public class LngLatAltDeserializer extends ValueDeserializer<LngLatAlt> {

    /**
     * Accepts a coordinate array or the legacy {@code {"longitude": …}} object; anything else is reported through the
     * context as an unexpected token.
     */
    @Override
    public LngLatAlt deserialize(JsonParser parser, DeserializationContext context) {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.START_ARRAY) {
            return readCoordinateArray(parser, context);
        }
        if (token == JsonToken.START_OBJECT) {
            return readLegacyObject(parser);
        }
        return (LngLatAlt) context.handleUnexpectedToken(LngLatAlt.class, parser);
    }

    private LngLatAlt readCoordinateArray(JsonParser parser, DeserializationContext context) {
        List<Double> values = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            values.add(readDouble(parser));
        }
        if (values.size() < 2) {
            return context.reportInputMismatch(LngLatAlt.class,
                    "A GeoJSON coordinate needs at least a longitude and a latitude, got %d element(s)", values.size());
        }
        return build(values.get(0), values.get(1),
                values.size() > 2 ? values.get(2) : Double.NaN,
                values.subList(Math.min(3, values.size()), values.size()));
    }

    private LngLatAlt readLegacyObject(JsonParser parser) {
        double longitude = 0;
        double latitude = 0;
        double altitude = Double.NaN;
        List<Double> additionalElements = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String name = parser.currentName();
            parser.nextToken();
            switch (name) {
                case "longitude" -> longitude = readDouble(parser);
                case "latitude" -> latitude = readDouble(parser);
                // null (an unset altitude written by a bean serializer) means no altitude
                case "altitude" -> altitude = parser.currentToken() == JsonToken.VALUE_NULL ? Double.NaN
                        : readDouble(parser);
                case "additionalElements" -> {
                    // null (as a bean with an unset array may have been written) means no additional elements
                    if (parser.currentToken() != JsonToken.VALUE_NULL) {
                        while (parser.nextToken() != JsonToken.END_ARRAY) {
                            additionalElements.add(readDouble(parser));
                        }
                    }
                }
                default -> parser.skipChildren();
            }
        }
        return build(longitude, latitude, altitude, additionalElements);
    }

    private LngLatAlt build(double longitude, double latitude, double altitude, List<Double> additionalElements) {
        if (additionalElements.isEmpty()) {
            return Double.isNaN(altitude)
                    ? new LngLatAlt(longitude, latitude)
                    : new LngLatAlt(longitude, latitude, altitude);
        }
        double[] extras = new double[additionalElements.size()];
        for (int i = 0; i < extras.length; i++) {
            extras[i] = additionalElements.get(i);
        }
        return new LngLatAlt(longitude, latitude, altitude, extras);
    }

    /**
     * Numbers arrive as numeric tokens; {@code NaN}/{@code Infinity} survive JSON only as quoted strings, which
     * is how the altitude of an altitude-less coordinate used to be written.
     */
    private double readDouble(JsonParser parser) {
        if (parser.currentToken() == JsonToken.VALUE_STRING) {
            return Double.parseDouble(parser.getString());
        }
        return parser.getDoubleValue();
    }

}
