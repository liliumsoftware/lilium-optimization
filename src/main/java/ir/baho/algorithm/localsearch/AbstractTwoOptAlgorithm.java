package ir.baho.algorithm.localsearch;

import ir.baho.algorithm.calculation.PathCostCalculation;
import ir.baho.algorithm.domain.Nodeable;
import ir.baho.algorithm.domain.Planable;
import ir.baho.algorithm.feasibility.FeasibilityChecker;
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
 * <p>The last node is never part of a reversed segment, which is right for a closed tour (the destination
 * stays last) but means that on an <em>open</em> plan the final customer is never moved; use
 * {@link AbstractOpenTwoOptAlgorithm} for open plans.
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
        for (int i = 0; i < bestPlan.getCount(); i++) {
            for (int j = i + 2; j < bestPlan.getCount(); j++) {
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

    /**
     * The node sequence with the segment {@code [i, j)} reversed.
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
