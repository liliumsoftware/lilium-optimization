package ir.baho.algorithm.support;

import ir.baho.algorithm.feasibility.FeasibilityChecker;
import ir.baho.algorithm.feasibility.FeasibilityResult;

/**
 * Capacity feasibility in demand units: a plan is feasible while its customers' summed demand stays within
 * {@code maxDemand}. Models the classic CVRP capacity constraint, as opposed to the plain customer-count cap
 * of {@link MaxCustomersFeasibilityChecker}.
 */
public final class DemandFeasibilityChecker implements FeasibilityChecker<TestPlan> {

    private final long maxDemand;

    public DemandFeasibilityChecker(long maxDemand) {
        this.maxDemand = maxDemand;
    }

    public static long load(TestPlan plan) {
        return plan.getCustomers().stream().mapToLong(TestNode::getDemand).sum();
    }

    @Override
    public FeasibilityResult getResult(TestPlan plan) {
        long load = load(plan);
        if (load <= maxDemand) {
            return FeasibilityResult.FEASIBLE;
        }
        return new FeasibilityResult() {
            @Override
            public String getCause() {
                return "capacity.exceeded";
            }

            @Override
            public Object[] getParams() {
                return new Object[] {load, maxDemand};
            }
        };
    }

    @Override
    public String getMessage(String key, Object... params) {
        return key;
    }

}
