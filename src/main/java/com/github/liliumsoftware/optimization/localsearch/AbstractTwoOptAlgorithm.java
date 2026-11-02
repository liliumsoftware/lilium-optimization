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

package com.github.liliumsoftware.optimization.localsearch;

import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.domain.Planable;
import com.github.liliumsoftware.optimization.exception.CancelException;
import com.github.liliumsoftware.optimization.feasibility.FeasibilityChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Classic 2-opt for a closed tour: repeatedly reverses the sub-path between two positions and keeps the result whenever
 * it is both strictly cheaper and feasible, restarting the scan from the top on every accepted reversal
 * (first improvement, not best improvement). Runs until a full pass finds no improving reversal: a local optimum, not a
 * global one. O(n&sup2;) candidate pairs per pass, each costed in O(n).
 *
 * <p>The origin always stays first. A trailing {@link Nodeable.Type#DESTINATION} node also stays last; on an
 * <em>open</em> plan (last node a customer) the final stop takes part in reversals like any other customer.
 * {@link AbstractOpenTwoOptAlgorithm} adds relocation moves that are specific to open paths.
 *
 * <p>Candidates are costed with {@link PathCostCalculation#calculateCost(Planable)
 * costCalculation.calculateCost(plan)} (not {@link Planable#getCost()}) and judged by the {@link FeasibilityChecker}.
 * Every candidate is built by {@link #twoOptSwap} on a fresh {@link Planable#copy() copy}, so the plan passed to
 * {@link #apply(Planable)} is never modified. {@link #cancel()} aborts with a {@link CancelException};
 * {@link #interrupt()} is polled at every step of the outer loop and returns the best plan found so far (at worst the
 * input). The class holds no mutable state beyond the signal flags, so one instance may improve independent plans
 * concurrently.
 *
 * @param <P> the plan type
 * @param <N> the node type
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractTwoOptAlgorithm<P
        extends Planable<P, N, ?>, N extends Nodeable<N>> extends LocalSearchAlgorithm<P> {

    /**
     * Costs every candidate; its matrix must contain every stop of the plans to improve.
     */
    protected final PathCostCalculation<P, N, ?> costCalculation;
    /**
     * Judges every cheaper candidate; only feasible candidates are accepted.
     */
    protected final FeasibilityChecker<P> feasibilityChecker;

    /**
     * Improves {@code plan} by segment reversals until no reversal is both strictly cheaper and feasible, or until
     * {@link #interrupt()} is observed. For every position pair {@code (i, j)} admitted by
     * {@link #supports(Planable, int, int)} it reverses the segment {@code [i, j)} of a copy of the current best plan;
     * a trailing destination is never part of a segment.
     *
     * @param plan the plan to improve; not modified
     * @return the best plan found: {@code plan} itself when {@link #supports(Planable)} rejects it or no reversal
     *         improves it, otherwise a plan returned by {@link #twoOptSwap}
     * @throws CancelException       if the search was cancelled or the running thread interrupted
     * @throws ArithmeticException   if a plan's cost does not fit a {@code long}
     * @throws IllegalStateException if the cost matrix is uninitialized or lacks a stop of the plan
     */
    public P apply(P plan) {
        if (!this.supports(plan)) {
            return plan;
        }
        P bestPlan = plan;
        long bestCost = costCalculation.calculateCost(bestPlan);
        // A destination is pinned last (the reversed segment [i, j) must end before it); an open plan's last
        // customer is a legitimate part of a reversal, so j may then reach getCount().
        int lastExclusive = endsWithDestination(bestPlan) ? bestPlan.getCount() - 1 : bestPlan.getCount();
        for (int i = 0; i < bestPlan.getCount(); i++) {
            // Improvement, not construction: an interrupt keeps the best plan found so far (at worst the input).
            if (isInterrupted()) {
                break;
            }
            for (int j = i + 2; j <= lastExclusive; j++) {
                checkCancel();
                if (!this.supports(bestPlan, i, j)) {
                    continue;
                }
                P newPlan = this.twoOptSwap(bestPlan.copy(), i, j);
                long newCost = costCalculation.calculateCost(newPlan);
                if (newCost < bestCost && feasibilityChecker.getResult(newPlan).isFeasible()) {
                    bestPlan = newPlan;
                    bestCost = newCost;
                    i = -1;
                    log.trace("TwoOpt plan: {}", bestPlan);
                    break;
                }
            }
        }
        return bestPlan;
    }

    private boolean endsWithDestination(P plan) {
        return plan.getCount() > 0 && plan.getNodes().getLast().getType() == Nodeable.Type.DESTINATION;
    }

    /**
     * The node sequence of {@code plan} with the segment {@code [i, j)} reversed, as a new list; {@code plan} is not
     * modified. A helper for implementing {@link #twoOptSwap}.
     *
     * @param plan the plan whose nodes are read
     * @param i    the first position of the reversed segment
     * @param j    the exclusive end of the reversed segment; may equal {@code plan.getCount()}
     * @return a new list holding the reordered nodes (the same node instances)
     * @throws IndexOutOfBoundsException if {@code i > j} or either position lies outside the node list
     */
    protected List<N> calculateTwoOptSwap(P plan, int i, int j) {
        List<N> newNodes = new ArrayList<>(plan.getNodes().subList(0, i));
        List<N> middlePart = new ArrayList<>(plan.getNodes().subList(i, j));
        Collections.reverse(middlePart);
        newNodes.addAll(middlePart);
        newNodes.addAll(plan.getNodes().subList(j, plan.getCount()));
        return newNodes;
    }

    /**
     * Builds the candidate with the segment {@code [i, j)} reversed, typically from {@link #calculateTwoOptSwap}. The
     * returned plan is costed with {@code costCalculation.calculateCost(plan)} and, when accepted, becomes the current
     * best plan, so it must keep the plan's resource, zone and any other state the domain relies on.
     *
     * @param plan a fresh copy of the current best plan, owned by this call: the implementation may rebuild and return
     *             it in place
     * @param i    the first position of the reversed segment
     * @param j    the exclusive end of the reversed segment; may equal {@code plan.getCount()} on an open plan
     * @return the candidate plan
     */
    protected abstract P twoOptSwap(P plan, int i, int j);

}
