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
 * 2-opt variant for open (non-returning) plans, where the destination, if any, must stay last. Because an open path
 * has no closing edge, a plain reversal misses improvements that moving a segment to the end of the path catches, so
 * for every position pair {@code (i, j)} admitted by {@link #supports(Planable, int, int)} three candidates are
 * evaluated, all built from the node order of the same current best plan:
 * <ol>
 *   <li>the segment {@code [i, j)} reversed in place;</li>
 *   <li>the segment {@code [i, j)} moved to the end of the path (in front of the destination, if any);</li>
 *   <li>the tail {@code [j, end)} reversed and placed in front of the segment {@code [i, j)}, which again ends the
 *       path.</li>
 * </ol>
 * Of the candidates that are strictly cheaper than that plan and feasible, the step keeps the cheapest (the earliest in
 * the order above on ties). After a step that accepted a candidate the scan restarts from the top (first improvement);
 * it ends when a full pass accepts nothing. The outer position {@code i} runs over the plan's stops
 * ({@code 0 <= i < getCount()}) and {@code j} from {@code i + 2} up to the end of the path (in front of the
 * destination, if any).
 *
 * <p>Candidates are costed with {@link PathCostCalculation#calculateCost(Planable)
 * costCalculation.calculateCost(plan)} (not {@link Planable#getCost()}) and built by {@link #getPlan} on fresh
 * {@link Planable#copy() copies}, so the plan passed to {@link #apply(Planable)} is never modified. {@link #cancel()}
 * aborts with a {@link CancelException}; {@link #interrupt()} is polled at every step of the outer loop and returns
 * the best plan found so far (at worst the input). The class holds no mutable state beyond the signal flags, so one
 * instance may improve independent plans concurrently.
 *
 * @param <P> the plan type
 * @param <N> the node type
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractOpenTwoOptAlgorithm<P
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
     * Improves {@code plan} by reversal and relocation moves (see the class description) until a full pass accepts no
     * candidate, or until {@link #interrupt()} is observed.
     *
     * @param plan the plan to improve; not modified
     * @return the best plan found: {@code plan} itself when {@link #supports(Planable)} rejects it or nothing improves
     *         it, otherwise a plan returned by {@link #getPlan}
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
        N destination = plan.getDestination();
        for (int i = 0; i < bestPlan.getCount(); i++) {
            // Improvement, not construction: an interrupt keeps the best plan found so far (at worst the input).
            if (isInterrupted()) {
                break;
            }
            for (int j = i + 2; j <= (destination == null ? bestPlan.getCount() : bestPlan.getCount() - 1); j++) {
                checkCancel();
                if (!this.supports(bestPlan, i, j)) {
                    continue;
                }
                // All three candidates of a step are built from the same snapshot of the current best plan, and the
                // cheapest feasible one that improves on it wins (the earliest on ties). Each is built from its own
                // copy, so getPlan may rebuild the plan it is given in place (as twoOptSwap may in the closed variant)
                // without touching the caller's plan or the other candidates.
                P snapshot = bestPlan;
                boolean found = false;
                for (int move = 1; move <= 3; move++) {
                    P newPlan = this.getPlan(snapshot.copy(), candidate(move, snapshot.copy(), i, j));
                    long newCost = costCalculation.calculateCost(newPlan);
                    if (newCost < bestCost && feasibilityChecker.getResult(newPlan).isFeasible()) {
                        bestPlan = newPlan;
                        bestCost = newCost;
                        found = true;
                    }
                }

                if (found) {
                    i = -1;
                    log.trace("OpenTwoOpt plan: {}", bestPlan);
                    break;
                }
            }
        }
        return bestPlan;
    }

    /**
     * Builds the candidate plan whose stops are {@code nodes}, in that order. The returned plan is costed with
     * {@code costCalculation.calculateCost(plan)} and, when accepted, becomes the current best plan, so it must keep
     * the plan's resource, zone and any other state the domain relies on.
     *
     * @param plan  a fresh copy of the current best plan, owned by this call: the implementation may rebuild and return
     *              it in place
     * @param nodes the candidate stop order, a new list built from another copy of the current best plan (its
     *              destination, if any, last)
     * @return the candidate plan
     */
    protected abstract P getPlan(P plan, List<N> nodes);

    private List<N> candidate(int move, P plan, int i, int j) {
        return switch (move) {
            case 1 -> plan1(plan, i, j);
            case 2 -> plan2(plan, i, j);
            default -> plan3(plan, i, j);
        };
    }

    private List<N> plan1(P plan, int i, int j) {
        List<N> nodes = new ArrayList<>(plan.getNodes().subList(0, i));
        List<N> middlePart = new ArrayList<>(plan.getNodes().subList(i, j));
        Collections.reverse(middlePart);
        nodes.addAll(middlePart);
        nodes.addAll(plan.getNodes().subList(j, plan.getCount()));
        return nodes;
    }

    private List<N> plan2(P plan, int i, int j) {
        N destination = plan.getDestination();
        List<N> nodes = new ArrayList<>(plan.getNodes().subList(0, i));
        nodes.addAll(plan.getNodes().subList(j, destination == null ? plan.getCount() : plan.getCount() - 1));
        nodes.addAll(plan.getNodes().subList(i, j));
        if (destination != null) {
            nodes.add(destination);
        }
        return nodes;
    }

    private List<N> plan3(P plan, int i, int j) {
        N destination = plan.getDestination();
        List<N> nodes = new ArrayList<>(plan.getNodes().subList(0, i));
        List<N> middlePart = new ArrayList<>(plan.getNodes().subList(j, destination == null ? plan.getCount()
                : plan.getCount() - 1));
        Collections.reverse(middlePart);
        nodes.addAll(middlePart);
        nodes.addAll(plan.getNodes().subList(i, j));
        if (destination != null) {
            nodes.add(destination);
        }
        return nodes;
    }

}
