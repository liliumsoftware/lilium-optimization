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
import com.github.liliumsoftware.optimization.feasibility.FeasibilityChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Classic 2-opt for a closed tour: repeatedly reverse the sub-path between two positions and keep the result
 * whenever it is both cheaper and feasible, restarting the scan from the top on every accepted improvement
 * (first-improvement, not best-improvement). Runs until a full pass finds no improving reversal — a local
 * optimum, not a global one. O(n²) candidate pairs per pass.
 *
 * <p>The origin always stays first. A trailing {@link Nodeable.Type#DESTINATION} node also stays last; on an
 * <em>open</em> plan (last node a customer) the final stop takes part in reversals like any other customer.
 * {@link AbstractOpenTwoOptAlgorithm} adds relocation moves that are specific to open paths.
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractTwoOptAlgorithm<P
        extends Planable<P, N, ?>, N extends Nodeable<N>> extends LocalSearchAlgorithm<P> {

    protected final PathCostCalculation<P, N, ?> costCalculation;
    protected final FeasibilityChecker<P> feasibilityChecker;

    /**
     * Improves {@code plan} by segment reversals until no reversal is both cheaper and feasible; returns the
     * input when {@link #supports(Planable)} rejects it or nothing improves.
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
     * The node sequence with the segment {@code [i, j)} reversed; {@code j} may equal {@code getCount()}.
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
     * The plan with the segment between positions {@code i} and {@code j} reversed; may rebuild {@code plan} in place.
     */
    protected abstract P twoOptSwap(P plan, int i, int j);

}
