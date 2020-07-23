package ir.baho.algorithm.support;

import ir.baho.algorithm.calculation.PathCostCalculation;
import ir.baho.algorithm.feasibility.FeasibilityChecker;
import ir.baho.algorithm.insertion.AbstractInsertionAlgorithm;

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
