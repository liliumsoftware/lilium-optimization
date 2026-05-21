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

import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;

/**
 * Identity local search that keeps {@code getCost()} truthful: it never reorders a plan, it only recomputes
 * the plan's cost from the cost matrix.
 *
 * <p>This matters because the construction and inter-plan algorithms funnel every touched plan through the
 * local search and then compare plans by {@code getCost()}. {@link TestPlan} computes that cost live from its
 * nodes, so this double changes nothing a caller can observe compared with {@link NoOpLocalSearch}; it only
 * routes the call through the library's {@code calculateCost} so that path runs too. A real 2-opt would
 * reorder nodes and blur which component under test caused a change.
 */
public final class RecostingLocalSearch extends LocalSearchAlgorithm<TestPlan> {

    private final MatrixCostCalculation costCalculation;

    public RecostingLocalSearch(MatrixCostCalculation costCalculation) {
        this.costCalculation = costCalculation;
    }

    @Override
    public TestPlan apply(TestPlan plan) {
        costCalculation.calculateCost(plan);
        return plan;
    }

}
