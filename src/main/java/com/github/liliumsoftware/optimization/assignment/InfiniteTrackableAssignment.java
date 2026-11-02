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

package com.github.liliumsoftware.optimization.assignment;

import com.github.liliumsoftware.optimization.domain.Planable;
import com.github.liliumsoftware.optimization.domain.Trackable;

import java.util.ArrayList;
import java.util.List;

/**
 * Assignment variant for an unlimited fleet: every plan is assigned from the whole expanded fleet
 * ({@link #getTrackables()}, not the current pool) with no contention, so there is no relocate/exchange/reassign
 * repair and assignment never fails for lack of resources. Used when resource count is not a binding constraint.
 *
 * <p>Every entry point follows that rule, including {@link #assignAndExchange}, the sub-pool overloads and the
 * zone-group overloads: no unit is ever withdrawn, units held by other plans stay available, {@code otherPlans} is
 * ignored, and the same unit instance may back several plans. A plan only fails to be assigned when no unit on offer
 * fits it. Like the list-based overloads of the finite variant, every overload offers the units sorted by
 * {@link #comparator} for the plan at hand; the difference is that a unit taken by one plan is still offered to the
 * next. {@link #assignWithTrackable}, {@link #withoutIdleUnits}, {@link #hasIdleUnits} and {@link #wantedIdleUnits}
 * are inherited unchanged; {@link #wantedIdleUnits} stays {@code false} because no overload here records it.
 *
 * <p>No overload of this class is synchronized: they read only the immutable expanded fleet and their arguments.
 *
 * @param <P> the plan type
 * @param <T> the resource type being assigned
 */
public abstract class InfiniteTrackableAssignment<P
        extends Planable<P, ?, T>, T extends Trackable<T>> extends TrackableAssignment<P, T> {

    /**
     * Creates the assignment over {@code trackables}.
     *
     * @param trackables the fleet; expanded as described in {@link TrackableAssignment#TrackableAssignment(List)}
     */
    public InfiniteTrackableAssignment(List<T> trackables) {
        super(trackables);
    }

    /**
     * Assigns {@code plan} from the whole expanded fleet, offered sorted by {@link #comparator} through
     * {@link #assign(Planable, List)}. Always possible unless no unit fits the plan.
     *
     * @param plan the plan to assign; mutated
     * @return {@code true} if a unit was assigned
     */
    public boolean assign(P plan) {
        return this.assign(plan, ordered(plan, trackables));
    }

    /**
     * Assigns every plan independently from the whole expanded fleet (units may be reused across plans), in list order,
     * stopping at the first plan that cannot be assigned: the later plans are then not touched.
     *
     * @param plans the plans to assign; the plans are mutated in place, the list is not modified
     * @return {@code true} if every plan got a unit; {@code false} at the first plan that did not
     */
    @Override
    public boolean assign(List<P> plans) {
        return plans.stream().allMatch(this::assign);
    }

    /**
     * Assigns {@code plan} from the whole expanded fleet, including the units {@code plans} hold.
     *
     * @param plans the plans already assigned; only used to refuse a plan that is one of them
     * @param plan  the plan to assign; mutated
     * @return {@code true} if {@code plan} got a unit; {@code false} if {@code plans} already contains it (by
     *         {@code equals}) or no unit fits
     */
    @Override
    public boolean assign(List<P> plans, P plan) {
        if (plans.contains(plan)) {
            return false;
        }
        return this.assign(plan);
    }

    /**
     * Assigns every plan from the whole expanded fleet, exactly like {@link #assign(List)}: {@code otherPlans} never
     * contend for units in this variant, no repair pass runs, the plans are mutated in place and the list is never
     * modified. Stops at the first plan that cannot be assigned, leaving the later plans untouched. It records nothing
     * for {@link #wantedIdleUnits}.
     *
     * @param otherPlans ignored
     * @param plans      the plans to assign
     * @return {@code true} if every plan got a unit; {@code false} at the first plan that did not
     */
    @Override
    public boolean assign(List<P> otherPlans, List<P> plans) {
        return plans.stream().allMatch(this::assign);
    }

    /**
     * Same as {@link #assign(List)}: {@code otherPlans} never contend for units in this variant.
     *
     * @param otherPlans ignored
     * @param plans      the plans to assign
     * @return {@code true} if every plan got a unit; {@code false} at the first plan that did not
     */
    @Override
    public boolean assignGreedy(List<P> otherPlans, List<P> plans) {
        return assign(plans);
    }

    /**
     * Assigns every plan from the whole expanded fleet. Unlike {@link #assign(List)} it tries every plan, even after
     * one could not be assigned. There is nothing to repair with an unlimited fleet, so the relocate, exchange and
     * reassign passes of the finite variant do not run, and the list is never modified.
     *
     * @param plans the plans to assign; the plans are mutated in place
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
     * Assigns every plan from {@code trackables} without withdrawing units: each plan is offered the whole sub-pool,
     * sorted by {@link #comparator}. Stops at the first plan that cannot be assigned, leaving the later plans
     * untouched.
     *
     * @param plans      the plans to assign; the plans are mutated in place, the list is not modified
     * @param trackables the sub-pool; copied, not modified
     * @return {@code true} if every plan got a unit; {@code false} at the first plan that did not
     */
    @Override
    public boolean assignWithTrackables(List<P> plans, List<T> trackables) {
        return plans.stream().allMatch(plan -> this.assign(plan, ordered(plan, trackables)));
    }

    /**
     * Assigns {@code plan} from {@code trackables}, including the units {@code plans} hold, sorted by
     * {@link #comparator}.
     *
     * @param plans      the plans already assigned; only used to refuse a plan that is one of them
     * @param plan       the plan to assign; mutated
     * @param trackables the sub-pool; copied, not modified
     * @return {@code true} if {@code plan} got a unit; {@code false} if {@code plans} already contains it (by
     *         {@code equals}) or no offered unit fits
     */
    @Override
    public boolean assignWithTrackables(List<P> plans, P plan, List<T> trackables) {
        if (plans.contains(plan)) {
            return false;
        }
        return this.assign(plan, ordered(plan, trackables));
    }

    /**
     * Whether one unit may back several plans at once; always, with an unlimited fleet.
     *
     * @return {@code true}
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
