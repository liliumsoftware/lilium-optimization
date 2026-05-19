package com.github.liliumsoftware.optimization.support;

import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.balance.AbstractCapacityBalanceAlgorithm;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.insertion.AbstractInsertionAlgorithm;

/**
 * Concrete capacity balancing, where a plan's "capacity" is its summed customer demand.
 */
public final class TestCapacityBalance
        extends AbstractCapacityBalanceAlgorithm<TestPlan, TestNode, TestInsertionCandidate> {

    public TestCapacityBalance(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
            TrackableAssignment<TestPlan, ?> trackableAssignment,
            AbstractInsertionAlgorithm<TestPlan, TestNode, TestInsertionCandidate> insertionAlgorithm,
            long minCapacity) {
        super(costCalculation, trackableAssignment, insertionAlgorithm, minCapacity);
    }

    @Override
    protected long getCapacity(TestPlan plan) {
        return DemandFeasibilityChecker.load(plan);
    }

}
