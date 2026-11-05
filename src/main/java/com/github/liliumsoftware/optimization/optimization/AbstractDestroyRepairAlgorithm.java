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

package com.github.liliumsoftware.optimization.optimization;

import com.github.liliumsoftware.optimization.BaseAlgorithm;
import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.calculation.CostCalculation;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.domain.Planable;
import com.github.liliumsoftware.optimization.exception.CancelException;
import com.github.liliumsoftware.optimization.insertion.AbstractInsertionAlgorithm;
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;
import com.github.liliumsoftware.optimization.split.ZoneGroups;
import com.github.liliumsoftware.optimization.split.ZoneSplitAlgorithm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Large-neighbourhood "destroy and repair": removes a chain of customers from one plan, re-inserts them into the other
 * plans with the configured insertion algorithm (cheapest or regret insertion, as that algorithm is configured) and
 * keeps the result only if every customer found a place, the resources can still be assigned and the total cost
 * strictly drops. It escapes local optima that single moves such as exchange and relocate get stuck in.
 *
 * <p><strong>Moves.</strong> A chain is {@code [start, end)} of one plan, at most {@code destroyRepairChain} stops
 * long, starting and ending at a customer ({@link #supports(Planable, int, int)}). The source plan is taken out of the
 * list while its customers are re-inserted, so they never go back into it; it returns to its position afterwards
 * unless it was emptied. The resources are then re-assigned against the plans of the other zone groups.
 *
 * <p><strong>Passes.</strong> Each pass of {@link #apply(List, List)} has three steps:
 * <ol>
 *   <li>a ranked step: customers are ranked by {@link #getHighestRankNodes} (badly placed between their neighbours
 *       first, only those whose rank reaches {@code destroyRepairThreshold}); for each in turn, every chain starting at
 *       it is probed and the best improving one is committed, which ends the step; a customer without an improving
 *       chain hands over to the next one;</li>
 *   <li>a small-plan step: every plan with at most {@code destroyRepairChain} customers is destroyed and repaired chain
 *       by chain ({@link #destroyAndRepair(List, List, Planable)}), which can dissolve it entirely; a dissolved plan
 *       is removed and the plan that moves up into its position is visited next;</li>
 *   <li>the local search runs on every plan.</li>
 * </ol>
 * The search stops after a pass that accepted no move and whose local search lowered no cost, once
 * {@link #interrupt()} has been called (polled before every pass, every ranked customer and every plan of the
 * small-plan step; the moves accepted so far are kept and the pass's local search is skipped), or after
 * 100&nbsp;000 passes (logged at WARN), a cap that only a
 * {@link #compare} that is not a strict improvement can reach. Progress is measured by accepted moves and cost, never
 * by the plans' {@code equals}.
 *
 * <p><strong>Mutation and signals.</strong> Every move is built and judged on copies, and an accepted move replaces
 * the list contents with those copies, so the list passed in is mutated by replacing its elements while the caller's
 * plan objects are never modified (the local search runs on them when no move was accepted, which is why it must not
 * modify its argument). {@link #cancel()} aborts with a {@link CancelException}; both signals are forwarded to the
 * insertion and local search algorithms.
 *
 * <p><strong>Thread-safety.</strong> No mutable state beyond the signal flags; one instance may run on independent
 * lists concurrently. {@link #apply(List, int)} runs zone groups in parallel, so the collaborators must be thread-safe
 * as well (the library's own are).
 *
 * @param <P> the plan type
 * @param <N> the node type
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractDestroyRepairAlgorithm<P
        extends Planable<P, N, ?>, N extends Nodeable<N>> extends BaseAlgorithm {

    /**
     * Upper bound on full passes of {@link #apply(List, List)}; only reached when {@link #compare} keeps accepting
     * moves that do not converge.
     */
    private static final int MAX_PASSES = 100_000;
    /**
     * Supplies the closest-node ranks used to pick destroy candidates; must be initialized with every node of the
     * plans.
     */
    protected final PathCostCalculation<P, N, ?> costCalculation;
    /**
     * Re-assigns the resources of every repaired plan set; a move whose plans cannot all be assigned is rejected.
     */
    protected final TrackableAssignment<P, ?> trackableAssignment;
    /**
     * Improves every plan at the end of each pass.
     */
    protected final LocalSearchAlgorithm<P> localSearchAlgorithm;
    /**
     * Re-inserts the customers of a destroyed chain into the other plans.
     */
    protected final AbstractInsertionAlgorithm<P, N, ?> insertionAlgorithm;
    /**
     * The maximum number of stops in a destroyed chain, and the customer count up to which a plan is destroyed whole
     * in the small-plan step.
     */
    protected final int destroyRepairChain;
    /**
     * The minimum {@link #getRank rank} a customer needs to start a chain in the ranked step.
     */
    protected final int destroyRepairThreshold;

    private static boolean sameInstances(List<?> a, List<?> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
    }

    private static int indexOfInstance(List<?> list, Object element) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == element) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Partitions {@code plans} into at most {@code maxSplitCount} independent zone groups (see
     * {@link ZoneSplitAlgorithm}) and repairs them through {@link ZoneGroups}: first in parallel, each group limited to
     * the resources its own plans hold, then, for the groups that wanted an idle resource, once more one after the
     * other in input order against the current plans of the other groups. No resource is booked twice and the result
     * does not depend on thread timing.
     *
     * @param plans         the plans to improve; not modified, nor are the plan objects in it
     * @param maxSplitCount the maximum number of zone groups
     * @return a new list holding every group's result, groups in the order of their first plan in {@code plans}
     * @throws CancelException       if the search was cancelled
     * @throws ArithmeticException   if a summed cost does not fit a {@code long}
     * @throws IllegalStateException if the cost matrix is uninitialized or lacks a node of the plans
     */
    public List<P> apply(List<P> plans, int maxSplitCount) {
        return ZoneGroups.apply(costCalculation, trackableAssignment, maxSplitCount, plans, this::apply);
    }

    /**
     * Destroy-and-repair over the whole list as one group, without zone partitioning and with no plans of other groups
     * to honour.
     *
     * @param plans the plans to improve; a mutable list owned by the caller, whose elements are replaced by improved
     *              copies (the plan objects themselves are not modified)
     * @return {@code plans}
     * @throws CancelException       if the search was cancelled
     * @throws ArithmeticException   if a summed cost does not fit a {@code long}
     * @throws IllegalStateException if the cost matrix is uninitialized or lacks a node of the plans
     */
    public List<P> apply(List<P> plans) {
        return apply(new ArrayList<>(), plans);
    }

    /**
     * The destroy-and-repair loop described in the class description: passes of a ranked step, a small-plan step and
     * local search on every plan, until a pass accepts no move and its local search lowers no cost, the algorithm is
     * interrupted (checked before each pass, each ranked customer and each plan of the small-plan step), or the pass
     * cap is reached. Plans compared by value, by code or by
     * identity give the same result.
     *
     * @param otherPlans the plans of the other zone groups, whose resources the assignment must leave alone; not
     *                   modified
     * @param plans      the plans to improve; its elements are replaced by improved copies
     * @return {@code plans}
     * @throws CancelException       if the search was cancelled
     * @throws ArithmeticException   if a summed cost does not fit a {@code long}
     * @throws IllegalStateException if the cost matrix is uninitialized or lacks a node of the plans
     */
    protected List<P> apply(List<P> otherPlans, List<P> plans) {
        for (int pass = 1; !isInterrupted(); pass++) {
            if (pass > MAX_PASSES) {
                log.warn("Destroy-repair stopped after {} passes without converging; check that compare() is a strict"
                        + " improvement", MAX_PASSES);
                break;
            }
            List<P> passStart = new ArrayList<>(plans);
            for (Map.Entry<N, Integer> node : getHighestRankNodes(plans)) {
                // Improvement, not construction: an interrupt keeps the moves accepted so far.
                if (isInterrupted()) {
                    return plans;
                }
                boolean resume = false;
                for (int i = 0; i < plans.size(); i++) {
                    boolean done = false;
                    P plan = plans.get(i);
                    int count = Math.min(plan.getCustomerCount(), destroyRepairChain);
                    for (int j = 0; j < plan.getCount(); j++) {
                        N n = plan.getNodes().get(j);
                        if (n.getCode().equals(node.getKey().getCode())) {
                            List<P> bestPlans = plans;
                            for (int k = j; k < count + j; k++) {
                                checkCancel();
                                if (k + 1 > plan.getCount()) {
                                    continue;
                                }
                                if (!supports(plan, j, k + 1)) {
                                    continue;
                                }

                                List<P> tempPlans = plans.stream().map(P::copy).collect(Collectors.toList());
                                if (destroyAndRepair(otherPlans, tempPlans, i, j, k + 1) && compare(tempPlans,
                                        bestPlans)) {
                                    bestPlans = tempPlans;
                                    if (log.isDebugEnabled()) {
                                        log.debug(getMessage(plan));
                                    }
                                }
                            }
                            // bestPlans is only ever replaced by a strictly better copy, so identity tells whether
                            // a move was found — whatever the consumer's equals says about the copies.
                            if (bestPlans != plans) {
                                plans.clear();
                                plans.addAll(bestPlans);
                            } else {
                                resume = true;
                            }
                            done = true;
                            break;
                        }
                    }
                    if (done) {
                        break;
                    }
                }
                if (!resume) {
                    break;
                }
            }
            for (int i = 0; i < plans.size(); i++) {
                checkCancel();
                if (isInterrupted()) {
                    return plans;
                }
                P plan = plans.get(i);
                if (plan.getCustomerCount() <= destroyRepairChain) {
                    int size = plans.size();
                    destroyAndRepair(otherPlans, plans, plan);
                    // An emptied plan was removed, so the next plan has shifted into position i: visit it next.
                    if (plans.size() < size) {
                        i--;
                    }
                }
            }
            // An accepted move replaces the list contents with fresh copies; nothing else touches the list before the
            // local search, so comparing instances detects acceptance independently of the consumer's equals.
            boolean accepted = !sameInstances(passStart, plans);
            long costBeforeLocalSearch = CostCalculation.totalCost(plans);
            plans.replaceAll(localSearchAlgorithm::apply);
            // Without an accepted move only the local search can have changed the plans; another pass pays off only if
            // it lowered a cost (it may have opened new moves), otherwise the next pass would find nothing either.
            if (!accepted && CostCalculation.totalCost(plans) >= costBeforeLocalSearch) {
                break;
            }
        }
        return plans;
    }

    /**
     * The small-plan step for one plan: for every start position of {@code plan}, every chain starting there is
     * probed on copies of the whole list and the best improving one (under {@link #compare}) is committed by replacing
     * the list contents with that copy set; the scan continues on the committed copy of the plan at the same start
     * position, which now holds the stop that followed the destroyed chain, with the chain length bound recomputed
     * from the plan's remaining customers. Stops once the plan has been emptied and removed. Does nothing when
     * {@code plan} is not an element of {@code plans} (compared by identity).
     *
     * @param otherPlans the plans of the other zone groups, honoured by the resource assignment
     * @param plans      the plans being improved; its elements are replaced when a chain is committed, the plan
     *                   objects in it are not modified
     * @param plan       the element of {@code plans} to destroy
     * @throws CancelException     if the search was cancelled
     * @throws ArithmeticException if a summed cost does not fit a {@code long}
     */
    protected void destroyAndRepair(List<P> otherPlans, List<P> plans, P plan) {
        int index = indexOfInstance(plans, plan);
        if (index < 0) {
            return;
        }
        int count = Math.min(plan.getCustomerCount(), destroyRepairChain);
        for (int j = 0; j < plan.getCount(); j++) {
            List<P> bestPlans = plans;
            for (int k = j; k < count + j; k++) {
                checkCancel();
                if (k + 1 > plan.getCount()) {
                    continue;
                }
                if (!supports(plan, j, k + 1)) {
                    continue;
                }

                List<P> tempPlans = plans.stream().map(P::copy).collect(Collectors.toList());
                if (destroyAndRepair(otherPlans, tempPlans, index, j, k + 1) && compare(tempPlans, bestPlans)) {
                    bestPlans = tempPlans;
                }
            }
            if (bestPlans != plans) {
                boolean emptied = bestPlans.size() < plans.size();
                plans.clear();
                plans.addAll(bestPlans);
                if (emptied) {
                    return;
                }
                plan = plans.get(index);
                count = Math.min(plan.getCustomerCount(), destroyRepairChain);
                // The chain [j, end) is gone and the stop behind it has shifted into position j: try it next.
                j--;
            }
        }
    }

    /**
     * Destroys and repairs one chain, in place on {@code plans}: takes the plan at {@code index} out of the list,
     * removes its stops {@code [start, end)}, inserts the removed customers into the remaining plans with the
     * insertion algorithm, puts the plan back at {@code index} unless it has no customer left, and re-assigns the
     * resources against {@code otherPlans}. The callers pass a copy of the list whose plans are copies too.
     *
     * @param otherPlans the plans of the other zone groups, honoured by the resource assignment
     * @param plans      the plan set to modify; its plans and elements are changed even when the result is
     *                   {@code false}
     * @param index      the position of the plan to destroy
     * @param start      the first position of the chain
     * @param end        the exclusive end of the chain
     * @return {@code true} if every removed customer found a place and the assignment succeeded; {@code false} also
     *         when {@code index} is out of range
     */
    protected boolean destroyAndRepair(List<P> otherPlans, List<P> plans, int index, int start, int end) {
        if (index < 0 || index >= plans.size()) {
            return false;
        }
        P plan = plans.remove(index);
        List<N> removedNodes = new ArrayList<>(plan.getNodes().subList(start, end));
        plan.getNodes().removeAll(removedNodes);
        List<N> insertedNodes = insertionAlgorithm.insert(plans, removedNodes);
        if (plan.getCustomerCount() > 0) {
            plans.add(index, plan);
        }
        return insertedNodes.size() == removedNodes.size() && trackableAssignment.assign(otherPlans, plans);
    }

    /**
     * Every customer of {@code plans}, ranked by how badly it sits between its neighbours: by {@link #getRank}, then by
     * {@link #getCostRank}, both descending, ties in plan and route order. Customers whose rank is below
     * {@code destroyRepairThreshold} are left out. A neighbour passed to the rank hooks is {@code null} at a plan end
     * without a depot stop.
     *
     * @param plans the plans to rank the customers of; not modified
     * @return the destroy candidates, best first, each mapped to its rank
     * @throws CancelException       if the search was cancelled
     * @throws IllegalStateException if the cost matrix is uninitialized or lacks a node of the plans
     */
    protected List<Map.Entry<N, Integer>> getHighestRankNodes(List<P> plans) {
        // Plan order, not hash order: the sort below is stable, so customers of equal rank and cost rank stay in the
        // order they appear in the plans, whatever the node's hashCode (identity- or enum-based hashes vary per run).
        Map<N, Integer> ranks = new LinkedHashMap<>();
        Map<String, Long> costRanks = new HashMap<>();
        for (P plan : plans) {
            // Every customer position, whatever the plan's shape: the first stop is a customer when the plan has no
            // origin, and then it has no predecessor (null, treated like the free origin edge).
            for (int i = 0; i < plan.getCount(); i++) {
                checkCancel();
                N node = plan.getNodes().get(i);
                if (node.getType() != Nodeable.Type.CUSTOMER) {
                    continue;
                }
                N before = i > 0 ? plan.getNodes().get(i - 1) : null;
                N after = i + 1 < plan.getCount() ? plan.getNodes().get(i + 1) : null;
                ranks.put(node, getRank(node, before, after));
                costRanks.put(node.getCode(), getCostRank(node, before, after));
            }
        }
        return ranks.entrySet().stream().filter(e -> e.getValue() >= destroyRepairThreshold)
                .sorted(Map.Entry.<N, Integer>comparingByValue().reversed()
                        .thenComparing(Comparator.comparing((Map.Entry<N, Integer> o) -> costRanks.get(o.getKey()
                                .getCode())).reversed()))
                .toList();

    }

    /**
     * Whether {@code [start, end)} of {@code plan} may be destroyed. The default implementation requires the first and
     * last stop of the chain to be customers.
     *
     * @param plan  the plan the chain belongs to
     * @param start the first position of the chain
     * @param end   the exclusive end of the chain
     * @return {@code true} if the chain may be destroyed and repaired
     */
    protected boolean supports(P plan, int start, int end) {
        return plan.getNodes().get(start).getType() == Nodeable.Type.CUSTOMER
                && plan.getNodes().get(end - 1).getType() == Nodeable.Type.CUSTOMER;
    }

    /**
     * How badly {@code node} sits between its neighbours; higher is worse. The default implementation returns
     * {@link PathCostCalculation#getClosestNodeRank}: the position of its worse neighbour in its closest-node ranking,
     * with depots and missing neighbours treated as free.
     *
     * @param node   the customer to rank
     * @param before its predecessor, or {@code null} at the start of a plan without an origin
     * @param after  its successor, or {@code null} at the end of a plan without a destination
     * @return the rank, compared with {@code destroyRepairThreshold}
     * @throws IllegalStateException if the cost matrix is uninitialized or lacks one of the nodes (default
     *                               implementation)
     */
    protected int getRank(N node, N before, N after) {
        return costCalculation.getClosestNodeRank(node.getCode(), before, after);
    }

    /**
     * The tie-break of {@link #getRank}; higher is worse. The default implementation returns
     * {@link PathCostCalculation#getClosestNodeCostRank}: the cost to the worse-placed neighbour.
     *
     * @param node   the customer to rank
     * @param before its predecessor, or {@code null} at the start of a plan without an origin
     * @param after  its successor, or {@code null} at the end of a plan without a destination
     * @return the cost rank
     * @throws IllegalStateException if the cost matrix is uninitialized or lacks one of the nodes (default
     *                               implementation)
     */
    protected long getCostRank(N node, N before, N after) {
        return costCalculation.getClosestNodeCostRank(node.getCode(), before, after);
    }

    /**
     * Whether the repaired set {@code newPlans} is accepted over {@code oldPlans}. The default implementation accepts a
     * strictly lower {@link CostCalculation#totalCost summed cost}. An override should also be a strict improvement;
     * otherwise the search may only stop at the pass cap.
     *
     * @param newPlans the repaired candidate set
     * @param oldPlans the best set accepted so far for the current chain start
     * @return {@code true} to accept {@code newPlans}
     * @throws ArithmeticException if a summed cost does not fit a {@code long} (default implementation)
     */
    protected boolean compare(List<P> newPlans, List<P> oldPlans) {
        return CostCalculation.totalCost(newPlans) < CostCalculation.totalCost(oldPlans);
    }

    /**
     * The message logged at DEBUG level for an improving chain found in the ranked step; only called when DEBUG is
     * enabled. The default implementation returns {@code "Destroy plan"}.
     *
     * @param plan the plan the chain was destroyed in (the list element, not the repaired copy)
     * @return the log message
     */
    protected String getMessage(P plan) {
        return "Destroy plan";
    }

    /**
     * Requests an abort of this algorithm and of its insertion and local search algorithms; see
     * {@link BaseAlgorithm#cancel()}.
     */
    @Override
    public void cancel() {
        insertionAlgorithm.cancel();
        localSearchAlgorithm.cancel();
        super.cancel();
    }

    /**
     * Requests a graceful stop of this algorithm and of its insertion and local search algorithms; see
     * {@link BaseAlgorithm#interrupt()}.
     */
    @Override
    public void interrupt() {
        insertionAlgorithm.interrupt();
        localSearchAlgorithm.interrupt();
        super.interrupt();
    }

}
