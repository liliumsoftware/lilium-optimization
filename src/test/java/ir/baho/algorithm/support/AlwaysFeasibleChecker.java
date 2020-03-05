package ir.baho.algorithm.support;

import ir.baho.algorithm.feasibility.FeasibilityChecker;
import ir.baho.algorithm.feasibility.FeasibilityResult;

/**
 * Feasibility checker that accepts every plan; isolates cost/ordering behaviour from feasibility filtering.
 */
public final class AlwaysFeasibleChecker implements FeasibilityChecker<TestPlan> {

    @Override
    public FeasibilityResult getResult(TestPlan plan) {
        return FeasibilityResult.FEASIBLE;
    }

    @Override
    public String getMessage(String key, Object... params) {
        return key;
    }

}
