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

import com.github.liliumsoftware.optimization.assignment.InfiniteTrackableAssignment;
import com.github.liliumsoftware.optimization.domain.TrackableCategory;

import java.util.Comparator;
import java.util.List;

/**
 * Concrete {@link InfiniteTrackableAssignment}: same capacity rule as {@link TestAssignment}, but drawing from
 * an unlimited fleet so the same unit may back several plans at once.
 */
public final class TestInfiniteAssignment extends InfiniteTrackableAssignment<TestPlan, TestTrackable> {

    public TestInfiniteAssignment(List<TestTrackable> trackables) {
        super(trackables);
    }

    @Override
    protected boolean assign(TestPlan plan, TestTrackable trackable) {
        return CapacityRules.assign(plan, trackable);
    }

    @Override
    protected boolean assign(TestPlan plan, List<TestTrackable> trackables) {
        return CapacityRules.assign(plan, trackables);
    }

    @Override
    protected Comparator<TestTrackable> comparator(TestPlan plan) {
        return CapacityRules.comparator();
    }

    @Override
    protected TrackableCategory getCategory(TestTrackable trackable) {
        return CapacityRules.category(trackable);
    }

}
