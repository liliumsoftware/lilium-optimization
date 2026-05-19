package com.github.liliumsoftware.optimization.support;

import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.insertion.AbstractInsertionAlgorithm;
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;
import com.github.liliumsoftware.optimization.optimization.AbstractDestroyRepairAlgorithm;

import java.util.List;
import java.util.Map;

/**
 * Concrete large-neighbourhood destroy-and-repair; also exposes the node ranking so the tests can check which
 * stops the heuristic considers worst-placed without going through a full pass.
 */
public final class TestDestroyRepair extends AbstractDestroyRepairAlgorithm<TestPlan, TestNode> {

    public TestDestroyRepair(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
            TrackableAssignment<TestPlan, ?> trackableAssignment,
            LocalSearchAlgorithm<TestPlan> localSearchAlgorithm,
            AbstractInsertionAlgorithm<TestPlan, TestNode, ?> insertionAlgorithm,
            int destroyRepairChain, int destroyRepairThreshold) {
        super(costCalculation, trackableAssignment, localSearchAlgorithm, insertionAlgorithm,
                destroyRepairChain, destroyRepairThreshold);
    }

    public List<Map.Entry<TestNode, Integer>> highestRankNodes(List<TestPlan> plans) {
        return getHighestRankNodes(plans);
    }

    public boolean supportsSegment(TestPlan plan, int start, int end) {
        return supports(plan, start, end);
    }

}
