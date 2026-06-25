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
import com.github.liliumsoftware.optimization.insertion.AbstractInsertionAlgorithm;

/**
 * Concrete cheapest/regret insertion over {@link TestPlan}s.
 */
public class TestInsertion extends AbstractInsertionAlgorithm<TestPlan, TestNode, TestInsertionCandidate> {

    public TestInsertion(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
            FeasibilityChecker<TestPlan> feasibilityChecker, boolean regret) {
        super(costCalculation, feasibilityChecker, regret);
    }

    @Override
    protected TestInsertionCandidate createEmptyInsertionCandidate(TestPlan originalPlan, TestNode node) {
        return new TestInsertionCandidate(originalPlan, node);
    }

    @Override
    protected TestInsertionCandidate createInsertionCandidate(TestPlan originalPlan, TestPlan candidatePlan,
            TestNode node) {
        return new TestInsertionCandidate(originalPlan, candidatePlan, node);
    }

}
