package ir.baho.algorithm.clarkewright;

import ir.baho.algorithm.domain.Nodeable;

import java.util.Comparator;

/**
 * The Clarke-Wright saving of serving {@code origin} immediately before {@code destination} on one route
 * instead of on two separate out-and-back routes. Merges are attempted in descending {@code value} order,
 * so a higher value is a more attractive merge.
 */
public record Saving(Nodeable<?> origin, Nodeable<?> destination, double value) {

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
