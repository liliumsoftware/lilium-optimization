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
