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

import com.github.liliumsoftware.optimization.calculation.CostCalculation;
import com.github.liliumsoftware.optimization.domain.Planable;
import com.github.liliumsoftware.optimization.domain.Trackable;
import com.github.liliumsoftware.optimization.domain.TrackableCategory;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Assigns resources (trackables) to plans and repairs those assignments. The constructor expands the fleet into
 * assignable units (a resource standing for N interchangeable units becomes N indexed units); the entry points then
 * hand units to plans through two domain hooks, {@link #assign(Planable, Trackable)} (one given unit) and
 * {@link #assign(Planable, List)} (pick one from an offered list). The entry points differ in the units they offer
 * and in whether they repair:
 * <ul>
 * <li>greedy, no repair: {@link #assign(List)}, {@link #assignGreedy} and {@link #assignWithTrackables(List, List)}
 * assign every plan in list order, withdraw each unit once taken and stop at the first plan that cannot be
 * assigned;</li>
 * <li>a single plan: {@link #assign(List, Planable)}, {@link #assignWithTrackables(List, Planable, List)} and
 * {@link #assignWithTrackable};</li>
 * <li>greedy plus repair: {@link #assign(List, List)} and {@link #assignAndExchange} run the greedy step and then the
 * {@link #relocate} → {@link #exchange} → {@link #reassign(List, List)} repair passes, which fill unassigned plans
 * and move plans onto cheaper units.</li>
 * </ul>
 * The list-based entry points, and {@link #relocate} when it offers a donor its replacement, offer each plan the units
 * sorted by {@link #comparator(Planable)}. Plans are always
 * mutated (they receive units); the greedy steps mutate the given plan objects in place, while the repair passes probe
 * on copies and put the accepted copies into the plan list, so the caller must re-read the list after a repairing
 * entry point.
 *
 * <p>The repair passes end once a pass accepts no move and never consult the plans' {@code equals}, so plans compared
 * by value, by code or by identity are repaired alike. Every repair loop also stops after 100 000 accepted moves
 * (logged at WARN), so an overridden {@code compare…} hook that accepts ties cannot loop forever. Units are told apart
 * by {@code equals} (code and index; see {@link Trackable}).
 *
 * <p><strong>Concurrency.</strong> The {@code assign…} methods only read {@link #currentTrackables} (into a private
 * working copy) and otherwise touch nothing but their arguments, so they may run concurrently on independent plan
 * sets; none of them is {@code synchronized}. Concurrent calls must not share a plan object or a plan list, and their
 * plan sets must not compete for a unit: nothing here locks the plans or reserves a unit across calls.
 * {@link com.github.liliumsoftware.optimization.split.ZoneGroups} meets that by running the groups in parallel, each
 * limited by {@link #withoutIdleUnits} to the units its own plans hold, so the groups never compete for a unit; a
 * second, ordered pass then lets each group that wanted one ({@link #wantedIdleUnits}) use the idle units the earlier
 * groups left. The pool itself is replaced only by
 * the second-dispatch variant, whose mutators are synchronized and must not race with in-flight assignments; the field
 * is {@code volatile} so a replaced pool is seen by every thread.
 *
 * @param <P> the plan type
 * @param <T> the resource type being assigned
 */
@Slf4j
public abstract class TrackableAssignment<P extends Planable<P, ?, T>, T extends Trackable<T>> {

    /**
     * Upper bound on the moves one repair loop accepts; only reached when an overridden {@code compare…} hook keeps
     * accepting moves that never converge (for example by accepting ties).
     */
    private static final int MAX_MOVES = 100_000;

    /**
     * The expanded fleet built by the constructor: every unit in fleet order; unmodifiable and never replaced. Unlike
     * {@link #currentTrackables}, which {@link SecondDispatchTrackableAssignment} may narrow or reorder, it never
     * changes.
     */
    @Getter
    protected final List<T> trackables;

    /**
     * The pool the entry points of this class draw from; initially a modifiable copy of {@link #trackables}. The entry
     * points only read it, into private working copies, without locking; {@link SecondDispatchTrackableAssignment}
     * replaces it. Subclasses should replace the reference rather than mutate the list in place.
     */
    protected volatile List<T> currentTrackables;

    /**
     * Expands the fleet into assignable units and makes them the initial pool: each resource {@code t} becomes
     * {@code t.getCount()} copies ({@link Trackable#copy()}) numbered {@code 0 .. count-1} through
     * {@link Trackable#setIndex(int)}, in fleet order. A resource whose count is {@code 0} contributes no unit: that is
     * how a consumer keeps a resource of its fleet list out of the pool (for example one that is unavailable today).
     * Every unit of the expansion must be distinct from every other under {@code equals}, because the pool tells units
     * apart only by {@code equals}: a resource with a count above {@code 1} therefore needs a {@code setIndex} that
     * takes effect and a {@code getIndex}/{@code equals} that include the index (the default no-op {@code setIndex}
     * yields equal copies), and two resources of the fleet must not expand into equal units (for example two
     * single-unit resources with one code).
     *
     * @param trackables the fleet; read once, not kept; must not be or contain {@code null}
     * @throws IllegalArgumentException if a resource reports a negative {@link Trackable#getCount()}, or if two units
     *                                  of the expansion are equal (the message names the codes)
     */
    public TrackableAssignment(List<T> trackables) {
        for (T t : trackables) {
            if (t.getCount() < 0) {
                throw new IllegalArgumentException("Trackable " + t.getCode() + " has a negative count "
                        + t.getCount() + "; use 0 to leave a resource out of the pool");
            }
        }
        this.trackables = trackables.stream().map(t -> IntStream.range(0, t.getCount())
                .mapToObj(value -> {
                    T copy = t.copy();
                    copy.setIndex(value);
                    return copy;
                }).toList()).flatMap(Collection::stream).toList();
        requireDistinctUnits(this.trackables);
        this.currentTrackables = new ArrayList<>(this.trackables);
    }

    /**
     * Rejects an expansion holding two units that are equal; compares by {@code equals} only (not {@code hashCode}),
     * like the pool itself, at a cost quadratic in the number of units.
     */
    private static <T extends Trackable<T>> void requireDistinctUnits(List<T> units) {
        for (int i = 0; i < units.size(); i++) {
            T unit = units.get(i);
            for (int j = i + 1; j < units.size(); j++) {
                T other = units.get(j);
                if (unit.equals(other)) {
                    throw new IllegalArgumentException("Trackable units " + unit.getCode() + " (index "
                            + unit.getIndex() + ") and " + other.getCode() + " (index " + other.getIndex()
                            + ") of the expanded fleet are equal; getIndex, setIndex and equals must tell the units"
                            + " of a multi-unit resource apart, and two resources must not share code and index");
                }
            }
        }
    }

    /**
     * The pool the entry points currently draw from: the expanded fleet ({@link #getTrackables()}) unless a
     * second-dispatch mutator has narrowed or reordered it.
     *
     * @return a read-only view of the current pool; it does not follow a later replacement of the pool by a
     *         second-dispatch mutator (call again after one)
     */
    public List<T> getCurrentTrackables() {
        return Collections.unmodifiableList(currentTrackables);
    }

    /**
     * Assigns every plan of {@code plans}, in list order, from the current pool: each plan is offered the units not yet
     * taken in this call, sorted by {@link #comparator(Planable)}, through {@link #assign(Planable, List)}, and the
     * unit it takes is withdrawn. Every plan is assigned afresh: the units the plans held before the call and the units
     * held by plans outside the list are all on offer. No repair pass runs.
     *
     * @param plans the plans to assign; the plans are mutated in place, the list is not modified
     * @return {@code true} if every plan got a unit; {@code false} as soon as one plan cannot be assigned, in which
     *         case the earlier plans keep their new units and the later plans are not touched (they keep whatever unit
     *         they held before, which an earlier plan may now hold as well)
     */
    public boolean assign(List<P> plans) {
        List<T> tempTrackables = new ArrayList<>(this.currentTrackables);
        for (P plan : plans) {
            tempTrackables.sort(comparator(plan));
            if (!this.assign(plan, tempTrackables)) {
                return false;
            }
            withdraw(tempTrackables, plan);
        }
        return true;
    }

    /**
     * Assigns {@code plan} one unit of the current pool that no plan of {@code plans} holds, offered sorted by
     * {@link #comparator(Planable)} through {@link #assign(Planable, List)}. The unit {@code plan} itself holds is on
     * offer unless a plan of {@code plans} holds it too.
     *
     * @param plans the plans whose units are taken; not modified
     * @param plan  the plan to assign; mutated
     * @return {@code true} if {@code plan} got a unit; {@code false} if {@code plans} already contains it (by
     *         {@code equals}) or no offered unit fits
     */
    public boolean assign(List<P> plans, P plan) {
        if (plans.contains(plan)) {
            return false;
        }
        List<T> tempTrackables = new ArrayList<>(this.currentTrackables);
        tempTrackables.removeAll(plans.stream().map(P::getTrackable).toList());
        tempTrackables.sort(comparator(plan));
        return this.assign(plan, tempTrackables);
    }

    /**
     * Assigns {@code plans} from the units {@code otherPlans} leave free, then repairs the result. The pool is the
     * current pool without the units {@code otherPlans} hold (they are assumed assigned already) and, for a list made
     * by {@link #withoutIdleUnits}, without the units that were idle when that list was made. Steps:
     * <ol>
     * <li>greedy: every plan in list order is offered the remaining units sorted by {@link #comparator(Planable)} and
     * the unit it takes is withdrawn; a refusal does not stop the loop. This step mutates the plan objects in
     * place;</li>
     * <li>if a plan is still without a unit, {@link #relocate} over the pool; if a plan is still
     * without a unit after that, the call returns {@code false} and skips the remaining step;</li>
     * <li>{@link #exchange}, then {@link #reassign(List, List)} over the pool.</li>
     * </ol>
     * Every repair pass draws only from that pool, so a unit held by {@code otherPlans} is never handed to one of
     * {@code plans}. The repair passes replace elements of {@code plans} by modified copies, so the plan objects in the
     * list before the call may be gone from it afterwards: re-read the list. Against a {@link #withoutIdleUnits} list
     * the call also records, whatever its outcome, whether the group could have used an idle unit (see
     * {@link #wantedIdleUnits}).
     *
     * <p>Not synchronized: concurrent calls on one instance run in parallel. They must not share a plan object or a
     * plan list, and each call's units must be out of the other calls' reach (as {@link #withoutIdleUnits} lists
     * arrange): otherwise two calls may book the same unit.
     *
     * @param otherPlans the plans whose units are out of reach; not modified
     * @param plans      the plans to assign; must be mutable, its elements may be replaced
     * @return {@code false} if a plan is still without a unit after relocation, {@code true} otherwise
     * @throws ArithmeticException if the default {@link #compareExchange} sums costs beyond the {@code long} range
     */
    public boolean assign(List<P> otherPlans, List<P> plans) {
        List<T> available = available(otherPlans);
        List<T> tempTrackables = new ArrayList<>(available);
        for (P plan : plans) {
            tempTrackables.sort(comparator(plan));
            this.assign(plan, tempTrackables);
            withdraw(tempTrackables, plan);
        }
        tempTrackables = available;
        if (plans.stream().anyMatch(p -> p.getTrackable() == null)) {
            if (!relocate(plans, tempTrackables)) {
                noteIdleUnitWanted(otherPlans, plans);
                return false;
            }
        }
        exchange(plans);
        reassign(plans, tempTrackables);
        noteIdleUnitWanted(otherPlans, plans);
        return true;
    }

    /**
     * Domain hook: assigns {@code trackable} to {@code plan} if the unit fits the plan, updating whatever depends on
     * the unit (such as the cost) so the {@code compare…} hooks see its effect. A refusal must leave the plan without a
     * resource ({@code getTrackable() == null}). Whether the unit is free is the caller's concern. The repair passes
     * and every probe call it on copies.
     *
     * @param plan      the plan to assign; mutated
     * @param trackable the unit to give it
     * @return {@code true} if the unit was assigned
     */
    protected abstract boolean assign(P plan, T trackable);

    /**
     * Domain hook: assigns {@code plan} a unit picked from {@code trackables}, typically the first that fits. The
     * list-based entry points and {@link #relocate} offer the free units sorted by {@link #comparator(Planable)}. A
     * refusal must leave the plan without a resource ({@code getTrackable() == null}). The list is a private,
     * modifiable working copy: the hook may reorder it but must pick from it.
     *
     * @param plan       the plan to assign; mutated
     * @param trackables the units on offer, in the caller's order of preference; possibly empty
     * @return {@code true} if a unit was assigned
     */
    protected abstract boolean assign(P plan, List<T> trackables);

    /**
     * Greedy assignment like {@link #assign(List)} (every plan in list order, no repair pass, stop at the first
     * refusal) but drawing only from the units {@code otherPlans} do not hold and, for a list made by
     * {@link #withoutIdleUnits}, not from the units that were idle when it was made either. With a plain, empty
     * {@code otherPlans} it is exactly {@link #assign(List)}. The balancing algorithms use it to keep the units of
     * other zone groups out of reach. Against a {@link #withoutIdleUnits} list only a refusal is recorded for
     * {@link #wantedIdleUnits}; a successful call does not probe whether an idle unit would have been cheaper.
     *
     * @param otherPlans the plans whose units are out of reach; not modified
     * @param plans      the plans to assign; the plans are mutated in place, the list is not modified
     * @return {@code true} if every plan got a unit; {@code false} as soon as one cannot be assigned (earlier plans
     *         keep their new units, later plans are not touched)
     */
    public boolean assignGreedy(List<P> otherPlans, List<P> plans) {
        if (otherPlans.isEmpty() && !(otherPlans instanceof HeldUnitsOnly)) {
            return assign(plans);
        }
        List<T> tempTrackables = available(otherPlans);
        for (P plan : plans) {
            tempTrackables.sort(comparator(plan));
            if (!this.assign(plan, tempTrackables)) {
                noteIdleUnitWanted(otherPlans, plans);
                return false;
            }
            withdraw(tempTrackables, plan);
        }
        return true;
    }

    /**
     * Whether an assignment against {@code otherPlans}, a list made by {@link #withoutIdleUnits}, could have used one
     * of the idle units it was denied. {@link #assign(List, List)} records it when a plan stayed without a resource
     * that an idle unit accepts, or an idle unit of another category would have made an assigned plan cheaper
     * ({@link #compareReassign}); {@link #assignGreedy} records it only on a refusal. The flag is never cleared. Only
     * such a group can gain from the idle units, so {@code ZoneGroups} gives only these groups their ordered second
     * pass.
     *
     * @param otherPlans a list previously passed as {@code otherPlans}
     * @return the recorded flag for a {@link #withoutIdleUnits} list; {@code false} for any other list
     */
    public boolean wantedIdleUnits(List<P> otherPlans) {
        if (otherPlans instanceof HeldUnitsOnly<?, ?> restricted) {
            return restricted.idleWanted;
        }
        return false;
    }

    /**
     * Records on a {@link #withoutIdleUnits} list that its group could have used one of the idle units it was denied.
     * Probes copies only; once recorded, later calls return at once.
     */
    private void noteIdleUnitWanted(List<P> otherPlans, List<P> plans) {
        if (!(otherPlans instanceof HeldUnitsOnly<?, ?> restricted)) {
            return;
        }
        if (restricted.idleWanted || restricted.idle().isEmpty()) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<T> idle = (List<T>) restricted.idle();
        for (P plan : plans) {
            for (T unit : idle) {
                if (plan.getTrackable() == null) {
                    if (assign(plan.copy(), unit)) {
                        restricted.idleWanted = true;
                        return;
                    }
                } else if (!getCategory(plan.getTrackable()).equals(getCategory(unit))) {
                    P probe = plan.copy();
                    if (assign(probe, unit) && compareReassign(plan, probe)) {
                        restricted.idleWanted = true;
                        return;
                    }
                }
            }
        }
    }

    /**
     * An {@code otherPlans} argument for {@link #assign(List, List)} and {@link #assignGreedy} that also keeps every
     * idle unit out of reach: a unit of the current pool that neither {@code otherPlans} nor {@code plans} holds at the
     * time of this call. Plans assigned against it can only trade the units {@code plans} already hold, so groups of
     * plans that are optimized concurrently, each against such a list, never compete for a unit. The idle units are
     * fixed now, but the units of {@code otherPlans} are read from those plan objects at every later call, so they must
     * keep their units while the list is in use. The list also carries the flag read by {@link #wantedIdleUnits}.
     *
     * @param otherPlans the plans of the other groups; must not contain {@code null}
     * @param plans      the group that will be assigned against the result; not modified
     * @return a read-only list holding the plans of {@code otherPlans} (the same objects, not copies)
     * @throws NullPointerException if {@code otherPlans} contains {@code null}
     */
    public List<P> withoutIdleUnits(List<P> otherPlans, List<P> plans) {
        List<T> idle = new ArrayList<>(this.currentTrackables);
        idle.removeAll(otherPlans.stream().map(P::getTrackable).toList());
        idle.removeAll(plans.stream().map(P::getTrackable).toList());
        return new HeldUnitsOnly<>(List.copyOf(otherPlans), List.copyOf(idle));
    }

    /**
     * Whether the current pool holds a unit that no plan of {@code plans} holds.
     *
     * @param plans the plans whose units count as taken; not modified
     * @return {@code true} if at least one unit of the current pool is idle
     */
    public boolean hasIdleUnits(List<P> plans) {
        List<T> idle = new ArrayList<>(this.currentTrackables);
        idle.removeAll(plans.stream().map(P::getTrackable).toList());
        return !idle.isEmpty();
    }

    /**
     * The current pool without the units {@code otherPlans} hold and, for a list made by {@link #withoutIdleUnits},
     * without the units that were idle when it was made.
     */
    private List<T> available(List<P> otherPlans) {
        List<T> pool = new ArrayList<>(this.currentTrackables);
        pool.removeAll(otherPlans.stream().map(P::getTrackable).toList());
        if (otherPlans instanceof HeldUnitsOnly<?, ?> restricted) {
            @SuppressWarnings("unchecked")
            List<T> idle = (List<T>) restricted.idle();
            pool.removeAll(idle);
        }
        return pool;
    }

    /**
     * Greedy assignment like {@link #assign(List)}, but drawing from {@code trackables} instead of the current pool:
     * every plan in list order is offered the units of {@code trackables} not yet taken in this call, sorted by
     * {@link #comparator(Planable)}, and no repair pass runs. Units held by plans outside the list are not excluded.
     *
     * @param plans      the plans to assign; the plans are mutated in place, the list is not modified
     * @param trackables the sub-pool; copied, not modified
     * @return {@code true} if every plan got a unit; {@code false} as soon as one cannot be assigned (earlier plans
     *         keep their new units, later plans are not touched)
     */
    public boolean assignWithTrackables(List<P> plans, List<T> trackables) {
        List<T> tempTrackables = new ArrayList<>(trackables);
        for (P plan : plans) {
            tempTrackables.sort(comparator(plan));
            if (!this.assign(plan, tempTrackables)) {
                return false;
            }
            withdraw(tempTrackables, plan);
        }
        return true;
    }

    /**
     * Like {@link #assign(List, Planable)}, but drawing from {@code trackables} instead of the current pool:
     * {@code plan} is offered the units of {@code trackables} that no plan of {@code plans} holds, sorted by
     * {@link #comparator(Planable)}.
     *
     * @param plans      the plans whose units are taken; not modified
     * @param plan       the plan to assign; mutated
     * @param trackables the sub-pool; copied, not modified
     * @return {@code true} if {@code plan} got a unit; {@code false} if {@code plans} already contains it (by
     *         {@code equals}) or no offered unit fits
     */
    public boolean assignWithTrackables(List<P> plans, P plan, List<T> trackables) {
        if (plans.contains(plan)) {
            return false;
        }
        List<T> tempTrackables = new ArrayList<>(trackables);
        tempTrackables.removeAll(plans.stream().map(P::getTrackable).toList());
        tempTrackables.sort(comparator(plan));
        return this.assign(plan, tempTrackables);
    }

    /**
     * Assigns the specific {@code trackable} to {@code plan}: {@link #assign(Planable, Trackable)} is tried on a copy
     * first, so a refusal leaves {@code plan} untouched, and only then applied to {@code plan} itself. Neither the pool
     * nor any other plan is consulted: avoiding a double booking is the caller's concern.
     *
     * @param plan      the plan to assign; mutated on success
     * @param trackable the unit to give it
     * @return {@code true} if the unit was assigned; {@code false} if the hook refused it ({@code plan} is unchanged)
     */
    public boolean assignWithTrackable(P plan, T trackable) {
        P copy = plan.copy();
        if (!assign(copy, trackable)) {
            return false;
        }
        assign(plan, trackable);
        return true;
    }

    /**
     * Assigns {@code plans} from the whole current pool and repairs the result, treating {@code plans} as the complete
     * plan set: a unit is free unless one of {@code plans} holds it, so the units of plans outside the list are on
     * offer. Steps: the greedy step of {@link #assign(List, List)} (a refusal does not stop it; the plan objects are
     * mutated in place), then {@link #relocate} over the current pool and, only when every plan then holds a unit,
     * {@link #exchange} and {@link #reassign(List)}. The repair passes replace elements of {@code plans} by modified
     * copies: re-read the list after the call. Not synchronized.
     *
     * @param plans the plans to assign; must be mutable, its elements may be replaced
     * @return {@code false} if some plan still has no resource after relocation (exchange and reassign are then
     *         skipped), {@code true} otherwise
     * @throws ArithmeticException if the default {@link #compareExchange} sums costs beyond the {@code long} range
     */
    public boolean assignAndExchange(List<P> plans) {
        List<T> tempTrackables = new ArrayList<>(this.currentTrackables);
        for (P plan : plans) {
            tempTrackables.sort(comparator(plan));
            this.assign(plan, tempTrackables);
            withdraw(tempTrackables, plan);
        }
        boolean e = relocate(plans, new ArrayList<>(this.currentTrackables));
        if (!e) {
            return false;
        }
        exchange(plans);
        reassign(plans);
        return true;
    }

    /**
     * Whether one unit may back several plans at once. {@code false} for this finite fleet: a unit held by one plan is
     * unavailable to every other, so zone groups must not compete for units (see {@code ZoneGroups}). The
     * unlimited-fleet variant returns {@code true}.
     *
     * @return {@code false}
     */
    public boolean sharesUnits() {
        return false;
    }

    /**
     * Takes the unit {@code plan} just claimed out of the candidate pool, so a later plan in the same pass
     * cannot be handed the same one. Without this the whole-set overloads would double-book a resource and
     * report success for a plan set the fleet cannot actually execute — the single-plan overloads and the
     * relocate/reassign passes all exclude in-use units the same way.
     */
    private void withdraw(List<T> trackables, P plan) {
        T assigned = plan.getTrackable();
        if (assigned != null) {
            trackables.remove(assigned);
        }
    }

    /**
     * Repair pass that hands the unit of an assigned plan (the donor) to an unassigned one and gives the donor another
     * unit, repeating until a full pass accepts no move. For each pair of plans of which exactly one holds a unit, the
     * move is probed on copies of the whole list: the recipient is offered the donor's unit through
     * {@link #assign(Planable, Trackable)}, then the donor is offered, through {@link #assign(Planable, List)}, the
     * units of {@code trackables} that no plan holds, sorted by {@link #comparator(Planable)} for the donor (the copy
     * of the donor, still holding the unit it hands over). The move is accepted when both succeed and
     * {@link #compareRelocate} approves; the content of {@code plans} is then replaced by the copies. Every accepted
     * move leaves one more plan holding a unit, so the pass terminates; it never consults the plans' {@code equals},
     * and the move cap shared by every repair loop (100 000, logged at WARN) applies as well.
     *
     * @param plans      the plans to repair; must be mutable, its elements are replaced by copies on every accepted
     *                   move
     * @param trackables the pool replacement units are drawn from; not modified
     * @return {@code true} if every plan holds a unit at the end
     */
    protected boolean relocate(List<P> plans, List<T> trackables) {
        int moves = 0;
        while (true) {
            boolean moved = false;
            for (int i = 0; i < plans.size(); i++) {
                for (int j = i + 1; j < plans.size(); j++) {
                    if (isMoveLimitReached(moves, "relocate")) {
                        return plans.stream().allMatch(p -> p.getTrackable() != null);
                    }
                    P firstPlan = plans.get(i);
                    P secondPlan = plans.get(j);
                    // A hand-over needs exactly one of the two plans to hold a unit; checking that on the originals
                    // spares copying the whole plan list for every other pair.
                    if ((firstPlan.getTrackable() == null) == (secondPlan.getTrackable() == null)) {
                        continue;
                    }
                    List<P> tempPlans = plans.stream().map(P::copy).collect(Collectors.toList());
                    P tempFirstPlan = tempPlans.get(i);
                    P tempSecondPlan = tempPlans.get(j);

                    if (tempSecondPlan.getTrackable() != null
                            && tempFirstPlan.getTrackable() == null
                            && assign(tempFirstPlan, tempSecondPlan.getTrackable())
                            && assign(tempSecondPlan, replacements(trackables, tempPlans, tempSecondPlan))
                            && tempSecondPlan.getTrackable() != null
                            && compareRelocate(tempPlans, plans)) {
                        plans.clear();
                        plans.addAll(tempPlans);
                        moved = true;
                        moves++;
                        if (log.isTraceEnabled()) {
                            log.trace(getMessage(firstPlan, secondPlan));
                        }
                    } else if (tempFirstPlan.getTrackable() != null
                            && tempSecondPlan.getTrackable() == null
                            && assign(tempSecondPlan, tempFirstPlan.getTrackable())
                            && assign(tempFirstPlan, replacements(trackables, tempPlans, tempFirstPlan))
                            && tempFirstPlan.getTrackable() != null
                            && compareRelocate(tempPlans, plans)) {
                        plans.clear();
                        plans.addAll(tempPlans);
                        moved = true;
                        moves++;
                        if (log.isTraceEnabled()) {
                            log.trace(getMessage(firstPlan, secondPlan));
                        }
                    }
                }
            }
            if (!moved) {
                return plans.stream().allMatch(p -> p.getTrackable() != null);
            }
        }
    }

    /**
     * The units {@link #relocate} offers the donor: those of {@code trackables} that no plan of {@code plans} holds,
     * sorted by {@link #comparator(Planable)} for the donor.
     */
    private List<T> replacements(List<T> trackables, List<P> plans, P donor) {
        return trackables.stream().filter(t -> plans.stream().noneMatch(p -> t.equals(p.getTrackable())))
                .sorted(comparator(donor)).collect(Collectors.toList());
    }

    /**
     * Whether a repair loop has accepted {@link #MAX_MOVES} moves; logs a WARN when it has.
     */
    private boolean isMoveLimitReached(int moves, String repair) {
        if (moves < MAX_MOVES) {
            return false;
        }
        log.warn("Trackable {} stopped after {} accepted moves without converging; check the compare hooks", repair,
                MAX_MOVES);
        return true;
    }

    /**
     * Repair pass that swaps the units of two plans whose units belong to different categories
     * ({@link #getCategory}), repeating until a full pass accepts no swap. Plans without a unit are skipped. Each swap
     * is probed on copies of the whole list through {@link #assign(Planable, Trackable)} and accepted when
     * {@link #compareExchange} approves; the content of {@code plans} is then replaced by the copies. With the default
     * hook every accepted swap strictly lowers the total cost, so the pass terminates; it never consults the plans'
     * {@code equals}, and the move cap shared by every repair loop (100 000, logged at WARN) stops a hook that keeps
     * accepting.
     *
     * @param plans the plans to repair; must be mutable, its elements are replaced by copies on every accepted swap
     * @throws ArithmeticException if the default {@link #compareExchange} sums costs beyond the {@code long} range
     */
    protected void exchange(List<P> plans) {
        boolean moved;
        int moves = 0;
        do {
            moved = false;
            for (int i = 0; i < plans.size(); i++) {
                for (int j = i + 1; j < plans.size(); j++) {
                    if (isMoveLimitReached(moves, "exchange")) {
                        return;
                    }
                    P firstPlan = plans.get(i);
                    P secondPlan = plans.get(j);
                    // A swap needs two held units of different categories; checking that on the originals spares
                    // copying the whole plan list for every other pair (the copies are checked again below).
                    if (firstPlan.getTrackable() == null || secondPlan.getTrackable() == null
                            || getCategory(firstPlan.getTrackable()).equals(getCategory(secondPlan.getTrackable()))) {
                        continue;
                    }
                    List<P> tempPlans = plans.stream().map(P::copy).toList();
                    P tempFirstPlan = tempPlans.get(i);
                    P tempSecondPlan = tempPlans.get(j);

                    // Both resources must be read before either is overwritten: assigning the first plan
                    // replaces its trackable, so reading it afterwards would hand the *same* unit to both
                    // plans (a double-booking) instead of trading them.
                    T firstTrackable = tempFirstPlan.getTrackable();
                    T secondTrackable = tempSecondPlan.getTrackable();

                    if (firstTrackable != null
                            && secondTrackable != null
                            && !getCategory(firstTrackable).equals(getCategory(secondTrackable))
                            && assign(tempFirstPlan, secondTrackable)
                            && assign(tempSecondPlan, firstTrackable)
                            && compareExchange(tempFirstPlan, tempSecondPlan, tempPlans, plans)) {
                        plans.clear();
                        plans.addAll(tempPlans);
                        moved = true;
                        moves++;
                        if (log.isTraceEnabled()) {
                            log.trace(getMessage(firstPlan, secondPlan));
                        }
                    }
                }
            }
        } while (moved);
    }

    /**
     * {@link #reassign(List, List)} over the whole current pool: a unit is idle unless one of {@code plans} holds it,
     * so the units of plans outside the list count as idle.
     *
     * @param plans the plans to repair; must support {@code set}, its elements may be replaced by copies
     */
    protected void reassign(List<P> plans) {
        reassign(plans, this.currentTrackables);
    }

    /**
     * Repair pass that moves an assigned plan onto an idle unit of another category ({@link #getCategory}) when
     * {@link #compareReassign} approves. A unit is idle when it is in {@code trackables} and no plan of {@code plans}
     * holds it; plans without a unit are left alone. Every candidate is probed on a fresh copy of the plan through
     * {@link #assign(Planable, Trackable)}, so a refused or rejected probe never leaks into the next one; an accepted
     * copy replaces the plan at its position and the scan restarts from the first plan. The pass ends once a full scan
     * accepts no move. With the default hook every accepted move strictly lowers that plan's cost, so the pass
     * terminates; the move cap shared by every repair loop (100 000, logged at WARN) counts every accepted move, so an
     * overridden hook that accepts ties cannot loop forever.
     *
     * @param plans      the plans to repair; must support {@code set}, its elements may be replaced by copies
     * @param trackables the pool idle units are taken from; not modified
     */
    protected void reassign(List<P> plans, List<T> trackables) {
        boolean moved;
        int moves = 0;
        do {
            moved = false;
            for (int i = 0; i < plans.size(); i++) {
                if (isMoveLimitReached(moves, "reassign")) {
                    return;
                }
                P plan = plans.get(i);
                if (plan.getTrackable() == null) {
                    continue;
                }
                TrackableCategory category = getCategory(plan.getTrackable());
                List<T> idle = trackables.stream().filter(t -> plans.stream()
                        .noneMatch(p -> t.equals(p.getTrackable()))).toList();
                for (T trackable : idle) {
                    if (category.equals(getCategory(trackable))) {
                        continue;
                    }
                    P newPlan = plan.copy();
                    if (assign(newPlan, trackable) && compareReassign(plan, newPlan)) {
                        plans.set(i, newPlan);
                        moved = true;
                        moves++;
                        if (log.isTraceEnabled()) {
                            log.trace(getMessage(plan, newPlan));
                        }
                        i = -1;
                        break;
                    }
                }
            }
        } while (moved);
    }

    /**
     * Acceptance test of {@link #relocate}: whether {@code newPlans}, the copies with the move applied, is the better
     * outcome. By default it accepts when more plans of {@code newPlans} than of {@code oldPlans} hold a resource.
     * Overrides should accept strict improvements only.
     *
     * @param newPlans copies of the plans with the move applied
     * @param oldPlans the plans before the move
     * @return {@code true} to accept the move
     */
    protected boolean compareRelocate(List<P> newPlans, List<P> oldPlans) {
        return newPlans.stream().filter(p -> p.getTrackable() != null).count() > oldPlans.stream()
                .filter(p -> p.getTrackable() != null).count();
    }

    /**
     * Acceptance test of {@link #exchange}: whether the swap that produced {@code newPlans} is accepted. Overrides
     * must accept strict improvements only: a test that accepts ties can keep a repair loop swapping until the move
     * cap stops it. By default it accepts when the summed cost ({@link CostCalculation#totalCost}) of {@code newPlans}
     * is lower than that of {@code oldPlans}; the two swapped plans are not consulted separately.
     *
     * @param firstPlan  the copy of the first plan, now holding the second plan's unit
     * @param secondPlan the copy of the second plan, now holding the first plan's unit
     * @param newPlans   copies of all plans with the swap applied (containing {@code firstPlan} and
     *                   {@code secondPlan})
     * @param oldPlans   the plans before the swap
     * @return {@code true} to accept the swap
     * @throws ArithmeticException if a summed cost does not fit a {@code long} (default implementation)
     */
    protected boolean compareExchange(P firstPlan, P secondPlan, List<P> newPlans, List<P> oldPlans) {
        return CostCalculation.totalCost(newPlans) < CostCalculation.totalCost(oldPlans);
    }

    /**
     * Acceptance test of {@link #reassign(List, List)} (also used to probe idle units for {@link #wantedIdleUnits}):
     * whether {@code newPlan}, on an idle unit, beats {@code plan}. Overrides must accept strict improvements only: a
     * test that accepts ties can move a plan back and forth until the move cap stops it. By default it accepts when
     * the cost of {@code newPlan} is lower than that of {@code plan}.
     *
     * @param plan    the plan on its current unit
     * @param newPlan a copy of {@code plan} assigned to the idle unit
     * @return {@code true} to accept the move
     */
    protected boolean compareReassign(P plan, P newPlan) {
        return newPlan.getCost() < plan.getCost();
    }

    /**
     * The message logged at TRACE for an accepted repair move; only called when TRACE is enabled. By default
     * {@code "Changed trackables"}.
     *
     * @param firstPlan  the first plan of the move as it was before the move (for {@link #reassign(List, List)}, the
     *                   plan that moved)
     * @param secondPlan the second plan of the move as it was before the move (for {@link #reassign(List, List)}, the
     *                   accepted copy on its new unit)
     * @return the message to log
     */
    protected String getMessage(P firstPlan, P secondPlan) {
        return "Changed trackables";
    }

    /**
     * The preference order of the units offered to {@code plan}: the list-based entry points sort the units on offer
     * with it before calling {@link #assign(Planable, List)}, which typically takes the first one that fits.
     *
     * @param plan the plan about to be assigned
     * @return the order in which units are offered to {@code plan}, most preferred first
     */
    protected abstract Comparator<T> comparator(P plan);

    /**
     * The category of {@code trackable}, compared by {@code equals}: {@link #exchange} and
     * {@link #reassign(List, List)} only move plans across categories.
     *
     * @param trackable a unit of the fleet
     * @return its category; never {@code null}
     */
    protected abstract TrackableCategory getCategory(T trackable);

    /**
     * The plans of other groups plus the units that were idle when the list was made; read-only.
     */
    private static final class HeldUnitsOnly<P, T> extends AbstractList<P> {

        private final List<P> plans;
        private final List<T> idle;
        /**
         * Set by the one group optimized against this list; read after that group's run has completed.
         */
        private volatile boolean idleWanted;

        private HeldUnitsOnly(List<P> plans, List<T> idle) {
            this.plans = plans;
            this.idle = idle;
        }

        List<T> idle() {
            return idle;
        }

        @Override
        public P get(int index) {
            return plans.get(index);
        }

        @Override
        public int size() {
            return plans.size();
        }

    }

}
