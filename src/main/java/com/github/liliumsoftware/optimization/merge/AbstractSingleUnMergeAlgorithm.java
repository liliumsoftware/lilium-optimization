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

package com.github.liliumsoftware.optimization.merge;

import com.github.liliumsoftware.optimization.BaseAlgorithm;
import com.github.liliumsoftware.optimization.OptimizationType;
import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.domain.Planable;
import com.github.liliumsoftware.optimization.exception.CancelException;
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Separates plans along a single zone boundary: when one plan serves exactly one real zone that another plan also
 * serves, the other plan's customers in that zone are moved onto the single-zone plan (appended in front of its
 * destination, if any), which may empty and remove the other plan. Both plans then go through the local search, and
 * the move is committed when the resources of the whole list can still be assigned
 * ({@link TrackableAssignment#assign(List)}); there is no cost check.
 *
 * <p>Only real zones qualify: a plan whose customers carry no zone or only the default {@code "NoZone"} of
 * {@link Nodeable#getZone()} never receives customers this way (otherwise every pair of plans would look like "one
 * zone, shared" and be folded together), and nothing is un-merged under {@link OptimizationType#NO_RESTRICTION}.
 *
 * <p>{@link #apply(List)} scans the unordered plan pairs, tries both orientations of each and restarts from the first
 * pair after every accepted un-merge. It terminates whatever the costs do, because every accepted un-merge removes a
 * zone from the other plan while the single-zone plan keeps its one zone, so the number of (plan, zone) pairs strictly
 * drops. The list passed in is mutated by replacing its contents; the caller's plan objects are never modified.
 * {@link #cancel()} aborts with a {@link CancelException}; {@link #interrupt()} is polled before every pair and returns
 * the list with the un-merges accepted so far. Both signals are forwarded to the local search. The class holds no
 * mutable state beyond the signal flags, so one instance may run on independent lists concurrently.
 *
 * @param <P> the plan type
 * @param <N> the node type
 */
@Slf4j
public abstract class AbstractSingleUnMergeAlgorithm<P
        extends Planable<P, N, ?>, N extends Nodeable<N>> extends BaseAlgorithm {

    /**
     * The zone {@link Nodeable#getZone()} reports by default, i.e. "no zone".
     */
    private static final String DEFAULT_ZONE = "NoZone";
    /**
     * Assigns the resources of every candidate plan set; an un-merge whose plans cannot all be assigned is rejected.
     */
    protected final TrackableAssignment<P, ?> trackableAssignment;
    /**
     * Improves the plans touched by an un-merge before it is judged.
     */
    protected final LocalSearchAlgorithm<P> localSearchAlgorithm;
    /**
     * The zone policy of the run; under {@link OptimizationType#NO_RESTRICTION} nothing is un-merged.
     */
    protected final OptimizationType optimizationType;

    /**
     * Creates an un-merge for zone-restricted planning ({@link OptimizationType#LOCATION_ZONE}).
     *
     * @param trackableAssignment  assigns the resources of every candidate plan set
     * @param localSearchAlgorithm improves the touched plans; must not modify its argument
     */
    public AbstractSingleUnMergeAlgorithm(TrackableAssignment<P, ?> trackableAssignment,
            LocalSearchAlgorithm<P> localSearchAlgorithm) {
        this(trackableAssignment, localSearchAlgorithm, OptimizationType.LOCATION_ZONE);
    }

    /**
     * Creates an un-merge for the given zone policy.
     *
     * @param trackableAssignment  assigns the resources of every candidate plan set
     * @param localSearchAlgorithm improves the touched plans; must not modify its argument
     * @param optimizationType     the zone policy of the run; under {@link OptimizationType#NO_RESTRICTION} zones do
     *                             not restrict anything, so no plan is ever un-merged
     */
    public AbstractSingleUnMergeAlgorithm(TrackableAssignment<P, ?> trackableAssignment,
            LocalSearchAlgorithm<P> localSearchAlgorithm,
            OptimizationType optimizationType) {
        this.trackableAssignment = trackableAssignment;
        this.localSearchAlgorithm = localSearchAlgorithm;
        this.optimizationType = optimizationType;
    }

    /**
     * Scans every unordered plan pair in list order, applying the first accepted un-merge and restarting from the first
     * pair, until a full scan changes nothing or the algorithm is interrupted. Plans rejected by
     * {@link #supports(Planable)} are skipped.
     *
     * @param plans the plans to separate; a mutable list owned by the caller, whose contents are replaced by the
     *              changed copies (the plan objects themselves are not modified)
     * @return {@code plans}
     * @throws CancelException if the search was cancelled
     */
    public List<P> apply(List<P> plans) {
        for (int i = 0; i < plans.size(); i++) {
            if (!this.supports(plans.get(i))) {
                continue;
            }
            for (int j = i + 1; j < plans.size(); j++) {
                checkCancel();
                if (isInterrupted()) {
                    return plans;
                }
                if (i == j) {
                    continue;
                }
                if (!this.supports(plans.get(j))) {
                    continue;
                }
                boolean done = apply(plans, plans.get(i), plans.get(j));
                if (done) {
                    i = -1;
                    break;
                }
            }
        }
        return plans;
    }

    /**
     * Tries to un-merge the pair in both orientations: {@code unMerge(plans, firstPlan, secondPlan)}, then, if that
     * was not accepted, {@code unMerge(plans, secondPlan, firstPlan)}.
     *
     * @param plans      the plans being separated; its contents are replaced when an un-merge is accepted
     * @param firstPlan  an element of {@code plans}
     * @param secondPlan another element of {@code plans}
     * @return {@code true} if an un-merge was accepted and {@code plans} changed
     * @throws CancelException if the local search was cancelled
     */
    protected boolean apply(List<P> plans, P firstPlan, P secondPlan) {
        if (firstPlan.equals(secondPlan)) {
            return false;
        }
        if (!this.supports(firstPlan) || !this.supports(secondPlan)) {
            return false;
        }

        if (unMerge(plans, firstPlan, secondPlan)) {
            return true;
        }
        return unMerge(plans, secondPlan, firstPlan);
    }

    /**
     * Moves the customers that {@code secondPlan} has in {@code firstPlan}'s only zone onto {@code firstPlan}, on
     * copies of the whole list; {@code secondPlan} is removed when left without customers. Commits the result by
     * replacing the contents of {@code plans} when the resources can still be assigned. Nothing is changed when
     * {@link #supports(Planable, Planable)} rejects the pair or the assignment fails.
     *
     * @param plans      the plans being separated
     * @param firstPlan  the element of {@code plans} serving a single zone, which receives the customers
     * @param secondPlan the element of {@code plans} that gives up its customers in that zone
     * @return {@code true} if the un-merge was accepted and {@code plans} changed
     * @throws CancelException if the local search was cancelled
     */
    protected boolean unMerge(List<P> plans, P firstPlan, P secondPlan) {
        if (supports(firstPlan, secondPlan)) {
            List<P> tempPlans = plans.stream().map(P::copy).collect(Collectors.toList());
            P tempFirstPlan = tempPlans.get(plans.indexOf(firstPlan));
            P tempSecondPlan = tempPlans.get(plans.indexOf(secondPlan));

            String zone = firstPlan.getCustomers().getFirst().getZone();

            List<N> secondNodes = tempSecondPlan.getCustomers().stream().filter(n -> zone.equals(n.getZone())).toList();
            tempSecondPlan.getNodes().removeAll(secondNodes);

            N firstPlanDestination = tempFirstPlan.getDestination();
            if (firstPlanDestination != null) {
                tempFirstPlan.getNodes().remove(firstPlanDestination);
                tempFirstPlan.getNodes().addAll(secondNodes);
                tempFirstPlan.getNodes().add(firstPlanDestination);
            } else {
                tempFirstPlan.getNodes().addAll(secondNodes);
            }

            tempPlans.set(plans.indexOf(firstPlan), localSearchAlgorithm.apply(tempFirstPlan));
            if (tempSecondPlan.getCustomerCount() != 0) {
                tempPlans.set(plans.indexOf(secondPlan), localSearchAlgorithm.apply(tempSecondPlan));
            } else {
                tempPlans.remove(plans.indexOf(secondPlan));
            }

            if (trackableAssignment.assign(tempPlans)) {
                plans.clear();
                plans.addAll(tempPlans);
                if (log.isDebugEnabled()) {
                    log.debug(getMessage(firstPlan, secondPlan));
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code plan} may take part in an un-merge at all. The default implementation returns {@code true}.
     *
     * @param plan an element of the list being separated
     * @return {@code true} if {@code plan} may be un-merged
     */
    protected boolean supports(P plan) {
        return true;
    }

    /**
     * Whether {@code firstPlan} serves exactly one real zone that {@code secondPlan} also serves; never under
     * {@link OptimizationType#NO_RESTRICTION}, and never for a zone that is {@code null} or the default
     * {@code "NoZone"}.
     *
     * @param firstPlan  the plan that would receive the customers
     * @param secondPlan the plan that would give them up
     * @return {@code true} if the un-merge may be built and tried
     */
    protected boolean supports(P firstPlan, P secondPlan) {
        if (optimizationType == OptimizationType.NO_RESTRICTION) {
            return false;
        }
        Set<String> firstZones = zones(firstPlan);
        Set<String> secondZones = zones(secondPlan);
        if (firstZones.size() != 1) {
            return false;
        }
        String zone = firstZones.iterator().next();
        return zone != null && !DEFAULT_ZONE.equals(zone) && secondZones.contains(zone);
    }

    private Set<String> zones(P plan) {
        Set<String> zones = new HashSet<>();
        plan.getCustomers().forEach(customer -> zones.add(customer.getZone()));
        return zones;
    }

    /**
     * The message logged at DEBUG level for an accepted un-merge; only called when DEBUG is enabled. The default
     * implementation returns {@code "SingleUnMerge plans"}.
     *
     * @param firstPlan  the first plan of the accepted orientation (the original element)
     * @param secondPlan the second plan of the accepted orientation (the original element)
     * @return the log message
     */
    protected String getMessage(P firstPlan, P secondPlan) {
        return "SingleUnMerge plans";
    }

    /**
     * Requests an abort of this algorithm and of its local search; see {@link BaseAlgorithm#cancel()}.
     */
    @Override
    public void cancel() {
        localSearchAlgorithm.cancel();
        super.cancel();
    }

    /**
     * Requests a graceful stop of this algorithm and of its local search; see {@link BaseAlgorithm#interrupt()}.
     */
    @Override
    public void interrupt() {
        localSearchAlgorithm.interrupt();
        super.interrupt();
    }

}
