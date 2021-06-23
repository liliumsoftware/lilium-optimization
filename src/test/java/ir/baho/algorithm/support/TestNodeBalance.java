package ir.baho.algorithm.support;

import ir.baho.algorithm.assignment.TrackableAssignment;
import ir.baho.algorithm.balance.AbstractNodeBalanceAlgorithm;
import ir.baho.algorithm.calculation.PathCostCalculation;
import ir.baho.algorithm.insertion.AbstractInsertionAlgorithm;

/**
 * Concrete customer-count balancing.
 */
public final class TestNodeBalance extends AbstractNodeBalanceAlgorithm<TestPlan, TestNode, TestInsertionCandidate> {

    public TestNodeBalance(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
            TrackableAssignment<TestPlan, ?> trackableAssignment,
            AbstractInsertionAlgorithm<TestPlan, TestNode, TestInsertionCandidate> insertionAlgorithm,
            int threshold) {
        super(costCalculation, trackableAssignment, insertionAlgorithm, threshold);
    }

}
