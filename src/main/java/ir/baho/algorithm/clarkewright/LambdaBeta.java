package ir.baho.algorithm.clarkewright;

/**
 * One point in the Clarke-Wright parameter sweep. {@code lambda} is the classic savings shape parameter
 * (route-shape weight); {@code beta} biases the symmetric variant toward the better of the two merge
 * directions ({@code beta <= 0} disables it). The sweep evaluates a grid of these and keeps the best plan set.
 */
public record LambdaBeta(double lambda, double beta) {

    /**
     * A λ-only point ({@code beta = 0}, i.e. the plain saving).
     */
    public LambdaBeta(double lambda) {
        this(lambda, 0);
    }

}
