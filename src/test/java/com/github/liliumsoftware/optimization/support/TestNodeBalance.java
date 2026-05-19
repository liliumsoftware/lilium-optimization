package com.github.liliumsoftware.optimization.support;

import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.balance.AbstractNodeBalanceAlgorithm;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.insertion.AbstractInsertionAlgorithm;

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
