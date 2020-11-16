package ir.baho.algorithm.routing;

/**
 * GeoJSON {@code LineString}: an ordered path of coordinates — the shape a route geometry deserializes to.
 */
public class LineString extends MultiPoint {

    /**
     * An empty path.
     */
    public LineString() {
    }

    /**
     * A path through {@code points} in order.
     */
    public LineString(LngLatAlt... points) {
        super(points);
    }

    @Override
    public String toString() {
        return "LineString{} " + super.toString();
    }

}
