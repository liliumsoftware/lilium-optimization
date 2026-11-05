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
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.insertion.AbstractInsertionAlgorithm;
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;
import com.github.liliumsoftware.optimization.optimization.AbstractExchangeAlgorithm;
import com.github.liliumsoftware.optimization.optimization.ExchangeRelocateType;

import java.util.List;

/**
 * Concrete inter-plan exchange (segment swap); the base class supplies all behaviour, so this only fixes the
 * type parameters and exposes the pairwise entry point for focused tests.
 */
public final class TestExchange extends AbstractExchangeAlgorithm<TestPlan, TestNode> {

    public TestExchange(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
            TrackableAssignment<TestPlan, ?> trackableAssignment,
            LocalSearchAlgorithm<TestPlan> localSearchAlgorithm,
            AbstractInsertionAlgorithm<TestPlan, TestNode, ?> insertionAlgorithm,
            ExchangeRelocateType type, int neighbors, int chain, boolean cross) {
        super(costCalculation, trackableAssignment, localSearchAlgorithm, insertionAlgorithm, type, neighbors, chain,
                cross);
    }

    /**
     * Runs the move search for one plan pair only — the unit the accept/reject logic actually operates on.
     */
    public boolean applyPair(List<TestPlan> plans, TestPlan firstPlan, TestPlan secondPlan) {
        return apply(List.of(), plans, firstPlan, secondPlan);
    }

}
