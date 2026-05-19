package com.github.liliumsoftware.optimization.support;

import com.github.liliumsoftware.optimization.OptimizationType;
import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;
import com.github.liliumsoftware.optimization.merge.AbstractSingleUnMergeAlgorithm;

import java.util.List;

/**
 * Concrete single-zone un-merge: pulls one shared zone's customers onto the plan confined to that zone.
 */
public final class TestSingleUnMerge extends AbstractSingleUnMergeAlgorithm<TestPlan, TestNode> {

    public TestSingleUnMerge(TrackableAssignment<TestPlan, ?> trackableAssignment,
            LocalSearchAlgorithm<TestPlan> localSearchAlgorithm) {
        super(trackableAssignment, localSearchAlgorithm);
    }

    public TestSingleUnMerge(TrackableAssignment<TestPlan, ?> trackableAssignment,
            LocalSearchAlgorithm<TestPlan> localSearchAlgorithm, OptimizationType optimizationType) {
        super(trackableAssignment, localSearchAlgorithm, optimizationType);
    }

    public boolean unMergeOnce(List<TestPlan> plans, TestPlan firstPlan, TestPlan secondPlan) {
        return unMerge(plans, firstPlan, secondPlan);
    }

    public boolean supportsPair(TestPlan firstPlan, TestPlan secondPlan) {
        return supports(firstPlan, secondPlan);
    }

}
