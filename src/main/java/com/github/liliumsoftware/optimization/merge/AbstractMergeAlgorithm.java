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
import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.calculation.CostCalculation;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.domain.Planable;
import com.github.liliumsoftware.optimization.domain.Trackable;
import com.github.liliumsoftware.optimization.exception.CancelException;
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Fuses two plans into one whenever that lowers the total cost and the resources can still be assigned.
 *
 * <p>A merge of a first plan with a second plan, built on copies of the whole list, drops the first plan's destination
 * and the second plan's origin and appends the second plan's remaining stops to the first; when the plans differ in
 * shape, a depot stop the merged route would otherwise lose (the second plan's origin, the first plan's destination)
 * is kept. The merged plan goes through the local search, the second plan is removed, and the resources of the
 * shrunken set are re-assigned with {@link TrackableAssignment#assignAndExchange}. The candidate is accepted only if
 * that assignment succeeds and {@link #compare} reports a strict improvement. By default only plans in neighbouring
 * zones are merged ({@link #supports(Planable, Planable)}).
 *
 * <p>{@link #apply(List)} scans the unordered plan pairs, tries both orientations of each and restarts from the first
 * pair after every accepted merge. It terminates whatever {@link #compare} does, because every accepted merge removes
 * a plan from the list. The list passed in is mutated by replacing its contents; the caller's plan objects are never
 * modified. {@link #cancel()} aborts with a {@link CancelException}; {@link #interrupt()} is polled before every pair
 * and returns the list with the merges accepted so far. Both signals are forwarded to the local search. The class
 * holds no mutable state beyond the signal flags, so one instance may run on independent lists concurrently.
 *
 * @param <P> the plan type
 * @param <N> the node type
 * @param <T> the trackable (resource) type
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractMergeAlgorithm<P
        extends Planable<P, N, T>, N extends Nodeable<N>, T extends Trackable<T>> extends BaseAlgorithm {

    /**
     * Supplies the zone neighbourhood used by {@link #supports(Planable, Planable)}.
     */
    protected final PathCostCalculation<P, N, ?> costCalculation;
    /**
     * Re-assigns the resources of every candidate plan set; a merge whose plans cannot all be assigned is rejected.
     */
    protected final TrackableAssignment<P, T> trackableAssignment;
    /**
     * Improves every merged plan before the merge is judged.
     */
    protected final LocalSearchAlgorithm<P> localSearchAlgorithm;

    /**
     * Scans every unordered plan pair in list order, merging the first pair that improves the cost, and restarts from
     * the first pair after each accepted merge, until a full scan merges nothing or the algorithm is interrupted.
     * Plans rejected by {@link #supports(Planable)} are skipped.
     *
     * @param plans the plans to merge; a mutable list owned by the caller, whose contents are replaced by the merged
     *              copies (the plan objects themselves are not modified)
     * @return {@code plans}
     * @throws CancelException     if the search was cancelled
     * @throws ArithmeticException if a summed cost does not fit a {@code long}
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
     * Tries to merge the pair in both orientations, since concatenation is directional: {@code secondPlan} appended to
     * {@code firstPlan}, then, if that was not accepted, {@code firstPlan} appended to {@code secondPlan}.
     *
     * @param plans      the plans being merged; its contents are replaced when a merge is accepted
     * @param firstPlan  an element of {@code plans}
     * @param secondPlan another element of {@code plans}
     * @return {@code true} if a merge was accepted and {@code plans} changed
     * @throws CancelException     if the search was cancelled
     * @throws ArithmeticException if a summed cost does not fit a {@code long}
     */
    protected boolean apply(List<P> plans, P firstPlan, P secondPlan) {
        if (firstPlan.equals(secondPlan)) {
            return false;
        }
        if (!this.supports(firstPlan) || !this.supports(secondPlan)) {
            return false;
        }

        if (merge(plans, firstPlan, secondPlan)) {
            return true;
        }
        return merge(plans, secondPlan, firstPlan);
    }

    /**
     * Builds the merge of {@code secondPlan} into {@code firstPlan} on copies of the whole list (see the class
     * description), re-assigns the resources and, if the assignment succeeds and {@link #compare} prefers the
     * candidate, replaces the contents of {@code plans} with it. Nothing is changed when
     * {@link #supports(Planable, Planable)} rejects the pair or the candidate is not accepted.
     *
     * @param plans      the plans being merged
     * @param firstPlan  the element of {@code plans} that absorbs the other
     * @param secondPlan the element of {@code plans} that is absorbed and removed
     * @return {@code true} if the merge was accepted and {@code plans} changed
     * @throws CancelException     if the local search was cancelled
     * @throws ArithmeticException if a summed cost does not fit a {@code long}
     */
    protected boolean merge(List<P> plans, P firstPlan, P secondPlan) {
        if (supports(firstPlan, secondPlan)) {
            List<P> tempPlans = plans.stream().map(P::copy).collect(Collectors.toList());
            P tempFirstPlan = tempPlans.get(plans.indexOf(firstPlan));
            P tempSecondPlan = tempPlans.get(plans.indexOf(secondPlan));

            N firstDestination = tempFirstPlan.getDestination();
            N secondOrigin = tempSecondPlan.getOrigin();
            if (firstDestination != null) {
                tempFirstPlan.getNodes().remove(firstDestination);
            }
            if (secondOrigin != null) {
                tempSecondPlan.getNodes().remove(secondOrigin);
            }
            tempFirstPlan.getNodes().addAll(tempSecondPlan.getNodes());
            // Plans of different shapes: keep the depot stops the merged route would otherwise lose.
            if (secondOrigin != null && tempFirstPlan.getOrigin() == null) {
                tempFirstPlan.getNodes().addFirst(secondOrigin);
            }
            if (firstDestination != null && tempFirstPlan.getDestination() == null) {
                tempFirstPlan.getNodes().add(firstDestination);
            }
            tempPlans.set(plans.indexOf(firstPlan), localSearchAlgorithm.apply(tempFirstPlan));

            tempPlans.remove(tempSecondPlan);
            if (trackableAssignment.assignAndExchange(tempPlans) && compare(tempPlans, plans)) {
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
     * Whether {@code plan} may take part in a merge at all. The default implementation returns {@code true}.
     *
     * @param plan an element of the list being merged
     * @return {@code true} if {@code plan} may be merged
     */
    protected boolean supports(P plan) {
        return true;
    }

    /**
     * Whether {@code secondPlan} may be merged into {@code firstPlan}. The default implementation requires
     * {@code secondPlan}'s zone to be a neighbour of {@code firstPlan}'s ({@link PathCostCalculation#isNeighbor}).
     *
     * @param firstPlan  the plan that would absorb the other
     * @param secondPlan the plan that would be absorbed
     * @return {@code true} if the merge may be built and judged
     */
    protected boolean supports(P firstPlan, P secondPlan) {
        return costCalculation.isNeighbor(firstPlan.getZone(), secondPlan.getZone());
    }

    /**
     * Whether the merged set {@code newPlans} is accepted over {@code oldPlans}. The default implementation accepts a
     * strictly lower {@link CostCalculation#totalCost summed cost}.
     *
     * @param newPlans the candidate set, one plan shorter than {@code oldPlans}
     * @param oldPlans the current list
     * @return {@code true} to accept {@code newPlans}
     * @throws ArithmeticException if a summed cost does not fit a {@code long} (default implementation)
     */
    protected boolean compare(List<P> newPlans, List<P> oldPlans) {
        return CostCalculation.totalCost(newPlans) < CostCalculation.totalCost(oldPlans);
    }

    /**
     * The message logged at DEBUG level for an accepted merge; only called when DEBUG is enabled. The default
     * implementation returns {@code "Merge plans"}.
     *
     * @param firstPlan  the plan that absorbed the other (the original element, not the merged copy)
     * @param secondPlan the plan that was absorbed
     * @return the log message
     */
    protected String getMessage(P firstPlan, P secondPlan) {
        return "Merge plans";
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
