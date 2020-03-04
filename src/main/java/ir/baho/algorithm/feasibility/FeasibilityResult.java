package ir.baho.algorithm.feasibility;

/**
 * Outcome of checking a single plan's feasibility. An infeasible result carries a message {@code cause} key
 * plus {@code params} for localization. The shared {@link #FEASIBLE} singleton avoids allocating on the hot
 * path, where the common case is "feasible".
 */
public interface FeasibilityResult {

    /**
     * Shared feasible outcome; reused to avoid per-check allocation in the search loops.
     */
    FeasibilityResult FEASIBLE = new FeasibilityResult() {
        @Override
        public boolean isFeasible() {
            return true;
        }

        @Override
        public String getCause() {
            return null;
        }

        @Override
        public Object[] getParams() {
            return new Object[0];
        }
    };

    /**
     * Whether the plan passed; {@code false} by default so a custom result is infeasible unless it says otherwise.
     */
    default boolean isFeasible() {
        return false;
    }

    /**
     * Message key of the violated constraint, or {@code null} when feasible.
     */
    String getCause();

    /**
     * Message parameters for {@link #getCause()}; never {@code null}.
     */
    Object[] getParams();

}
