package ir.baho.algorithm.support;

import ir.baho.algorithm.calculation.PathCostCalculation;
import ir.baho.algorithm.feasibility.FeasibilityChecker;
import ir.baho.algorithm.localsearch.AbstractOpenTwoOptAlgorithm;

import java.util.List;

/**
 * Concrete open-path 2-opt: rebuilds the plan around the candidate node ordering, preserving the plan's
 * identity, zone and assigned resource (a move must never silently unassign a plan).
 */
public final class TestOpenTwoOpt extends AbstractOpenTwoOptAlgorithm<TestPlan, TestNode> {

    public TestOpenTwoOpt(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
            FeasibilityChecker<TestPlan> feasibilityChecker) {
        super(costCalculation, feasibilityChecker);
    }

    @Override
    protected TestPlan getPlan(TestPlan plan, List<TestNode> nodes) {
        return plan.withNodes(nodes);
    }

}
