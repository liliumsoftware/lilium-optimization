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
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;
import com.github.liliumsoftware.optimization.merge.AbstractMergeAlgorithm;

import java.util.List;

/**
 * Concrete plan-merge algorithm; exposes the pairwise, single-orientation entry point so tests can assert the
 * accept/reject decision for one specific candidate merge.
 */
public class TestMerge extends AbstractMergeAlgorithm<TestPlan, TestNode, TestTrackable> {

    public TestMerge(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
            TrackableAssignment<TestPlan, TestTrackable> trackableAssignment,
            LocalSearchAlgorithm<TestPlan> localSearchAlgorithm) {
        super(costCalculation, trackableAssignment, localSearchAlgorithm);
    }

    /**
     * Both orientations for one pair, including the eligibility guards ({@code apply}, not {@code merge}, is
     * where a plan is refused a merge with itself).
     */
    public boolean applyPair(List<TestPlan> plans, TestPlan firstPlan, TestPlan secondPlan) {
        return apply(plans, firstPlan, secondPlan);
    }

}
