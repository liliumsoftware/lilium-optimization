package ir.baho.algorithm.routing;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The GeoJSON value types are the API surface a host app deserializes routing responses into, so two things
 * must hold: Jackson must be able to reconstruct the exact concrete geometry from the JSON {@code "type"}
 * discriminator alone (that is how {@link RoutingProvider#getRoute} turns an OSRM payload into a
 * {@link LineString}), and equality must stay value-based and class-strict, since {@code LineString} and
 * {@code MultiPoint} are otherwise structurally identical and would silently compare equal.
 */
class GeoJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void pointRoundTripsThroughTheTypeDiscriminator() throws Exception {
        Point original = new Point(1.5, 2.5);

        String json = mapper.writeValueAsString(original);
        GeoJsonObject restored = mapper.readValue(json, GeoJsonObject.class);

        assertThat(json).contains("\"type\":\"Point\"");
        assertThat(restored).isExactlyInstanceOf(Point.class).isEqualTo(original);
    }

    @Test
    void multiPointRoundTripsThroughTheTypeDiscriminator() throws Exception {
        MultiPoint original = new MultiPoint(new LngLatAlt(1.0, 2.0), new LngLatAlt(3.0, 4.0));

        String json = mapper.writeValueAsString(original);
        GeoJsonObject restored = mapper.readValue(json, GeoJsonObject.class);

        assertThat(json).contains("\"type\":\"MultiPoint\"");
        assertThat(restored).isExactlyInstanceOf(MultiPoint.class).isEqualTo(original);
    }

    @Test
    void lineStringRoundTripsThroughTheTypeDiscriminator() throws Exception {
        LineString original = new LineString(new LngLatAlt(1.0, 2.0), new LngLatAlt(3.0, 4.0));

        String json = mapper.writeValueAsString(original);
        GeoJsonObject restored = mapper.readValue(json, GeoJsonObject.class);

        assertThat(json).contains("\"type\":\"LineString\"");
        assertThat(restored).isExactlyInstanceOf(LineString.class).isEqualTo(original);
    }

    @Test
    void osrmGeometryPayloadDeserializesIntoCoordinates() throws Exception {
        String payload = "{\"type\":\"LineString\",\"coordinates\":[[1.0,2.0],[3.0,4.0]]}";

        LineString geometry = mapper.readValue(payload, LineString.class);

        assertThat(geometry.getCoordinates())
                .containsExactly(new LngLatAlt(1.0, 2.0), new LngLatAlt(3.0, 4.0));
    }

    @Test
    void altitudeSurvivesRoundTripAndAbsentAltitudeStaysNaN() throws Exception {
        Point withAltitude = new Point(1.0, 2.0, 3.0);
        Point withoutAltitude = new Point(1.0, 2.0);

        Point restoredWithAltitude = mapper.readValue(mapper.writeValueAsString(withAltitude), Point.class);
        Point restoredWithoutAltitude = mapper.readValue(mapper.writeValueAsString(withoutAltitude), Point.class);

        assertThat(restoredWithAltitude.getCoordinates().hasAltitude()).isTrue();
        assertThat(restoredWithAltitude).isEqualTo(withAltitude);
        assertThat(restoredWithoutAltitude.getCoordinates().hasAltitude()).isFalse();
        assertThat(restoredWithoutAltitude).isEqualTo(withoutAltitude);
    }

    @Test
    void unknownPropertiesAreIgnored() throws Exception {
        String payload = "{\"type\":\"LineString\",\"coordinates\":[[1.0,2.0]],\"unexpected\":{\"a\":1}}";

        LineString geometry = mapper.readValue(payload, LineString.class);

        assertThat(geometry.getCoordinates()).containsExactly(new LngLatAlt(1.0, 2.0));
    }

    @Test
    void equalityIsValueBased() throws Exception {
        Point one = new Point(1.0, 2.0);
        Point other = new Point(1.0, 2.0);

        assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
        assertThat(one).isNotEqualTo(new Point(1.0, 2.5));
        assertThat(new MultiPoint(new LngLatAlt(1.0, 2.0)))
                .isEqualTo(new MultiPoint(new LngLatAlt(1.0, 2.0)));
        assertThat(new MultiPoint(new LngLatAlt(1.0, 2.0), new LngLatAlt(3.0, 4.0)))
                .isNotEqualTo(new MultiPoint(new LngLatAlt(3.0, 4.0), new LngLatAlt(1.0, 2.0)));
    }

    @Test
    // the equals contract under test
    @SuppressWarnings("EqualsBetweenInconvertibleTypes")
    void geometriesOfDifferentClassesAreNeverEqual() throws Exception {
        MultiPoint multiPoint = new MultiPoint(new LngLatAlt(1.0, 2.0));
        LineString lineString = new LineString(new LngLatAlt(1.0, 2.0));
        Point point = new Point(1.0, 2.0);

        assertThat(multiPoint).isNotEqualTo(lineString);
        assertThat(lineString).isNotEqualTo(multiPoint);
        // deliberately comparing unrelated geometry types: equals must answer false, not throw
        assertThat(point.equals(multiPoint)).isFalse();
        assertThat(multiPoint.equals(point)).isFalse();
    }

    @Test
    void crsParticipatesInEquality() throws Exception {
        Point withoutCrs = new Point(1.0, 2.0);
        Point withCrs = new Point(1.0, 2.0);
        withCrs.setCrs(new Crs());

        assertThat(withCrs).isNotEqualTo(withoutCrs);
        assertThat(withoutCrs).isNotEqualTo(withCrs);

        Point withSameCrs = new Point(1.0, 2.0);
        withSameCrs.setCrs(new Crs());
        assertThat(withCrs).isEqualTo(withSameCrs).hasSameHashCodeAs(withSameCrs);

        Crs linked = new Crs();
        linked.setType(CrsType.LINK);
        Point withLinkCrs = new Point(1.0, 2.0);
        withLinkCrs.setCrs(linked);
        assertThat(withLinkCrs).isNotEqualTo(withCrs);
    }

    @Test
    void bboxParticipatesInEquality() throws Exception {
        Point one = new Point(1.0, 2.0);
        one.setBbox(new double[] {0.0, 0.0, 10.0, 10.0});
        Point same = new Point(1.0, 2.0);
        same.setBbox(new double[] {0.0, 0.0, 10.0, 10.0});
        Point different = new Point(1.0, 2.0);
        different.setBbox(new double[] {0.0, 0.0, 20.0, 20.0});

        assertThat(one).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(one).isNotEqualTo(different);
        assertThat(one).isNotEqualTo(new Point(1.0, 2.0));
    }

    @Test
    void crsDefaultsToNamedTypeWithEmptyProperties() throws Exception {
        Crs crs = new Crs();

        assertThat(crs.getType()).isEqualTo(CrsType.NAME);
        assertThat(crs.getProperties()).isEmpty();
        assertThat(crs).isEqualTo(new Crs()).hasSameHashCodeAs(new Crs());
    }

    @Test
    void crsPropertiesParticipateInEquality() throws Exception {
        Crs named = new Crs();
        named.getProperties().put("name", "urn:ogc:def:crs:EPSG::4326");
        Crs sameName = new Crs();
        sameName.getProperties().put("name", "urn:ogc:def:crs:EPSG::4326");

        assertThat(named).isEqualTo(sameName).hasSameHashCodeAs(sameName);
        assertThat(named).isNotEqualTo(new Crs());
    }

    @Test
    void addIsFluentAndAppendsInOrder() throws Exception {
        MultiPoint geometry = new MultiPoint();

        Geometry<LngLatAlt> returned = geometry.add(new LngLatAlt(1.0, 2.0)).add(new LngLatAlt(3.0, 4.0));

        assertThat(returned).isSameAs(geometry);
        assertThat(geometry.getCoordinates())
                .containsExactly(new LngLatAlt(1.0, 2.0), new LngLatAlt(3.0, 4.0));
    }

    @Test
    void varargsConstructorPreservesOrder() throws Exception {
        LineString geometry = new LineString(new LngLatAlt(3.0, 4.0), new LngLatAlt(1.0, 2.0), new LngLatAlt(5.0, 6.0));

        assertThat(geometry.getCoordinates())
                .containsExactly(new LngLatAlt(3.0, 4.0), new LngLatAlt(1.0, 2.0), new LngLatAlt(5.0, 6.0));
    }

    @Test
    void emptyGeometryHasEmptyCoordinateList() throws Exception {
        assertThat(new MultiPoint().getCoordinates()).isEmpty();
        assertThat(new LineString().getCoordinates()).isEmpty();
        assertThat(new Point().getCoordinates()).isNull();
    }

    @Test
    void bboxAndCrsSurviveSerialization() throws Exception {
        LineString original = new LineString(new LngLatAlt(1.0, 2.0), new LngLatAlt(3.0, 4.0));
        original.setBbox(new double[] {1.0, 2.0, 3.0, 4.0});
        Crs crs = new Crs();
        crs.getProperties().put("name", "urn:ogc:def:crs:OGC:1.3:CRS84");
        original.setCrs(crs);

        String json = mapper.writeValueAsString(original);
        GeoJsonObject restored = mapper.readValue(json, GeoJsonObject.class);

        assertThat(restored).isExactlyInstanceOf(LineString.class).isEqualTo(original);
        assertThat(restored.getBbox()).containsExactly(1.0, 2.0, 3.0, 4.0);
        assertThat(restored.getCrs()).isNotNull();
        assertThat(restored.getCrs().getType()).isEqualTo(CrsType.NAME);
        assertThat(restored.getCrs().getProperties()).containsEntry("name", "urn:ogc:def:crs:OGC:1.3:CRS84");
    }

    @Test
    void coordinatesUseTheGeoJsonArrayFormNotABeanObject() throws Exception {
        // Regression: a coordinate must be written/read as [longitude, latitude(, altitude, extra…)]. Serialized
        // as a bean ({"longitude":…}) nothing can parse a real OSRM geometry, which is what broke getRoute.
        assertThat(mapper.writeValueAsString(new Point(1.5, 2.5)))
                .isEqualTo("{\"type\":\"Point\",\"coordinates\":[1.5,2.5]}");
        assertThat(mapper.writeValueAsString(new Point(1.5, 2.5, 3.5)))
                .contains("\"coordinates\":[1.5,2.5,3.5]");
        assertThat(mapper.writeValueAsString(new LineString(new LngLatAlt(1.0, 2.0), new LngLatAlt(3.0, 4.0))))
                .contains("\"coordinates\":[[1.0,2.0],[3.0,4.0]]");

        // Altitude plus additional elements append to the same array, in order.
        assertThat(mapper.writeValueAsString(new Point(1.0, 2.0, 3.0, 4.0, 5.0)))
                .contains("\"coordinates\":[1.0,2.0,3.0,4.0,5.0]");
        assertThat(mapper.readValue("{\"type\":\"Point\",\"coordinates\":[1.0,2.0,3.0,4.0,5.0]}", Point.class)
                .getCoordinates()).isEqualTo(new LngLatAlt(1.0, 2.0, 3.0, 4.0, 5.0));
    }

    @Test
    void coordinateArrayShorterThanTwoElementsIsRejected() throws Exception {
        assertThatThrownBy(() -> mapper.readValue("{\"type\":\"Point\",\"coordinates\":[1.0]}", Point.class))
                .isInstanceOf(JsonMappingException.class)
                .hasMessageContaining("longitude");
    }

    @Test
    void legacyBeanFormIsStillAccepted() throws Exception {
        // Payloads persisted before the array form became the output format must keep deserializing.
        Point restored = mapper.readValue(
                "{\"type\":\"Point\",\"coordinates\":{\"longitude\":1.5,\"latitude\":2.5,\"altitude\":\"NaN\"}}",
                Point.class);

        assertThat(restored.getCoordinates()).isEqualTo(new LngLatAlt(1.5, 2.5));
        assertThat(restored.getCoordinates().hasAltitude()).isFalse();
    }

    @Test
    void crsTypesUseTheLowercaseNamesOfTheGeoJsonSpecification() throws Exception {
        // GeoJSON (2008) writes "type":"name" / "type":"link"; the enum constants used to be written in upper case and
        // a standard lowercase payload was rejected.
        assertThat(mapper.writeValueAsString(new Crs())).contains("\"type\":\"name\"");

        Point named = mapper.readValue("{\"type\":\"Point\",\"coordinates\":[1,2],"
                + "\"crs\":{\"type\":\"name\",\"properties\":{\"name\":\"EPSG:4326\"}}}", Point.class);
        Crs linked = mapper.readValue("{\"type\":\"link\",\"properties\":{}}", Crs.class);
        Crs legacy = mapper.readValue("{\"type\":\"NAME\",\"properties\":{}}", Crs.class);

        assertThat(named.getCrs().getType()).isEqualTo(CrsType.NAME);
        assertThat(named.getCrs().getProperties()).containsEntry("name", "EPSG:4326");
        assertThat(linked.getType()).isEqualTo(CrsType.LINK);
        assertThat(legacy.getType()).as("payloads written by earlier versions").isEqualTo(CrsType.NAME);
    }

    @Test
    void aLegacyCoordinateWithNullAdditionalElementsIsAccepted() throws Exception {
        LngLatAlt restored = mapper.readValue(
                "{\"longitude\":1,\"latitude\":2,\"altitude\":3,\"additionalElements\":null}", LngLatAlt.class);

        assertThat(restored).isEqualTo(new LngLatAlt(1, 2, 3));
    }

    @Test
    void aLegacyCoordinateWithANullAltitudeHasNoAltitude() throws Exception {
        // A bean whose altitude was never set may have been written as null; reading it failed with
        // InputCoercionException.
        LngLatAlt restored = mapper.readValue("{\"longitude\":1,\"latitude\":2,\"altitude\":null}", LngLatAlt.class);

        assertThat(restored).isEqualTo(new LngLatAlt(1, 2));
        assertThat(restored.hasAltitude()).isFalse();
    }

    @Test
    void absentCrsAndBboxAreOmittedFromJson() throws Exception {
        String json = mapper.writeValueAsString(new Point(1.0, 2.0));

        assertThat(json).doesNotContain("crs").doesNotContain("bbox");
    }

}
