package ir.baho.algorithm.routing;

import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

/**
 * GeoJSON {@code Point}: a single {@link LngLatAlt} coordinate. Used as the routing request coordinate type.
 */
@Setter
@Getter
public class Point extends GeoJsonObject {

    private LngLatAlt coordinates;

    /**
     * A point without coordinates (for deserialization).
     */
    public Point() {
    }

    /**
     * A point at the given coordinate.
     */
    public Point(LngLatAlt coordinates) {
        this.coordinates = coordinates;
    }

    /**
     * A 2-D point.
     */
    public Point(double longitude, double latitude) {
        coordinates = new LngLatAlt(longitude, latitude);
    }

    /**
     * A 3-D point.
     */
    public Point(double longitude, double latitude, double altitude) {
        coordinates = new LngLatAlt(longitude, latitude, altitude);
    }

    /**
     * A 3-D point with additional elements; see {@link LngLatAlt#LngLatAlt(double, double, double, double...)}.
     */
    public Point(double longitude, double latitude, double altitude, double... additionalElements) {
        coordinates = new LngLatAlt(longitude, latitude, altitude, additionalElements);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Point)) {
            return false;
        }
        Point point = (Point) o;
        if (!super.equals(o)) {
            return false;
        }
        return Objects.equals(coordinates, point.coordinates);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (coordinates != null ? coordinates.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "Point{" + "coordinates=" + coordinates + "} " + super.toString();
    }

}
