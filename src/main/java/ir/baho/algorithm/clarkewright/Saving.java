package ir.baho.algorithm.clarkewright;

import ir.baho.algorithm.domain.Nodeable;

import java.util.Comparator;

/**
 * The Clarke-Wright saving of serving {@code origin} immediately before {@code destination} on one route
 * instead of on two separate out-and-back routes. Merges are attempted in descending {@code value} order,
 * so a higher value is a more attractive merge.
 */
public final class Saving {
    private final Nodeable<?> origin;
    private final Nodeable<?> destination;
    private final double value;

    /**
     * Creates a {@code Saving} from its components.
     */
    public Saving(Nodeable<?> origin, Nodeable<?> destination, double value) {
        this.origin = origin;
        this.destination = destination;
        this.value = value;
    }

    /**
     * Returns the {@code origin} component.
     */
    public Nodeable<?> origin() {
        return origin;
    }

    /**
     * Returns the {@code destination} component.
     */
    public Nodeable<?> destination() {
        return destination;
    }

    /**
     * Returns the {@code value} component.
     */
    public double value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Saving)) {
            return false;
        }
        Saving other = (Saving) o;
        return java.util.Objects.equals(origin, other.origin)
                && java.util.Objects.equals(destination, other.destination)
                && Double.compare(value, other.value) == 0;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(origin, destination, value);
    }

    /**
     * Orders savings best-first (descending value), breaking ties by origin code for a stable sort.
     */
    public static Comparator<Saving> reversedValueComparator() {
        return Comparator.comparingDouble(Saving::value).reversed().thenComparing(o -> o.origin().getCode());
    }

    @Override
    public String toString() {
        return "Origin: " + origin.getCode() + "\t" + "Destination: " + destination.getCode() + "\t" + "Value: "
                + value;
    }
}
