package ir.baho.algorithm.support;

import ir.baho.algorithm.assignment.TrackableAssignment;
import ir.baho.algorithm.calculation.PathCostCalculation;
import ir.baho.algorithm.insertion.AbstractInsertionAlgorithm;
import ir.baho.algorithm.localsearch.LocalSearchAlgorithm;
import ir.baho.algorithm.optimization.AbstractExchangeAlgorithm;
import ir.baho.algorithm.optimization.ExchangeRelocateType;

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
