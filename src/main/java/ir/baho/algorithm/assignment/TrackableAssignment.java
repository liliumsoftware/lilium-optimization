package ir.baho.algorithm.assignment;

import ir.baho.algorithm.calculation.CostCalculation;
import ir.baho.algorithm.domain.Planable;
import ir.baho.algorithm.domain.Trackable;
import ir.baho.algorithm.domain.TrackableCategory;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Assigns resources (trackables) to plans and repairs those assignments. The constructor expands each input
 * trackable's {@link Trackable#getCount()} into that many indexed copies, so a resource standing for N
 * interchangeable units becomes N assignable units. The various {@code assign} overloads cover assigning a
 * whole set, one plan against a fixed set, or against an explicit sub-pool; the richest one runs
 * relocate → exchange → reassign repair passes to fill unassigned plans and swap resources toward lower cost. The
 * list-based overloads offer each plan the pool sorted by {@link #comparator(Planable)}. The repair passes end once a
 * pass accepts no move and never consult the plans' {@code equals}, so plans compared by value, by code or by identity
 * are repaired alike; every accepted move also counts against a cap, so an overridden {@code compare…} hook that
 * accepts ties cannot loop forever.
 *
 * <p><strong>Concurrency.</strong> {@link #assign(List, List)} is {@code synchronized} because callers may hand it
 * overlapping plan sets. The library's zone-group entry points (see {@code ZoneGroups}) first run the groups in
 * parallel, each limited by {@link #withoutIdleUnits} to the units its own plans hold, so the groups never compete
 * for a unit; a second, ordered pass then lets each group that wanted one ({@link #wantedIdleUnits}) use the idle
 * units the earlier groups left.
 *
 * @param <T> the resource type being assigned
 */
@Slf4j
public abstract class TrackableAssignment<P extends Planable<P, ?, T>, T extends Trackable<T>> {

    /**
     * Upper bound on the moves one repair loop accepts; only reached when an overridden {@code compare…} hook keeps
     * accepting moves that never converge (for example by accepting ties).
     */
    private static final int MAX_MOVES = 100_000;

    @Getter
    protected final List<T> trackables;
    protected List<T> currentTrackables;

    /**
     * Expands each resource into {@link Trackable#getCount()} indexed copies ({@code setIndex(0 .. count-1)}) and
     * makes them the initial pool.
     *
     * @param trackables the fleet; must not contain {@code null}
     */
    public TrackableAssignment(List<T> trackables) {
        this.trackables = trackables.stream().map(t -> IntStream.range(0, t.getCount())
                .mapToObj(value -> {
                    T copy = t.copy();
                    copy.setIndex(value);
                    return copy;
                }).toList()).flatMap(Collection::stream).toList();
        this.currentTrackables = new ArrayList<>(this.trackables);
    }

    /**
     * Assigns every plan, in list order, from the current pool, withdrawing each unit as it is taken.
     *
     * @return {@code false} as soon as a plan cannot be assigned (earlier plans keep their units)
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
     * Assigns {@code plan} from the units not held by {@code plans}; refuses a plan already in that list.
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
     * Assigns {@code plans} from the pool left after {@code otherPlans} (assumed already assigned) claim
     * theirs, then runs relocate/exchange/reassign repair; every repair pass draws only from that same pool, so a
     * unit held by {@code otherPlans} is never handed to one of {@code plans}; for a list made by
     * {@link #withoutIdleUnits} the idle units are out of reach as well. Synchronized because zone groups run it
     * concurrently.
     *
     * @return {@code false} if a plan remained unassignable after relocation, {@code true} otherwise
     */
    public synchronized boolean assign(List<P> otherPlans, List<P> plans) {
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
     * Domain hook: assigns {@code trackable} to {@code plan} when it fits; a refusal must leave the plan without a
     * resource.
     *
     * @return {@code true} if the unit was assigned
     */
    protected abstract boolean assign(P plan, T trackable);

    /**
     * Domain hook: assigns {@code plan} a unit picked from {@code trackables}, offered in the order the caller chose
     * (sorted by {@link #comparator(Planable)} in the list-based overloads); a refusal must leave the plan without a
     * resource. The list is a private, modifiable working copy: the hook may reorder it but must pick from it.
     *
     * @return {@code true} if a unit was assigned
     */
    protected abstract boolean assign(P plan, List<T> trackables);

    /**
     * Like {@link #assign(List)} (every plan in list order, greedily, no repair passes) but drawing only from the
     * units {@code otherPlans} do not hold, and for a list made by {@link #withoutIdleUnits} not from the idle units
     * either. With a plain, empty {@code otherPlans} it is exactly {@link #assign(List)}. The balancing algorithms use
     * it to keep the units of other zone groups out of reach.
     *
     * @return {@code false} as soon as a plan cannot be assigned (earlier plans keep their units)
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
     * Whether an assignment against {@code otherPlans}, a list made by {@link #withoutIdleUnits}, was ever refused or
     * left more expensive for want of an idle unit: a plan stayed without a resource that an idle unit accepts, or an
     * idle unit of another category would have made a plan cheaper ({@link #compareReassign}). Only such a group can
     * gain from the idle units, so {@code ZoneGroups} gives only these groups their ordered second pass. {@code false}
     * for any other list.
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
     * idle unit out of reach: a unit of the current pool that neither {@code otherPlans} nor {@code plans} holds now.
     * Plans assigned against it can only trade the units {@code plans} already hold, so groups of plans that are
     * optimized concurrently, each against such a list, never compete for a unit. The returned list is a read-only
     * snapshot holding the plans of {@code otherPlans}.
     */
    public List<P> withoutIdleUnits(List<P> otherPlans, List<P> plans) {
        List<T> idle = new ArrayList<>(this.currentTrackables);
        idle.removeAll(otherPlans.stream().map(P::getTrackable).toList());
        idle.removeAll(plans.stream().map(P::getTrackable).toList());
        return new HeldUnitsOnly<>(List.copyOf(otherPlans), List.copyOf(idle));
    }

    /**
     * Whether the current pool holds a unit no plan of {@code plans} holds.
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
     * Like {@link #assign(List)} but drawing only from the given sub-pool.
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
     * Like {@link #assign(List, Planable)} but drawing only from the given sub-pool.
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
     * Assigns the specific {@code trackable} to {@code plan}, probing on a copy first so a refused assignment
     * leaves the plan untouched.
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
     * Greedy assignment of every plan followed by the relocate, exchange and reassign repair passes.
     *
     * @return {@code false} if some plan still has no resource after relocation
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
     * Repair pass that hands an assigned resource over to an unassigned plan and finds the donor a new one,
     * repeating until a full pass accepts no move. Termination never consults the plans' {@code equals}: every accepted
     * move strictly increases the number of assigned plans, and every accepted move counts against a cap (100 000,
     * logged once at WARN) in case an overridden {@link #compareRelocate} accepts moves that do not converge.
     *
     * @return {@code true} if every plan ended up with a resource
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
                    List<P> tempPlans = plans.stream().map(P::copy).collect(Collectors.toList());
                    P tempFirstPlan = tempPlans.get(i);
                    P tempSecondPlan = tempPlans.get(j);

                    if (tempSecondPlan.getTrackable() != null
                            && tempFirstPlan.getTrackable() == null
                            && assign(tempFirstPlan, tempSecondPlan.getTrackable())
                            && assign(tempSecondPlan, trackables.stream().filter(t -> tempPlans.stream()
                            .noneMatch(p -> t.equals(p.getTrackable()))).collect(Collectors.toList()))
                            && tempSecondPlan.getTrackable() != null
                            && compareRelocate(tempPlans, plans)) {
                        plans.clear();
                        plans.addAll(tempPlans);
                        moved = true;
                        moves++;
                        log.trace(getMessage(firstPlan, secondPlan));
                    } else if (tempFirstPlan.getTrackable() != null
                            && tempSecondPlan.getTrackable() == null
                            && assign(tempSecondPlan, tempFirstPlan.getTrackable())
                            && assign(tempFirstPlan, trackables.stream().filter(t -> tempPlans.stream()
                            .noneMatch(p -> t.equals(p.getTrackable()))).collect(Collectors.toList()))
                            && tempFirstPlan.getTrackable() != null
                            && compareRelocate(tempPlans, plans)) {
                        plans.clear();
                        plans.addAll(tempPlans);
                        moved = true;
                        moves++;
                        log.trace(getMessage(firstPlan, secondPlan));
                    }
                }
            }
            if (!moved) {
                return plans.stream().allMatch(p -> p.getTrackable() != null);
            }
        }
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
     * Repair pass that swaps the resources of two plans in different categories whenever the summed cost drops,
     * repeating until a pass accepts no swap. Terminates because every accepted swap strictly lowers the total cost;
     * accepted swaps count against the same cap as {@link #relocate}, and the plans' {@code equals} is never consulted.
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
                        log.trace(getMessage(firstPlan, secondPlan));
                    }
                }
            }
        } while (moved);
    }

    /**
     * {@link #reassign(List, List)} drawing idle resources from the whole current pool.
     */
    protected void reassign(List<P> plans) {
        reassign(plans, this.currentTrackables);
    }

    /**
     * Repair pass that moves a plan onto an idle resource of another category when that is cheaper, restarting
     * the scan after each move and repeating until nothing changes. A resource is idle when it is in
     * {@code trackables} and no plan in {@code plans} holds it; plans without a resource are left alone. Every
     * candidate is probed on a fresh copy of the plan, so a refused or rejected probe never leaks into the next
     * one. Terminates because every accepted move strictly lowers the plan's cost; a pass without an accepted move
     * ends the repair. An accepted move restarts the scan within the pass, so the cap shared with {@link #relocate}
     * counts every accepted move: an overridden {@link #compareReassign} that accepts ties cannot loop forever.
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
                        log.trace(getMessage(plan, newPlan));
                        i = -1;
                        break;
                    }
                }
            }
        } while (moved);
    }

    /**
     * Whether {@code newPlans} is the better relocation outcome: more plans hold a resource.
     */
    protected boolean compareRelocate(List<P> newPlans, List<P> oldPlans) {
        return newPlans.stream().filter(p -> p.getTrackable() != null).count() > oldPlans.stream()
                .filter(p -> p.getTrackable() != null).count();
    }

    /**
     * Whether the swap that produced {@code newPlans} is accepted: the summed cost dropped.
     */
    protected boolean compareExchange(P firstPlan, P secondPlan, List<P> newPlans, List<P> oldPlans) {
        return CostCalculation.totalCost(newPlans) < CostCalculation.totalCost(oldPlans);
    }

    /**
     * Whether {@code newPlan}, on its idle resource, beats {@code plan}: its cost dropped.
     */
    protected boolean compareReassign(P plan, P newPlan) {
        return newPlan.getCost() < plan.getCost();
    }

    /**
     * The trace message logged for an accepted repair move.
     */
    protected String getMessage(P firstPlan, P secondPlan) {
        return "Changed trackables";
    }

    /**
     * The preference order of the pool for {@code plan}; the first fitting unit in it is taken.
     */
    protected abstract Comparator<T> comparator(P plan);

    /**
     * The category of {@code trackable}; the exchange and reassign passes only move plans across categories.
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
