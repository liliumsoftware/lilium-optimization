package ir.baho.algorithm.domain;

/**
 * A resource that executes a plan — a vehicle, driver or similar.
 *
 * <p>A single {@code Trackable} may stand for several interchangeable units: the assignment layer expands
 * one instance whose {@link #getCount()} is {@code n} into {@code n} indexed copies (via {@link #copy()} +
 * {@link #setIndex(int)}), so each unit can be handed out independently. Identity is by {@link #getCode()};
 * {@link #copy()} must be a deep copy.
 *
 * @param <T> the concrete resource type (self type)
 */
public interface Trackable<T extends Trackable<T>> {

    /**
     * Identity of the resource; units expanded from one resource share the code and differ by index.
     */
    String getCode();

    /**
     * A deep, independent copy; also used to materialize the {@link #getCount()} interchangeable units.
     */
    T copy();

    /**
     * Index of this unit within its {@link #getCount()}-sized group. The assignment layer numbers the
     * expanded units {@code 0 .. getCount() - 1}; a resource that was never expanded reports {@code 0}, so it is the
     * same unit as the first (for a single-unit resource the only) copy in the assignment pool. Implementations that
     * include the index in {@code equals} rely on this: a plan the caller built with its own, never-expanded instance
     * must still be recognised as holding that pool unit.
     */
    default int getIndex() {
        return 0;
    }

    /**
     * Set by the assignment layer when expanding a multi-unit resource; a no-op for singletons.
     */
    default void setIndex(int index) {
    }

    /**
     * How many interchangeable units this resource represents; {@code 1} by default.
     */
    default int getCount() {
        return 1;
    }

    /**
     * Whether the resource returns to its origin (closed tour) rather than ending at the destination.
     */
    default boolean isReturns() {
        return false;
    }

    /**
     * Whether the resource may be re-dispatched for a second run after returning; requires {@link #isReturns()}.
     */
    default boolean isSupportSecondDispatch() {
        return isReturns();
    }

}
