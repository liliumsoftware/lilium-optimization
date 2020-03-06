package ir.baho.algorithm.support;

import ir.baho.algorithm.feasibility.FeasibilityChecker;
import ir.baho.algorithm.feasibility.FeasibilityResult;

/**
 * Feasible only while a plan holds at most {@code maxCustomers} customers; drives infeasible-rejection tests.
 */
public final class MaxCustomersFeasibilityChecker implements FeasibilityChecker<TestPlan> {

    private final int maxCustomers;

    public MaxCustomersFeasibilityChecker(int maxCustomers) {
        this.maxCustomers = maxCustomers;
    }

    @Override
    public FeasibilityResult getResult(TestPlan plan) {
        if (plan.getCustomerCount() <= maxCustomers) {
            return FeasibilityResult.FEASIBLE;
        }
        return new FeasibilityResult() {
            @Override
            public String getCause() {
                return "too.many.customers";
            }

            @Override
            public Object[] getParams() {
                return new Object[] {maxCustomers};
            }
        };
    }

    @Override
    public String getMessage(String key, Object... params) {
        return key;
    }

}
