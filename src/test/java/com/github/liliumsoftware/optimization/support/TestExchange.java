package com.github.liliumsoftware.optimization.support;

import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.insertion.AbstractInsertionAlgorithm;
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;
import com.github.liliumsoftware.optimization.optimization.AbstractExchangeAlgorithm;
import com.github.liliumsoftware.optimization.optimization.ExchangeRelocateType;

/**
 * Concrete inter-plan exchange (segment swap); the base class supplies all behaviour, so this only fixes the
 * type parameters and exposes the pairwise entry point for focused tests.
 */
public final class TestExchange extends AbstractExchangeAlgorithm<TestPlan, TestNode> {

    public TestExchange(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
            TrackableAssignment<TestPlan, ?> trackableAssignment,
            LocalSearchAlgorithm<TestPlan> localSearchAlgorithm,
            AbstractInsertionAlgorithm<TestPlan, TestNode, ?> insertionAlgorithm,
            ExchangeRelocateType type, int neighbors, int chain, boolean cross) {
        super(costCalculation, trackableAssignment, localSearchAlgorithm, insertionAlgorithm, type, neighbors, chain,
                cross);
    }

}
