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
