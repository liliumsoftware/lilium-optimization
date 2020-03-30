package ir.baho.algorithm.assignment;

import ir.baho.algorithm.domain.Planable;
import ir.baho.algorithm.domain.Trackable;

import java.util.ArrayList;
import java.util.List;

/**
 * Assignment variant for an unlimited fleet: every plan is simply assigned from the full trackable list with
 * no contention, so there is no relocate/exchange repair and assignment never fails for lack of resources.
 * Used when resource count is not a binding constraint.
 *
 * <p>Every entry point follows that rule — including {@link #assignAndExchange}, the sub-pool overloads and the
 * zone-group overload: no unit is ever withdrawn, units held by other plans stay available, and the same unit
 * instance may back several plans. A plan only fails to be assigned when no unit of the offered pool fits it. Unlike
 * the finite variant, every overload offers the pool sorted by {@link #comparator} for the plan at hand, so each plan
 * gets the unit the consumer prefers for it.
 */
public abstract class InfiniteTrackableAssignment<P
        extends Planable<P, ?, T>, T extends Trackable<T>> extends TrackableAssignment<P, T> {

    /**
     * Creates the assignment over {@code trackables}.
     *
     * @param trackables the fleet; expanded per {@link TrackableAssignment#TrackableAssignment(List)}
     */
    public InfiniteTrackableAssignment(List<T> trackables) {
        super(trackables);
    }

    /**
     * Assigns {@code plan} from the full fleet. Always possible unless no unit fits the plan.
     *
     * @return {@code true} if a unit was assigned
     */
    public boolean assign(P plan) {
        return this.assign(plan, ordered(plan, trackables));
    }

    /**
     * Assigns every plan independently from the full fleet (units may be reused across plans).
     */
    @Override
    public boolean assign(List<P> plans) {
        return plans.stream().allMatch(this::assign);
    }

    /**
     * Assigns {@code plan} from the full fleet; refuses a plan that is already in {@code plans}.
     */
    @Override
    public boolean assign(List<P> plans, P plan) {
        if (plans.contains(plan)) {
            return false;
        }
        return this.assign(plan);
    }

    /**
     * Assigns every plan from the full fleet; {@code otherPlans} never contend for units in this variant.
     */
    @Override
    public boolean assign(List<P> otherPlans, List<P> plans) {
        return plans.stream().allMatch(this::assign);
    }

    /**
     * Assigns every plan from the full fleet; {@code otherPlans} never contend for units in this variant.
     */
    @Override
    public boolean assignGreedy(List<P> otherPlans, List<P> plans) {
        return assign(plans);
    }

    /**
     * Assigns every plan from the full fleet. There is nothing to repair with an unlimited fleet, so the relocate,
     * exchange and reassign passes of the finite variant do not run.
     *
     * @return {@code true} if every plan got a unit
     */
    @Override
    public boolean assignAndExchange(List<P> plans) {
        boolean assigned = true;
        for (P plan : plans) {
            assigned &= this.assign(plan);
        }
        return assigned;
    }

    /**
     * Assigns every plan from the given sub-pool without withdrawing units (each plan sees the whole sub-pool).
     */
    @Override
    public boolean assignWithTrackables(List<P> plans, List<T> trackables) {
        return plans.stream().allMatch(plan -> this.assign(plan, ordered(plan, trackables)));
    }

    /**
     * Assigns {@code plan} from the given sub-pool, including units {@code plans} hold; refuses a plan that is already
     * in {@code plans}.
     */
    @Override
    public boolean assignWithTrackables(List<P> plans, P plan, List<T> trackables) {
        if (plans.contains(plan)) {
            return false;
        }
        return this.assign(plan, ordered(plan, trackables));
    }

    /**
     * {@code true}: with an unlimited fleet one unit may back any number of plans.
     */
    @Override
    public boolean sharesUnits() {
        return true;
    }

    private List<T> ordered(P plan, List<T> pool) {
        List<T> sorted = new ArrayList<>(pool);
        sorted.sort(comparator(plan));
        return sorted;
    }

}
