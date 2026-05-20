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

import com.github.liliumsoftware.optimization.domain.TrackableCategory;

import java.util.Comparator;
import java.util.List;

/**
 * The one resource-fitting rule shared by every {@code TrackableAssignment} double in these tests: a trackable
 * fits a plan when its capacity is at least the plan's customer count. Kept in one place so the plain, infinite
 * and second-dispatch variants cannot drift apart and silently test different rules.
 */
public final class CapacityRules {

    private CapacityRules() {
    }

    public static boolean fits(TestPlan plan, TestTrackable trackable) {
        return plan.getCustomerCount() <= trackable.getCapacity();
    }

    /**
     * Assigns {@code trackable} when it fits, otherwise clears the plan's resource — the contract
     * {@code TrackableAssignment} relies on when it probes a candidate.
     */
    public static boolean assign(TestPlan plan, TestTrackable trackable) {
        if (fits(plan, trackable)) {
            plan.setTrackable(trackable);
            return true;
        }
        plan.setTrackable(null);
        return false;
    }

    /**
     * Assigns the first fitting trackable in the supplied (already ordered) pool.
     */
    public static boolean assign(TestPlan plan, List<TestTrackable> trackables) {
        for (TestTrackable trackable : trackables) {
            if (fits(plan, trackable)) {
                plan.setTrackable(trackable);
                return true;
            }
        }
        plan.setTrackable(null);
        return false;
    }

    /**
     * Smallest-fitting-resource-first, with the code as a deterministic tie-break.
     */
    public static Comparator<TestTrackable> comparator() {
        return Comparator.comparingInt(TestTrackable::getCapacity).thenComparing(TestTrackable::getCode);
    }

    public static TrackableCategory category(TestTrackable trackable) {
        return new CapacityCategory(trackable.getCapacity());
    }

    /**
     * Category keyed by capacity; distinct capacities are treated as exchangeable-across categories.
     */
    public record CapacityCategory(int capacity) implements TrackableCategory {
    }

}
