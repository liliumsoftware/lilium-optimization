package com.github.liliumsoftware.optimization.support;

import com.github.liliumsoftware.optimization.insertion.InsertionCandidate;

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
