package ir.baho.algorithm.support;

import ir.baho.algorithm.insertion.InsertionCandidate;

/**
 * Concrete {@link InsertionCandidate} carrying no extra data beyond the base delta-cost bookkeeping.
 */
public final class TestInsertionCandidate extends InsertionCandidate<TestPlan, TestNode> {

    public TestInsertionCandidate(TestPlan originalPlan, TestPlan candidatePlan, TestNode newNode) {
        super(originalPlan, candidatePlan, newNode);
    }

    public TestInsertionCandidate(TestPlan originalPlan, TestNode newNode) {
        super(originalPlan, newNode);
    }

}
