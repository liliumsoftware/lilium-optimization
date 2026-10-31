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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the value contract of the GeoJSON types beyond what a JSON round trip exercises: {@code hashCode} agrees with
 * {@code equals} for every geometry (including {@code null} coordinates and a {@link Crs} with {@code null} parts),
 * {@code equals} is reflexive and {@code null}-safe, the {@link LngLatAlt}-based constructors keep the given value,
 * and {@code toString} names the concrete type and its content.
 */
class GeoJsonValueContractTest {

    @Test
    void equalGeometriesHaveEqualHashCodes() {
        MultiPoint multiPoint = new MultiPoint(new LngLatAlt(1, 2), new LngLatAlt(3, 4));
        LineString lineString = new LineString(new LngLatAlt(1, 2), new LngLatAlt(3, 4));

        assertThat(multiPoint).hasSameHashCodeAs(new MultiPoint(new LngLatAlt(1, 2), new LngLatAlt(3, 4)));
        assertThat(lineString).hasSameHashCodeAs(new LineString(new LngLatAlt(1, 2), new LngLatAlt(3, 4)));
        assertThat(multiPoint.hashCode())
                .isNotEqualTo(new MultiPoint(new LngLatAlt(3, 4), new LngLatAlt(1, 2)).hashCode());
    }

    @Test
    void nullCoordinatesAreAValidStateForEqualsAndHashCode() {
        MultiPoint withoutCoordinates = new MultiPoint();
        withoutCoordinates.setCoordinates(null);
        MultiPoint alsoWithout = new MultiPoint();
        alsoWithout.setCoordinates(null);

        assertThat(withoutCoordinates).isEqualTo(alsoWithout).hasSameHashCodeAs(alsoWithout);
        assertThat(withoutCoordinates).isNotEqualTo(new MultiPoint());
        assertThat(new MultiPoint()).isNotEqualTo(withoutCoordinates);

        assertThat(new Point()).isEqualTo(new Point()).hasSameHashCodeAs(new Point());
        assertThat(new Point()).isNotEqualTo(new Point(1, 2));
        assertThat(new Point(1, 2)).isNotEqualTo(new Point());
    }

    @Test
    // reflexivity, null-safety and type-safety are under test
    @SuppressWarnings({"EqualsWithItself", "ConstantValue", "EqualsBetweenInconvertibleTypes"})
    void equalsIsReflexiveAndNullSafe() {
        Point point = new Point(1, 2);
        LineString line = new LineString(new LngLatAlt(1, 2));
        Crs crs = new Crs();

        assertThat(point.equals(point)).isTrue();
        assertThat(line.equals(line)).isTrue();
        assertThat(crs.equals(crs)).isTrue();
        assertThat(point.equals(null)).isFalse();
        assertThat(line.equals(null)).isFalse();
        assertThat(crs.equals(null)).isFalse();
        assertThat(crs.equals("name")).isFalse();
        GeoJsonObject base = new GeoJsonObject();
        assertThat(base.equals(base)).isTrue();
        assertThat(base.equals(null)).isFalse();
        assertThat(base.equals(point)).as("a subclass is another class").isFalse();
        assertThat(new GeoJsonObject()).isEqualTo(new GeoJsonObject()).hasSameHashCodeAs(new GeoJsonObject());
    }

    @Test
    void aGeometryWithACrsOrBboxDiffersFromOneWithout() {
        MultiPoint plain = new MultiPoint(new LngLatAlt(1, 2));
        MultiPoint boxed = new MultiPoint(new LngLatAlt(1, 2));
        boxed.setBbox(new double[] {0, 0, 1, 1});
        MultiPoint referenced = new MultiPoint(new LngLatAlt(1, 2));
        referenced.setCrs(new Crs());

        assertThat(plain).isNotEqualTo(boxed).isNotEqualTo(referenced);
        assertThat(boxed).isNotEqualTo(plain);
        assertThat(referenced).isNotEqualTo(plain);
        assertThat(boxed.hashCode()).isNotEqualTo(plain.hashCode());
    }

    @Test
    void aCrsWithNullPartsComparesAndHashesSafely() {
        Crs untyped = new Crs();
        untyped.setType(null);
        untyped.setProperties(null);
        Crs alsoUntyped = new Crs();
        alsoUntyped.setType(null);
        alsoUntyped.setProperties(null);

        assertThat(untyped).isEqualTo(alsoUntyped).hasSameHashCodeAs(alsoUntyped);
        assertThat(untyped).isNotEqualTo(new Crs());
        assertThat(new Crs()).isNotEqualTo(untyped);

        Crs linked = new Crs();
        linked.setType(CrsType.LINK);
        assertThat(linked).isNotEqualTo(new Crs());
        assertThat(linked.hashCode()).isNotEqualTo(new Crs().hashCode());
    }

    @Test
    void aPointBuiltFromACoordinateHoldsThatCoordinate() {
        LngLatAlt coordinate = new LngLatAlt(4.5, 51.2, 7);

        Point point = new Point(coordinate);

        assertThat(point.getCoordinates()).isSameAs(coordinate);
        assertThat(point).isEqualTo(new Point(4.5, 51.2, 7));
        assertThat(new Point(1, 2, 3, 4, 5).getCoordinates()).isEqualTo(new LngLatAlt(1, 2, 3, 4, 5));
    }

    @Test
    void theVarargsConstructorsCopyTheCoordinatesIntoAnOwnList() {
        LngLatAlt first = new LngLatAlt(1, 2);
        LngLatAlt second = new LngLatAlt(3, 4);

        MultiPoint multiPoint = new MultiPoint(first, second);
        LineString lineString = new LineString(second, first);
        Geometry<String> generic = new Geometry<>("a", "b");

        assertThat(multiPoint.getCoordinates()).containsExactly(first, second);
        assertThat(lineString.getCoordinates()).containsExactly(second, first);
        assertThat(generic.getCoordinates()).containsExactly("a", "b");
        generic.add("c");
        assertThat(generic.getCoordinates()).containsExactly("a", "b", "c");
        assertThat(new Geometry<String>().getCoordinates()).isEmpty();
    }

    @Test
    void toStringNamesTheTypeAndItsContent() {
        assertThat(new Point(1.5, 2.5).toString()).startsWith("Point{coordinates=LngLatAlt{longitude=1.5")
                .endsWith("GeoJsonObject{}");
        assertThat(new LineString(new LngLatAlt(1, 2)).toString()).startsWith("LineString{} MultiPoint{} Geometry{")
                .contains("latitude=2.0");
        assertThat(new MultiPoint().toString()).isEqualTo("MultiPoint{} Geometry{coordinates=[]} GeoJsonObject{}");
        Crs crs = new Crs();
        crs.getProperties().put("name", "EPSG:4326");
        assertThat(crs.toString()).isEqualTo("Crs{type='NAME', properties={name=EPSG:4326}}");
        assertThat(List.of(new GeoJsonObject()).toString()).isEqualTo("[GeoJsonObject{}]");
    }

}
