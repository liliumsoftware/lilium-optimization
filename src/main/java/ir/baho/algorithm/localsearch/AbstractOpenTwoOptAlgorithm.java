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
 * 2-opt variant for open (non-returning) plans, where the destination — if any — must stay last. For each
 * position pair it evaluates three reconnection moves (segment reversal, segment move, and reversed move)
 * and keeps the cheapest one that beats the current plan and is feasible, restarting the scan on improvement. Because
 * an open path has no closing edge, the plain reversal alone would miss improvements the two relocation variants
 * catch.
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractOpenTwoOptAlgorithm<P
        extends Planable<P, N, ?>, N extends Nodeable<N>> extends LocalSearchAlgorithm<P> {

    protected final PathCostCalculation<P, N, ?> costCalculation;
    protected final FeasibilityChecker<P> feasibilityChecker;

    /**
     * Improves {@code plan} by reversal and relocation moves until no move is both cheaper and feasible; returns
     * the input when {@link #supports(Planable)} rejects it or nothing improves.
     */
    public P apply(P plan) {
        if (!this.supports(plan)) {
            return plan;
        }
        P bestPlan = plan;
        long bestCost = costCalculation.calculateCost(bestPlan);
        N destination = plan.getDestination();
        for (int i = 0; i <= bestPlan.getCount(); i++) {
            for (int j = i + 2; j <= (destination == null ? bestPlan.getCount() : bestPlan.getCount() - 1); j++) {
                checkCancel();
                if (!this.supports(bestPlan, i, j)) {
                    continue;
                }
                boolean found = false;
                // Each candidate is built from its own copy, so getPlan may rebuild the plan it is given in place
                // (as twoOptSwap may in the closed variant) without touching the caller's plan or the other candidates.
                P newPlan1 = this.getPlan(bestPlan.copy(), plan1(bestPlan.copy(), i, j));
                P newPlan2 = this.getPlan(bestPlan.copy(), plan2(bestPlan.copy(), i, j));

                long newCost = costCalculation.calculateCost(newPlan1);
                if (newCost < bestCost && feasibilityChecker.getResult(newPlan1).isFeasible()) {
                    bestPlan = newPlan1;
                    bestCost = newCost;
                    found = true;
                }

                newCost = costCalculation.calculateCost(newPlan2);
                if (newCost < bestCost && feasibilityChecker.getResult(newPlan2).isFeasible()) {
                    bestPlan = newPlan2;
                    bestCost = newCost;
                    found = true;
                }

                P newPlan3 = this.getPlan(bestPlan.copy(), plan3(bestPlan.copy(), i, j));
                newCost = costCalculation.calculateCost(newPlan3);
                if (newCost < bestCost && feasibilityChecker.getResult(newPlan3).isFeasible()) {
                    bestPlan = newPlan3;
                    bestCost = newCost;
                    found = true;
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
     * The candidate plan with {@code nodes} as its stops; {@code plan} is a fresh copy of the current best plan, so an
     * implementation may rebuild and return it in place.
     */
    protected abstract P getPlan(P plan, List<N> nodes);

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
