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
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;
import com.github.liliumsoftware.optimization.merge.AbstractDoubleUnMergeAlgorithm;

/**
 * Concrete double-zone un-merge: two plans spanning the same two zones trade their customers so each keeps a
 * single zone.
 */
public final class TestDoubleUnMerge extends AbstractDoubleUnMergeAlgorithm<TestPlan, TestNode> {

    public TestDoubleUnMerge(TrackableAssignment<TestPlan, ?> trackableAssignment,
            LocalSearchAlgorithm<TestPlan> localSearchAlgorithm) {
        super(trackableAssignment, localSearchAlgorithm);
    }

    public boolean supportsPair(TestPlan firstPlan, TestPlan secondPlan) {
        return supports(firstPlan, secondPlan);
    }

}
