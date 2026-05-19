package com.github.liliumsoftware.optimization.support;

import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.insertion.AbstractInsertionAlgorithm;
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;
import com.github.liliumsoftware.optimization.optimization.AbstractRelocateAlgorithm;
import com.github.liliumsoftware.optimization.optimization.ExchangeRelocateType;

/**
 * Concrete inter-plan relocate (one-way segment transfer).
 */
public final class TestRelocate extends AbstractRelocateAlgorithm<TestPlan, TestNode> {

    public TestRelocate(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
            TrackableAssignment<TestPlan, ?> trackableAssignment,
            LocalSearchAlgorithm<TestPlan> localSearchAlgorithm,
            AbstractInsertionAlgorithm<TestPlan, TestNode, ?> insertionAlgorithm,
            ExchangeRelocateType type, int neighbors, int chain) {
        super(costCalculation, trackableAssignment, localSearchAlgorithm, insertionAlgorithm, type, neighbors, chain);
    }

}
