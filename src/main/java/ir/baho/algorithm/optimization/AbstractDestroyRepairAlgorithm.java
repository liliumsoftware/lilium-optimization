package ir.baho.algorithm.optimization;

import ir.baho.algorithm.BaseAlgorithm;
import ir.baho.algorithm.assignment.TrackableAssignment;
import ir.baho.algorithm.calculation.CostCalculation;
import ir.baho.algorithm.calculation.PathCostCalculation;
import ir.baho.algorithm.domain.Nodeable;
import ir.baho.algorithm.domain.Planable;
import ir.baho.algorithm.insertion.AbstractInsertionAlgorithm;
import ir.baho.algorithm.localsearch.LocalSearchAlgorithm;
import ir.baho.algorithm.split.ZoneGroups;
import ir.baho.algorithm.split.ZoneSplitAlgorithm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Large-neighbourhood "destroy and repair": remove a chain of the worst-placed customers from a plan and
 * re-insert them cheapest-first via the insertion algorithm, keeping the result only if it is feasible and
 * cheaper. Candidate stops are ranked by {@link #getHighestRankNodes} (a node poorly positioned relative to
 * its nearest neighbours ranks high) and only those at/above {@code destroyRepairThreshold} are attempted;
 * {@code destroyRepairChain} bounds the segment length. The whole pass repeats until a pass accepts no move and its
 * local search lowers no cost. Escapes the local optima that single-move exchange/relocate get stuck in.
 *
 * <p>Every move is built and accepted on copies, and an accepted move replaces the list contents with those copies, so
 * the caller's plan objects are never modified. Termination does not depend on the consumer's {@code equals}: moves
 * are only accepted when {@link #compare} reports a strict improvement, the search stops after a pass that accepts no
 * move and whose local search lowers no cost, and a generous pass cap (logged at WARN) guards against a
 * {@code compare} that is not a strict order.
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
    protected final PathCostCalculation<P, N, ?> costCalculation;
    protected final TrackableAssignment<P, ?> trackableAssignment;
    protected final LocalSearchAlgorithm<P> localSearchAlgorithm;
    protected final AbstractInsertionAlgorithm<P, N, ?> insertionAlgorithm;
    protected final int destroyRepairChain;
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
     * {@link ZoneSplitAlgorithm}), repairs them through {@link ZoneGroups} (in parallel on the resources each group
     * holds, then in input order with the idle resources) and returns the concatenated result (a new list). No resource
     * is booked twice and the result is deterministic. Groups come back in the order of their first plan in
     * {@code plans}.
     */
    public List<P> apply(List<P> plans, int maxSplitCount) {
        return ZoneGroups.apply(costCalculation, trackableAssignment, maxSplitCount, plans, this::apply);
    }

    /**
     * Destroy-and-repair over the whole list without zone partitioning. Mutates and returns {@code plans}.
     */
    public List<P> apply(List<P> plans) {
        return apply(new ArrayList<>(), plans);
    }

    /**
     * The destroy-and-repair loop: a ranked pass over the worst-placed nodes (committing the first node whose best
     * destroyed chain improves), then a pass over the plans small enough to be destroyed whole, then local search on
     * every plan. Repeats until a pass accepts no move and its local search lowers no cost, the algorithm is
     * interrupted, or the pass cap is reached. Progress is measured by accepted moves and cost, never by the plans'
     * {@code equals}, so plans compared by value, by code or by identity give the same result.
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
                                    log.debug(getMessage(plan));
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
                P plan = plans.get(i);
                if (plan.getCustomerCount() <= destroyRepairChain) {
                    destroyAndRepair(otherPlans, plans, plan);
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
     * Destroys and repairs chains of the small {@code plan} start by start; for each start the cheapest improving
     * chain is probed on copies and committed by replacing the list contents with that copy set (the plan objects
     * already in {@code plans} are never modified). Later starts continue on the committed copy of the plan; the
     * search stops once the plan has been emptied and removed.
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
            }
        }
    }

    /**
     * Removes the chain {@code [start, end)} from the plan at {@code index}, re-inserts the removed customers into
     * {@code plans} and re-assigns the resources against {@code otherPlans}; {@code true} if every customer found a
     * place and the assignment succeeded.
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
     * Every customer of {@code plans} ranked by how badly it sits between its neighbours (see
     * {@link PathCostCalculation#getClosestNodeRank}), best destroy candidates first; customers below
     * {@code destroyRepairThreshold} are left out. A neighbour is {@code null} at a plan end without a depot stop.
     */
    protected List<Map.Entry<N, Integer>> getHighestRankNodes(List<P> plans) {
        Map<N, Integer> ranks = new HashMap<>();
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
                .collect(Collectors.toList());

    }

    /**
     * Whether {@code [start, end)} is a chain of customers that may be destroyed.
     */
    protected boolean supports(P plan, int start, int end) {
        return plan.getNodes().get(start).getType() == Nodeable.Type.CUSTOMER
                && plan.getNodes().get(end - 1).getType() == Nodeable.Type.CUSTOMER;
    }

    /**
     * How badly {@code node} sits between its neighbours, as a closest-node rank; higher is worse.
     */
    protected int getRank(N node, N before, N after) {
        return costCalculation.getClosestNodeRank(node.getCode(), before, after);
    }

    /**
     * The cost-based tie-break of {@link #getRank}.
     */
    protected long getCostRank(N node, N before, N after) {
        return costCalculation.getClosestNodeCostRank(node.getCode(), before, after);
    }

    /**
     * Whether {@code newPlans} is accepted over {@code oldPlans}: its summed cost is lower.
     */
    protected boolean compare(List<P> newPlans, List<P> oldPlans) {
        return CostCalculation.totalCost(newPlans) < CostCalculation.totalCost(oldPlans);
    }

    /**
     * The debug message logged for an accepted destroy-and-repair.
     */
    protected String getMessage(P plan) {
        return "Destroy plan";
    }

    /**
     * Propagates cancellation to the nested insertion and local search algorithms before cancelling itself.
     */
    @Override
    public void cancel() {
        insertionAlgorithm.cancel();
        localSearchAlgorithm.cancel();
        super.cancel();
    }

    /**
     * Propagates the graceful stop to the nested insertion and local search algorithms before interrupting itself.
     */
    @Override
    public void interrupt() {
        insertionAlgorithm.interrupt();
        localSearchAlgorithm.interrupt();
        super.interrupt();
    }

}
