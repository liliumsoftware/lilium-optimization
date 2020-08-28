package ir.baho.algorithm.support;

import ir.baho.algorithm.assignment.TrackableAssignment;
import ir.baho.algorithm.calculation.PathCostCalculation;
import ir.baho.algorithm.insertion.AbstractInsertionAlgorithm;
import ir.baho.algorithm.localsearch.LocalSearchAlgorithm;
import ir.baho.algorithm.optimization.AbstractRelocateAlgorithm;
import ir.baho.algorithm.optimization.ExchangeRelocateType;

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
