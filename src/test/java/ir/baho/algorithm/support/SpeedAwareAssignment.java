package ir.baho.algorithm.support;

import ir.baho.algorithm.assignment.TrackableAssignment;
import ir.baho.algorithm.domain.TrackableCategory;

import java.util.Comparator;
import java.util.List;

/**
 * Assignment whose <em>cost depends on the assigned resource</em>: a plan's cost is its route length multiplied
 * by the vehicle's "slowness", modelled as its capacity (the big truck is the slow one).
 *
 * <p>This is what makes the exchange/reassign repair passes observable at all. Both accept a swap only when the
 * summed plan cost drops, so with a resource-independent cost model (as in {@link TestAssignment}) they can
 * never fire — and a test using that model would silently assert nothing.
 */
public final class SpeedAwareAssignment extends TrackableAssignment<TestPlan, TestTrackable> {

    public SpeedAwareAssignment(List<TestTrackable> trackables) {
        super(trackables);
    }

    public void repairExchange(List<TestPlan> plans) {
        exchange(plans);
    }

    public void repairReassign(List<TestPlan> plans) {
        reassign(plans);
    }

    @Override
    protected boolean assign(TestPlan plan, TestTrackable trackable) {
        if (!CapacityRules.fits(plan, trackable)) {
            plan.setTrackable(null);
            return false;
        }
        plan.setTrackable(trackable);
        plan.setCostMultiplier(trackable.getCapacity());
        return true;
    }

    @Override
    protected boolean assign(TestPlan plan, List<TestTrackable> trackables) {
        for (TestTrackable trackable : trackables) {
            if (CapacityRules.fits(plan, trackable)) {
                return assign(plan, trackable);
            }
        }
        plan.setTrackable(null);
        return false;
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
