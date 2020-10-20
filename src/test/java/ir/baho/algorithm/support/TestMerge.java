package ir.baho.algorithm.support;

import ir.baho.algorithm.assignment.TrackableAssignment;
import ir.baho.algorithm.calculation.PathCostCalculation;
import ir.baho.algorithm.localsearch.LocalSearchAlgorithm;
import ir.baho.algorithm.merge.AbstractMergeAlgorithm;

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
