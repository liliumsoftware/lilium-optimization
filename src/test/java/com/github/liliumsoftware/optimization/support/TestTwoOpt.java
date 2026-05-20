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

import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.feasibility.FeasibilityChecker;
import com.github.liliumsoftware.optimization.localsearch.AbstractTwoOptAlgorithm;

import java.util.List;

/**
 * Concrete 2-opt: applies the reversal computed by {@code calculateTwoOptSwap} in place on the given copy.
 */
public final class TestTwoOpt extends AbstractTwoOptAlgorithm<TestPlan, TestNode> {

    public TestTwoOpt(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
            FeasibilityChecker<TestPlan> feasibilityChecker) {
        super(costCalculation, feasibilityChecker);
    }

    @Override
    protected TestPlan twoOptSwap(TestPlan plan, int i, int j) {
        List<TestNode> swapped = calculateTwoOptSwap(plan, i, j);
        plan.getNodes().clear();
        plan.getNodes().addAll(swapped);
        return plan;
    }

}
