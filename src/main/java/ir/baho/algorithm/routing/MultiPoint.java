package ir.baho.algorithm.routing;

/**
 * GeoJSON {@code MultiPoint}: an unordered-by-contract collection of coordinates.
 */
public class MultiPoint extends Geometry<LngLatAlt> {

    /**
     * An empty collection of points.
     */
    public MultiPoint() {
    }

    /**
     * A collection holding {@code points} in the given order.
     */
    public MultiPoint(LngLatAlt... points) {
        super(points);
    }

    @Override
    public String toString() {
        return "MultiPoint{} " + super.toString();
    }

}
