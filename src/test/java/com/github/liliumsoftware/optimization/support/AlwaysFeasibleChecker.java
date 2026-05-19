package com.github.liliumsoftware.optimization.support;

import com.github.liliumsoftware.optimization.feasibility.FeasibilityChecker;
import com.github.liliumsoftware.optimization.feasibility.FeasibilityResult;

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
