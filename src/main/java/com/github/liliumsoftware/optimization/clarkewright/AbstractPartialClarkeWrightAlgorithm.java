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

package com.github.liliumsoftware.optimization.clarkewright;

import com.github.liliumsoftware.optimization.OptimizationType;
import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.domain.Planable;
import com.github.liliumsoftware.optimization.domain.Trackable;
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Clarke-Wright construction of the customers an existing plan set does not yet cover, on the part of the fleet that
 * set leaves free.
 *
 * <p>{@link #apply} first plans the not-yet-covered customers with the inherited {@link #plan} on the units of the
 * assignment's current pool that no plan holds (the "partial" pool) and keeps the result when it plans anything. Only
 * when it plans nothing (or no unit is free) does it try resource hand-overs: it moves a resource-holding plan from its
 * unit to a unit no plan holds, which frees the first unit, and then plans the not-yet-covered customers on the units
 * no plan holds (including the freed one). The first hand-over whose re-planning yields at least one plan is kept —
 * costs are not compared — and the search restarts; it ends when no hand-over yields a new plan. The purpose is to
 * serve more customers with the units the existing plans leave idle, not to reduce cost or the number of resources
 * used.
 *
 * <p>All merge and assignment steps of the inherited construction go through the {@code withTrackables} variants of
 * {@link TrackableAssignment}, so they only ever draw from the partial pool rather than the full fleet. Outside
 * {@link #apply} (a direct call of {@link #plan}) the pool is the assignment's current pool
 * ({@link TrackableAssignment#getCurrentTrackables()}), as for the full construction.
 *
 * <p><strong>Thread-safety.</strong> The partial pool lives in a thread-local for the duration of one {@link #apply}
 * call and is handed explicitly to the parallel sweep workers, so concurrent {@code apply} calls on one instance with
 * independent inputs cannot see each other's pool.
 *
 * @param <P> the plan/route type
 * @param <N> the node/stop type
 * @param <T> the resource type
 */
@Slf4j
public abstract class AbstractPartialClarkeWrightAlgorithm<P extends Planable<P, N, T>, N extends Nodeable<N>,
        T extends Trackable<T>> extends AbstractClarkeWrightAlgorithm<P, N> {

    private final TrackableAssignment<P, T> trackableAssignment;
    /**
     * The restricted pool of the {@link #apply} call running on the current thread. Thread-local rather than an
     * instance field so that independent {@code apply} calls on a shared instance cannot see each other's pool.
     * The λ/β sweep runs on parallel-stream worker threads that do not see this thread-local, so
     * {@link #getPlans} reads the pool once on the calling thread and hands it to each worker explicitly.
     */
    private final ThreadLocal<List<T>> trackables = new ThreadLocal<>();

    /**
     * Zone-restricted construction ({@link OptimizationType#LOCATION_ZONE}) with the classic saving ({@code λ = 1}, no
     * β).
     *
     * @param costCalculation      the cost model; its matrix must be initialized before planning
     * @param trackableAssignment  the resource assignment; its expanded fleet ({@code getTrackables()}) is the
     *                             set of units hand-overs choose from
     * @param localSearchAlgorithm the local search applied to merged plans
     * @throws NullPointerException if any argument is {@code null}
     */
    public AbstractPartialClarkeWrightAlgorithm(PathCostCalculation<P, N, ?> costCalculation,
            TrackableAssignment<P, T> trackableAssignment,
            LocalSearchAlgorithm<P> localSearchAlgorithm) {
        super(costCalculation, trackableAssignment, localSearchAlgorithm);
        this.trackableAssignment = trackableAssignment;
    }

    /**
     * Construction with the classic saving ({@code λ = 1}, no β) under the given zone policy.
     *
     * @param costCalculation      the cost model; its matrix must be initialized before planning
     * @param trackableAssignment  the resource assignment; its expanded fleet ({@code getTrackables()}) is the
     *                             set of units hand-overs choose from
     * @param localSearchAlgorithm the local search applied to merged plans
     * @param optimizationType     the zone policy
     * @throws NullPointerException if any argument is {@code null}
     */
    public AbstractPartialClarkeWrightAlgorithm(PathCostCalculation<P, N, ?> costCalculation,
            TrackableAssignment<P, T> trackableAssignment,
            LocalSearchAlgorithm<P> localSearchAlgorithm,
            OptimizationType optimizationType) {
        super(costCalculation, trackableAssignment, localSearchAlgorithm, optimizationType);
        this.trackableAssignment = trackableAssignment;
    }

    /**
     * Construction sweeping the given λ values without a β term.
     *
     * @param costCalculation      the cost model; its matrix must be initialized before planning
     * @param trackableAssignment  the resource assignment; its expanded fleet ({@code getTrackables()}) is the
     *                             set of units hand-overs choose from
     * @param localSearchAlgorithm the local search applied to merged plans
     * @param optimizationType     the zone policy
     * @param lambdas              the λ values to sweep; copied
     * @throws NullPointerException     if any argument or λ is {@code null}
     * @throws IllegalArgumentException if {@code lambdas} is empty or any λ is not a finite number
     */
    public AbstractPartialClarkeWrightAlgorithm(PathCostCalculation<P, N, ?> costCalculation,
            TrackableAssignment<P, T> trackableAssignment,
            LocalSearchAlgorithm<P> localSearchAlgorithm,
            OptimizationType optimizationType, List<Double> lambdas) {
        super(costCalculation, trackableAssignment, localSearchAlgorithm, optimizationType, lambdas);
        this.trackableAssignment = trackableAssignment;
    }

    /**
     * Full λ × β sweep; see {@link AbstractClarkeWrightAlgorithm#AbstractClarkeWrightAlgorithm(PathCostCalculation,
     * TrackableAssignment, LocalSearchAlgorithm, OptimizationType, List, List)} for the parameter semantics.
     *
     * @param costCalculation      the cost model; its matrix must be initialized before planning
     * @param trackableAssignment  the resource assignment; its expanded fleet ({@code getTrackables()}) is the
     *                             set of units hand-overs choose from
     * @param localSearchAlgorithm the local search applied to merged plans
     * @param optimizationType     the zone policy
     * @param lambdas              the λ values to sweep, in tie-breaking order; copied
     * @param betas                the β values to sweep, possibly empty; copied
     * @throws NullPointerException     if any argument, λ or β is {@code null}
     * @throws IllegalArgumentException if {@code lambdas} is empty, or any λ or β is not a finite number
     */
    public AbstractPartialClarkeWrightAlgorithm(PathCostCalculation<P, N, ?> costCalculation,
            TrackableAssignment<P, T> trackableAssignment,
            LocalSearchAlgorithm<P> localSearchAlgorithm,
            OptimizationType optimizationType,
            List<Double> lambdas, List<Double> betas) {
        super(costCalculation, trackableAssignment, localSearchAlgorithm, optimizationType, lambdas, betas);
        this.trackableAssignment = trackableAssignment;
    }

    /**
     * Plans the customers {@code plans} does not cover on the units it leaves free, first directly and then, if that
     * plans nothing, through resource hand-overs.
     *
     * <p><strong>Free units first.</strong> When some customers of {@code customers} are not served by {@code plans}
     * (matched by code), they are first planned with {@link #plan} on the units of the assignment's current pool
     * ({@link TrackableAssignment#getCurrentTrackables()}) that no plan of {@code plans} holds. If that yields at least
     * one plan, the new plans are appended to {@code plans} (the existing plans are not touched) and {@code plans} is
     * returned; customers the new plans cannot serve stay unplanned. Otherwise (or when no unit is free, or nothing is
     * uncovered) the hand-over search below runs.
     *
     * <p><strong>Hand-overs.</strong> For each ordered pair of distinct units {@code (i, j)} of the expanded fleet
     * ({@code TrackableAssignment.getTrackables()}), in fleet order: if a plan holds unit {@code i}, no plan holds unit
     * {@code j} and {@link TrackableAssignment#assignWithTrackable} gives unit {@code j} to that plan, then the
     * customers of {@code customers} whose code no plan serves are planned with {@link #plan} on the units no plan
     * holds afterwards (unit {@code i} among them). All of this happens on deep copies of {@code plans}. If the
     * re-planning returns at least one plan, the contents of {@code plans} are replaced by the copies followed by the
     * new plans and the scan restarts from the first pair; the result is accepted whatever its cost. Otherwise the
     * attempt is discarded. Each accepted hand-over adds at least one resource-holding plan, so the scan ends once no
     * pair yields a new plan. When no plan holds a resource (or the fleet has fewer than two units) the hand-over
     * search leaves {@code plans} unchanged. Plans without a resource are carried along (as copies after an accepted
     * hand-over); their customers count as covered. Customers the new plans cannot serve stay unplanned.
     *
     * <p>{@code cancel()} aborts with a {@link com.github.liliumsoftware.optimization.exception.CancelException};
     * {@code interrupt()} stops further hand-overs (and skips the free-unit step when it arrives before it) and returns
     * the plans as they are (a re-planning in progress, being construction, completes).
     *
     * @param origin    the depot the new plans start from
     * @param customers every customer that should be served, covered or not; not modified
     * @param plans     the existing plans; the new plans are appended to it, and its contents are replaced on every
     *                  accepted hand-over
     * @return {@code plans} itself
     * @throws IllegalArgumentException if two customers share a code
     * @throws com.github.liliumsoftware.optimization.exception.CancelException if the algorithm is cancelled or the
     *     running thread is interrupted
     * @throws ArithmeticException      if the total cost of a candidate plan set does not fit a {@code long}
     */
    public List<P> apply(N origin, List<N> customers, List<P> plans) {
        requireDistinctCodes(customers);
        try {
            return doApply(origin, customers, plans);
        } finally {
            this.trackables.remove();
        }
    }

    private List<P> doApply(N origin, List<N> customers, List<P> plans) {
        List<N> unplanned = getUnplannedNodes(plans, customers);
        // An interrupt skips this step like a hand-over: the plans are returned as they are.
        if (!unplanned.isEmpty() && !isInterrupted()) {
            // Free units first: the units no plan holds may serve the uncovered customers without any hand-over.
            List<T> free = trackableAssignment.getCurrentTrackables().stream()
                    .filter(t -> plans.stream().noneMatch(p -> t.equals(p.getTrackable())))
                    .collect(Collectors.toList());
            if (!free.isEmpty()) {
                this.trackables.set(free);
                List<P> newPlans = plan(origin, unplanned);
                if (!newPlans.isEmpty()) {
                    plans.addAll(newPlans);
                    if (log.isTraceEnabled()) {
                        log.trace(getMessage(plans));
                    }
                    return plans;
                }
            }
        }
        List<T> trackables = trackableAssignment.getTrackables();
        for (int i = 0; i < trackables.size(); i++) {
            int index = i;
            for (int j = 0; j < trackables.size(); j++) {
                if (i == j) {
                    continue;
                }
                checkCancel();
                // A hand-over is an improvement step: an interrupt stops further hand-overs, keeping the plans as
                // they are.
                if (isInterrupted()) {
                    return plans;
                }
                List<P> tempPlans = plans.stream().map(P::copy).collect(Collectors.toList());
                Optional<P> plan = tempPlans.stream().filter(p -> trackables.get(index).equals(p.getTrackable()))
                        .findAny();
                T freeTrackable = trackables.get(j);
                if (plan.isPresent() && tempPlans.stream().noneMatch(p -> freeTrackable.equals(p.getTrackable()))
                        && trackableAssignment.assignWithTrackable(plan.get(), freeTrackable)) {
                    this.trackables.set(trackables.stream().filter(t -> tempPlans.stream()
                            .noneMatch(p -> t.equals(p.getTrackable()))).collect(Collectors.toList()));
                    List<P> newPlans = plan(origin, getUnplannedNodes(tempPlans, customers));
                    if (!newPlans.isEmpty()) {
                        plans.clear();
                        plans.addAll(tempPlans);
                        plans.addAll(newPlans);
                        if (log.isTraceEnabled()) {
                            log.trace(getMessage(plans));
                        }
                        i = -1;
                        break;
                    }
                }
            }
        }
        return plans;
    }

    /**
     * Same grid as the parent, but every worker merges against the pool captured on the calling thread
     * ({@link #partialTrackables()}), since the workers cannot see the caller's thread-local.
     *
     * @param origin    the depot
     * @param customers the customers the savings are formed over
     * @param plans     the plans to merge; not modified
     * @return one outcome per grid point, in configuration order
     * @throws com.github.liliumsoftware.optimization.exception.CancelException if the algorithm is cancelled
     */
    @Override
    protected List<PlanList<P>> getPlans(N origin, List<N> customers, List<P> plans) {
        List<T> pool = partialTrackables();
        List<P> fixed = fixedPlans();
        Function<N, N> routeStarts = getRouteStarts(origin, plans);
        Function<N, N> routeEnds = getRouteEnds(origin, plans);
        if (betas.isEmpty()) {
            return lambdas.parallelStream().map(l -> new PlanList<>(withFixedPlans(fixed, () -> mergePlans(
                    plans.stream().map(Planable::copy).collect(Collectors.toList()),
                    getSavings(origin, customers, routeStarts, routeEnds, l, 0), pool)), new LambdaBeta(l))).toList();
        }
        return grid().parallelStream().map(lb -> new PlanList<>(withFixedPlans(fixed, () -> mergePlans(
                plans.stream().map(Planable::copy).collect(Collectors.toList()),
                getSavings(origin, customers, routeStarts, routeEnds, lb.lambda(), lb.beta()), pool)), lb)).toList();
    }

    /**
     * The parent's merge walk on the partial pool of the current thread ({@link #partialTrackables()}).
     *
     * @param plans   the plans to merge; its contents are replaced on every accepted merge
     * @param savings the savings in the order to try them; cleared once walked
     * @return a new list of the plans holding a resource
     * @throws com.github.liliumsoftware.optimization.exception.CancelException if the algorithm is cancelled
     */
    @Override
    protected List<P> mergePlans(List<P> plans, List<Saving> savings) {
        return mergePlans(plans, savings, partialTrackables());
    }

    /**
     * The parent's greedy merge walk (see {@link AbstractClarkeWrightAlgorithm#mergePlans(List, List)}), assigning
     * only from {@code pool}: through {@link TrackableAssignment#assignWithTrackables(List, List)} for each merge
     * attempt and {@link TrackableAssignment#assignWithTrackables(List, Planable, List)} for the leftover plans. The
     * units held by the fixed previous plans of the running {@link #plan} call (see
     * {@link AbstractClarkeWrightAlgorithm#plan}) are taken out of {@code pool} first, so they are never handed out.
     *
     * @param plans   the plans to merge; its contents are replaced on every accepted merge
     * @param savings the savings in the order to try them; cleared once walked
     * @param pool    the units resources may be drawn from; not modified
     * @return a new list of the plans holding a resource
     * @throws com.github.liliumsoftware.optimization.exception.CancelException if the algorithm is cancelled
     */
    protected List<P> mergePlans(List<P> plans, List<Saving> savings, List<T> pool) {
        List<T> free = withoutUnitsOf(pool, fixedPlans());
        return mergePlans(plans, savings, merged -> trackableAssignment.assignWithTrackables(merged, free),
                (kept, plan) -> trackableAssignment.assignWithTrackables(kept, plan, free));
    }

    /**
     * Probes on the partial pool of the current thread, without the units of {@code fixed}, as the merge walk assigns.
     */
    @Override
    boolean canTakePart(List<P> plans, List<P> fixed) {
        return trackableAssignment.assignWithTrackables(plans, withoutUnitsOf(partialTrackables(), fixed));
    }

    /**
     * {@code pool} without the units {@code plans} hold; {@code pool} itself when no plan holds one of them.
     */
    private List<T> withoutUnitsOf(List<T> pool, List<P> plans) {
        if (plans.isEmpty()) {
            return pool;
        }
        List<T> free = new ArrayList<>(pool);
        free.removeAll(plans.stream().map(P::getTrackable).toList());
        return free;
    }

    /**
     * The restricted pool the current thread's {@link #apply} call narrowed the fleet to, or the assignment's current
     * pool ({@link TrackableAssignment#getCurrentTrackables()}) when the inherited {@link #plan} is invoked on its own
     * (outside {@code apply}) — the same units the full construction would draw from, so a pool a second-dispatch
     * assignment has narrowed is respected.
     *
     * @return the units no plan of the current {@code apply} step holds, or
     *     {@code TrackableAssignment.getCurrentTrackables()}
     */
    protected List<T> partialTrackables() {
        List<T> pool = trackables.get();
        return pool == null ? trackableAssignment.getCurrentTrackables() : pool;
    }

    /**
     * The trace message logged after an accepted hand-over; only called when trace logging is enabled.
     *
     * @param plans the plans after the hand-over
     * @return the message
     */
    protected String getMessage(List<P> plans) {
        return "PartialClarkeWright " + plans.size() + " plans";
    }

    /**
     * The customers not yet served by {@code plans}, matched by code so a consumer whose node {@code copy()} is not
     * {@code equals} to its source never has a served customer planned twice.
     *
     * @param plans     the current plans; their customers count as served
     * @param customers every customer that should be served; not modified
     * @return a new list of the unserved customers, in {@code customers} order
     */
    protected List<N> getUnplannedNodes(List<P> plans, List<N> customers) {
        Set<String> plannedCodes = plans.stream().flatMap(p -> p.getCustomers().stream()).map(Nodeable::getCode)
                .collect(Collectors.toSet());
        List<N> nodes = new ArrayList<>(customers);
        nodes.removeIf(n -> plannedCodes.contains(n.getCode()));
        return nodes;
    }

}
