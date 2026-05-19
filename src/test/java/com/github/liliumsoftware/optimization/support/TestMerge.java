package com.github.liliumsoftware.optimization.support;

import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;
import com.github.liliumsoftware.optimization.merge.AbstractMergeAlgorithm;

import java.util.List;

/**
 * Concrete plan-merge algorithm; exposes the pairwise, single-orientation entry point so tests can assert the
 * accept/reject decision for one specific candidate merge.
 */
public class TestMerge extends AbstractMergeAlgorithm<TestPlan, TestNode, TestTrackable> {

    public TestMerge(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
            TrackableAssignment<TestPlan, TestTrackable> trackableAssignment,
            LocalSearchAlgorithm<TestPlan> localSearchAlgorithm) {
        super(costCalculation, trackableAssignment, localSearchAlgorithm);
    }

    /**
     * Both orientations for one pair, including the eligibility guards ({@code apply}, not {@code merge}, is
     * where a plan is refused a merge with itself).
     */
    public boolean applyPair(List<TestPlan> plans, TestPlan firstPlan, TestPlan secondPlan) {
        return apply(plans, firstPlan, secondPlan);
    }

}
