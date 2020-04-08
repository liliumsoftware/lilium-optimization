package ir.baho.algorithm.support;

import ir.baho.algorithm.localsearch.LocalSearchAlgorithm;
import lombok.Getter;

/**
 * Identity local search that records whether the enclosing algorithm propagated {@code cancel()} to it.
 *
 * <p>Propagation is the only thing that makes cancellation reach a composite algorithm's nested algorithms, and
 * it is invisible from the outside — {@code BaseAlgorithm}'s flag is private — so a spy is the only way to
 * assert it without reflection.
 */
public final class CancelSpyLocalSearch extends LocalSearchAlgorithm<TestPlan> {

    @Getter
    private boolean cancelled;

    @Getter
    private boolean interrupted;

    @Override
    public TestPlan apply(TestPlan plan) {
        return plan;
    }

    @Override
    public void cancel() {
        cancelled = true;
        super.cancel();
    }

    @Override
    public void interrupt() {
        interrupted = true;
        super.interrupt();
    }

}
