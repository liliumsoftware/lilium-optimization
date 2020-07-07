package ir.baho.algorithm.clarkewright;

import ir.baho.algorithm.domain.Planable;

import java.util.List;

/**
 * A candidate plan set together with the {@link LambdaBeta} parameters that produced it during the sweep.
 */
public final class PlanList<P extends Planable<P, ?, ?>> {
    private final List<P> plans;
    private final LambdaBeta lambdaBeta;

    /**
     * Creates a {@code PlanList} from its components.
     */
    public PlanList(List<P> plans, LambdaBeta lambdaBeta) {
        this.plans = plans;
        this.lambdaBeta = lambdaBeta;
    }

    /**
     * Returns the {@code plans} component.
     */
    public List<P> plans() {
        return plans;
    }

    /**
     * Returns the {@code lambdaBeta} component.
     */
    public LambdaBeta lambdaBeta() {
        return lambdaBeta;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlanList)) {
            return false;
        }
        PlanList<?> other = (PlanList<?>) o;
        return java.util.Objects.equals(plans, other.plans)
                && java.util.Objects.equals(lambdaBeta, other.lambdaBeta);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(plans, lambdaBeta);
    }

    @Override
    public String toString() {
        return "PlanList["
                + "plans=" + plans
                + ", lambdaBeta=" + lambdaBeta
                + "]";
    }
}
