/*
 * Copyright the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.liliumsoftware.optimization.support;

import com.github.liliumsoftware.optimization.feasibility.FeasibilityChecker;
import com.github.liliumsoftware.optimization.feasibility.FeasibilityResult;

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
