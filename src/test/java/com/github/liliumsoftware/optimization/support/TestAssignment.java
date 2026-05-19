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
