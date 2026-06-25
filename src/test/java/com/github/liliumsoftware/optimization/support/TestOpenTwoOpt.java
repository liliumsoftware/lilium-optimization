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
import com.github.liliumsoftware.optimization.localsearch.AbstractOpenTwoOptAlgorithm;

import java.util.List;

/**
 * Concrete open-path 2-opt: rebuilds the plan around the candidate node ordering, preserving the plan's
 * identity, zone and assigned resource (a move must never silently unassign a plan).
 */
public final class TestOpenTwoOpt extends AbstractOpenTwoOptAlgorithm<TestPlan, TestNode> {

    public TestOpenTwoOpt(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
            FeasibilityChecker<TestPlan> feasibilityChecker) {
        super(costCalculation, feasibilityChecker);
    }

    @Override
    protected TestPlan getPlan(TestPlan plan, List<TestNode> nodes) {
        return plan.withNodes(nodes);
    }

}
