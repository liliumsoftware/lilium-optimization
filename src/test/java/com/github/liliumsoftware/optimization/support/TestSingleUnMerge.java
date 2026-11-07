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

import com.github.liliumsoftware.optimization.OptimizationType;
import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;
import com.github.liliumsoftware.optimization.merge.AbstractSingleUnMergeAlgorithm;

/**
 * Concrete single-zone un-merge: pulls one shared zone's customers onto the plan confined to that zone.
 */
public final class TestSingleUnMerge extends AbstractSingleUnMergeAlgorithm<TestPlan, TestNode> {

    public TestSingleUnMerge(TrackableAssignment<TestPlan, ?> trackableAssignment,
            LocalSearchAlgorithm<TestPlan> localSearchAlgorithm) {
        super(trackableAssignment, localSearchAlgorithm);
    }

    public TestSingleUnMerge(TrackableAssignment<TestPlan, ?> trackableAssignment,
            LocalSearchAlgorithm<TestPlan> localSearchAlgorithm, OptimizationType optimizationType) {
        super(trackableAssignment, localSearchAlgorithm, optimizationType);
    }

    public boolean supportsPair(TestPlan firstPlan, TestPlan secondPlan) {
        return supports(firstPlan, secondPlan);
    }

}
