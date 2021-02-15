package ir.baho.algorithm.routing;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * Writes a {@link LngLatAlt} in the GeoJSON coordinate form {@code [longitude, latitude]}, appending the
 * altitude and any additional elements when an altitude is present (GeoJSON only allows extra elements
 * alongside an altitude, which is the invariant {@code LngLatAlt} itself enforces).
 *
 * <p>Paired with {@link LngLatAltDeserializer} so a coordinate round-trips through the standard form rather
 * than through a non-standard {@code {"longitude":…}} object that no GeoJSON consumer understands.
 */
public class LngLatAltSerializer extends JsonSerializer<LngLatAlt> {

    /**
     * Writes {@code [longitude, latitude]} plus altitude and additional elements when an altitude is present.
     */
    @Override
    public void serialize(LngLatAlt value, JsonGenerator generator, SerializerProvider context) throws IOException {
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
