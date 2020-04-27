package ir.baho.algorithm.clarkewright;

/**
 * One point in the Clarke-Wright parameter sweep. {@code lambda} is the classic savings shape parameter
 * (route-shape weight); {@code beta} biases the symmetric variant toward the better of the two merge
 * directions ({@code beta <= 0} disables it). The sweep evaluates a grid of these and keeps the best plan set.
 */
public final class LambdaBeta {
    private final double lambda;
    private final double beta;

    /**
     * Creates a {@code LambdaBeta} from its components.
     */
    public LambdaBeta(double lambda, double beta) {
        this.lambda = lambda;
        this.beta = beta;
    }

    /**
     * Returns the {@code lambda} component.
     */
    public double lambda() {
        return lambda;
    }

    /**
     * Returns the {@code beta} component.
     */
    public double beta() {
        return beta;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LambdaBeta)) {
            return false;
        }
        LambdaBeta other = (LambdaBeta) o;
        return Double.compare(lambda, other.lambda) == 0
                && Double.compare(beta, other.beta) == 0;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(lambda, beta);
    }

    @Override
    public String toString() {
        return "LambdaBeta["
                + "lambda=" + lambda
                + ", beta=" + beta
                + "]";
    }

    /**
     * A λ-only point ({@code beta = 0}, i.e. the plain saving).
     */
    public LambdaBeta(double lambda) {
        this(lambda, 0);
    }
}
