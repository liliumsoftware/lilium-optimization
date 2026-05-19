package com.github.liliumsoftware.optimization.calculation;

import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.domain.Planable;

import java.util.Collection;

/**
 * The cost model the algorithms optimize against. A plan's cost is, by definition, the sum of the costs
 * of its consecutive edges — this is the single invariant every move relies on when it accepts a candidate
 * only if the summed cost improves.
 *
 * <p>Implementors normally supply the edge cost {@link #calculateCost(Nodeable, Nodeable)} (see
 * {@code PathCostCalculation}); the plan-level and context-aware overloads have working defaults. The
 * {@code (plan, start, end)} overload is the hook for costs that depend on the enclosing plan (e.g. the
 * assigned resource) rather than on the edge alone.
 *
 * <p><strong>Overflow.</strong> Costs are {@code long}. Every sum the library forms (a plan's edges here, a plan set's
 * costs in {@link #totalCost}, the medoid sums of {@code PathCostCalculation}) is checked: a sum that does not fit a
 * {@code long} throws {@link ArithmeticException} instead of wrapping to a negative value that every "is it cheaper"
 * comparison would mistake for a huge saving.
 *
 * @param <P> the plan type
 * @param <N> the node type
 */
public interface CostCalculation<P extends Planable<P, N, ?>, N extends Nodeable<N>> {

    /**
     * The summed {@link Planable#getCost()} of {@code plans}: the objective the inter-plan algorithms compare.
     *
     * @throws ArithmeticException if the sum does not fit a {@code long}
     */
    static long totalCost(Collection<? extends Planable<?, ?, ?>> plans) {
        long total = 0;
        for (Planable<?, ?, ?> plan : plans) {
            total = CostSums.add(total, plan.getCost(), "plan set containing", plan.getCode());
        }
        return total;
    }

    /**
     * Cost of the directed edge {@code start -> end}.
     *
     * @throws UnsupportedOperationException unless overridden
     */
    default long calculateCost(N start, N end) {
        throw new UnsupportedOperationException("Not Implemented");
    }

    /**
     * Sum of {@link #calculateCost(Planable, Nodeable, Nodeable)} over each consecutive node pair.
     *
     * @throws ArithmeticException if the sum does not fit a {@code long}
     */
    default long calculateCost(P plan) {
        long cost = 0;
        for (int i = 0; i < plan.getCount() - 1; i++) {
            cost = CostSums.add(cost, calculateCost(plan, plan.getNodes().get(i), plan.getNodes().get(i + 1)), "plan",
                    plan.getCode());
        }
        return cost;
    }

    /**
     * Plan-aware edge cost; defaults to the plan-independent {@link #calculateCost(Nodeable, Nodeable)}.
     */
    default long calculateCost(P plan, N start, N end) {
        return calculateCost(start, end);
    }

}
