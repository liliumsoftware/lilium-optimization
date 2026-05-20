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
import com.github.liliumsoftware.optimization.domain.TrackableCategory;

import java.util.Comparator;
import java.util.List;

/**
 * Concrete {@link TrackableAssignment} with a simple capacity rule: a trackable fits a plan when its capacity
 * is at least the plan's customer count (see {@link CapacityRules}). Enough to drive assignment/expansion
 * tests deterministically.
 *
 * <p>The preference order is configurable because it decides whether the greedy first pass strands a plan:
 * smallest-fitting-first (the default) is nearly always optimal, while largest-first strands plans and is what
 * exercises the relocate repair pass.
 */
public final class TestAssignment extends TrackableAssignment<TestPlan, TestTrackable> {

    private final Comparator<TestTrackable> comparator;

    public TestAssignment(List<TestTrackable> trackables) {
        this(trackables, CapacityRules.comparator());
    }

    public TestAssignment(List<TestTrackable> trackables, Comparator<TestTrackable> comparator) {
        super(trackables);
        this.comparator = comparator;
    }

    /**
     * Prefers the biggest resource first — a wasteful but realistic policy that leaves later plans stranded.
     */
    public static TestAssignment preferringLargest(List<TestTrackable> trackables) {
        return new TestAssignment(trackables, CapacityRules.comparator().reversed());
    }

    // ------------------------------------------------------------------ exposed repair passes

    public boolean repairRelocate(List<TestPlan> plans, List<TestTrackable> pool) {
        return relocate(plans, pool);
    }

    public void repairExchange(List<TestPlan> plans) {
        exchange(plans);
    }

    public void repairReassign(List<TestPlan> plans) {
        reassign(plans);
    }

    public List<TestTrackable> pool() {
        return List.copyOf(currentTrackables);
    }

    // ------------------------------------------------------------------ domain hooks

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
        return comparator;
    }

    @Override
    protected TrackableCategory getCategory(TestTrackable trackable) {
        return CapacityRules.category(trackable);
    }

}
