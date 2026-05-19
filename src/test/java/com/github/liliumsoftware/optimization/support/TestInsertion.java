package com.github.liliumsoftware.optimization.support;

import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.feasibility.FeasibilityChecker;
import com.github.liliumsoftware.optimization.insertion.AbstractInsertionAlgorithm;

/**
 * Concrete cheapest/regret insertion over {@link TestPlan}s.
 */
public class TestInsertion extends AbstractInsertionAlgorithm<TestPlan, TestNode, TestInsertionCandidate> {

    public TestInsertion(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
            FeasibilityChecker<TestPlan> feasibilityChecker, boolean regret) {
        super(costCalculation, feasibilityChecker, regret);
    }

    @Override
    protected TestInsertionCandidate createEmptyInsertionCandidate(TestPlan originalPlan, TestNode node) {
        return new TestInsertionCandidate(originalPlan, node);
    }

    @Override
    protected TestInsertionCandidate createInsertionCandidate(TestPlan originalPlan, TestPlan candidatePlan,
            TestNode node) {
        return new TestInsertionCandidate(originalPlan, candidatePlan, node);
    }

}
