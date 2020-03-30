package ir.baho.algorithm.support;

import ir.baho.algorithm.assignment.InfiniteTrackableAssignment;
import ir.baho.algorithm.domain.TrackableCategory;

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
