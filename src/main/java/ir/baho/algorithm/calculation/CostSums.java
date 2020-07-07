package ir.baho.algorithm.calculation;

/**
 * Overflow-checked cost sums. The error message is only built when a sum actually overflows, so the check costs one
 * comparison per addition on the hot path.
 */
final class CostSums {

    private CostSums() {
    }

    /**
     * {@code sum + cost}, checked.
     *
     * @param what    the kind of sum, for the error message (for example {@code "plan"})
     * @param subject the code of what is being summed, for the error message
     * @throws ArithmeticException if the sum does not fit a {@code long}
     */
    static long add(long sum, long cost, String what, String subject) {
        long result = sum + cost;
        if (((sum ^ result) & (cost ^ result)) < 0) {
            throw new ArithmeticException("Cost of " + what + " " + subject + " overflows long (" + sum + " + " + cost
                    + ")");
        }
        return result;
    }

}
