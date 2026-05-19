package com.github.liliumsoftware.optimization.support;

import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;
import com.github.liliumsoftware.optimization.merge.AbstractDoubleUnMergeAlgorithm;

/**
 * Concrete double-zone un-merge: two plans spanning the same two zones trade their customers so each keeps a
 * single zone.
 */
public final class TestDoubleUnMerge extends AbstractDoubleUnMergeAlgorithm<TestPlan, TestNode> {

    public TestDoubleUnMerge(TrackableAssignment<TestPlan, ?> trackableAssignment,
            LocalSearchAlgorithm<TestPlan> localSearchAlgorithm) {
        super(trackableAssignment, localSearchAlgorithm);
    }

    public boolean supportsPair(TestPlan firstPlan, TestPlan secondPlan) {
        return supports(firstPlan, secondPlan);
    }

}
