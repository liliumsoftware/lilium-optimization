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

import com.github.liliumsoftware.optimization.assignment.SecondDispatchTrackableAssignment;
import com.github.liliumsoftware.optimization.domain.TrackableCategory;

import java.util.Comparator;
import java.util.List;

/**
 * Concrete {@link SecondDispatchTrackableAssignment} for exercising the second-run pool restriction; exposes
 * the protected {@code currentTrackables} view so tests can observe what the pool was narrowed to.
 */
public final class TestSecondDispatchAssignment extends SecondDispatchTrackableAssignment<TestPlan, TestTrackable> {

    public TestSecondDispatchAssignment(List<TestTrackable> trackables) {
        super(trackables);
    }

    /**
     * The pool the assignment layer would currently draw from — the state {@code secondDispatchOnly} and
     * {@code secondDispatchAndOthers} rewrite.
     */
    public List<TestTrackable> currentTrackables() {
        return List.copyOf(currentTrackables);
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
