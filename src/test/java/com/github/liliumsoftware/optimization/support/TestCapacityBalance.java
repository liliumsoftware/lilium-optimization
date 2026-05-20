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

import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.balance.AbstractCapacityBalanceAlgorithm;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.insertion.AbstractInsertionAlgorithm;

/**
 * Concrete capacity balancing, where a plan's "capacity" is its summed customer demand.
 */
public final class TestCapacityBalance
        extends AbstractCapacityBalanceAlgorithm<TestPlan, TestNode, TestInsertionCandidate> {

    public TestCapacityBalance(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
            TrackableAssignment<TestPlan, ?> trackableAssignment,
            AbstractInsertionAlgorithm<TestPlan, TestNode, TestInsertionCandidate> insertionAlgorithm,
            long minCapacity) {
        super(costCalculation, trackableAssignment, insertionAlgorithm, minCapacity);
    }

    @Override
    protected long getCapacity(TestPlan plan) {
        return DemandFeasibilityChecker.load(plan);
    }

}
