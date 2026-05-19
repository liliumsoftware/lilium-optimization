package com.github.liliumsoftware.optimization.support;

import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.feasibility.FeasibilityChecker;
import com.github.liliumsoftware.optimization.localsearch.AbstractTwoOptAlgorithm;

import java.util.List;

/**
 * Concrete 2-opt: applies the reversal computed by {@code calculateTwoOptSwap} in place on the given copy.
 */
public final class TestTwoOpt extends AbstractTwoOptAlgorithm<TestPlan, TestNode> {

    public TestTwoOpt(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
            FeasibilityChecker<TestPlan> feasibilityChecker) {
        super(costCalculation, feasibilityChecker);
    }

    @Override
    protected TestPlan twoOptSwap(TestPlan plan, int i, int j) {
        List<TestNode> swapped = calculateTwoOptSwap(plan, i, j);
        plan.getNodes().clear();
        plan.getNodes().addAll(swapped);
        return plan;
    }

}
