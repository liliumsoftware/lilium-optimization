package ir.baho.algorithm.support;

import ir.baho.algorithm.localsearch.LocalSearchAlgorithm;

/**
 * Identity local search that keeps {@code getCost()} truthful: it never reorders a plan, it only recomputes
 * the plan's cost from the cost matrix.
 *
 * <p>This matters because the construction and inter-plan algorithms funnel every touched plan through the
 * local search and then compare plans by {@code getCost()}. {@link TestPlan} computes that cost live from its
 * nodes, so this double changes nothing a caller can observe compared with {@link NoOpLocalSearch}; it only
 * routes the call through the library's {@code calculateCost} so that path runs too. A real 2-opt would
 * reorder nodes and blur which component under test caused a change.
 */
public final class RecostingLocalSearch extends LocalSearchAlgorithm<TestPlan> {

    private final MatrixCostCalculation costCalculation;

    public RecostingLocalSearch(MatrixCostCalculation costCalculation) {
        this.costCalculation = costCalculation;
    }

    @Override
    public TestPlan apply(TestPlan plan) {
        costCalculation.calculateCost(plan);
        return plan;
    }

}
