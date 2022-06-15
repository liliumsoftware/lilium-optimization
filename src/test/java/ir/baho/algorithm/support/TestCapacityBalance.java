package ir.baho.algorithm.support;

import ir.baho.algorithm.assignment.TrackableAssignment;
import ir.baho.algorithm.balance.AbstractCapacityBalanceAlgorithm;
import ir.baho.algorithm.calculation.PathCostCalculation;
import ir.baho.algorithm.insertion.AbstractInsertionAlgorithm;

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
