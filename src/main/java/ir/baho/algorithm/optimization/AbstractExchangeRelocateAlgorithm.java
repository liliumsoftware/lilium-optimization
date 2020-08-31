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
import java.util.List;
import java.util.stream.Collectors;

/**
 * Canonical inter-plan improvement skeleton: moves node segments <em>between</em> two plans and keeps a move only
 * when it is both feasible and cheaper. Concrete behaviour is supplied by the two subclasses
 * {@link AbstractExchangeAlgorithm} (swap segments) and {@link AbstractRelocateAlgorithm} (move a segment one way).
 *
 * <p>The move/accept loop is the reference implementation for the whole library: every candidate is built by
 * copying the entire plan list ({@code plans.stream().map(P::copy)}), applying the move to the <em>copies</em>,
 * and accepting only if TrackableAssignment.assign still succeeds and {@link #compare} shows a lower
 * summed cost. On acceptance the shared plan list is mutated in place ({@code plans.clear(); plans.addAll(best)})
 * and the outer scan restarts from the top ({@code i = -1; break}); the pass terminates by natural exhaustion
 * rather than an iteration cap.
 *
 * <p>Tuning knobs: {@code chain} caps the segment length that may be moved, {@code cross} decides whether the
 * second plan's side of a move may also be up to {@code chain} stops long or is a single position (an exchange then
 * swaps against one stop, a relocate inserts at one position), {@code neighbors} restricts the
 * candidate target to a node's N nearest neighbours, and {@code type} ({@link ExchangeRelocateType}) selects how
 * removed segments are re-added. {@link #apply(List, int)} splits the work into independent {@link ZoneSplitAlgorithm}
 * groups and runs them one after the other against the live plan set.
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractExchangeRelocateAlgorithm<P
        extends Planable<P, N, ?>, N extends Nodeable<N>> extends BaseAlgorithm {

    protected final PathCostCalculation<P, N, ?> costCalculation;
    protected final TrackableAssignment<P, ?> trackableAssignment;
    protected final LocalSearchAlgorithm<P> localSearchAlgorithm;
    protected final AbstractInsertionAlgorithm<P, N, ?> insertionAlgorithm;
    protected final ExchangeRelocateType type;
    protected final int neighbors;
    protected final int chain;
    protected final boolean cross;

    /**
     * Entry point that partitions {@code plans} into non-interacting zone groups (see {@link ZoneSplitAlgorithm})
     * and optimizes them through {@link ZoneGroups}: in parallel on the resources each group holds, then in input order
     * with the idle resources; {@code maxSplitCount} bounds how finely the work may be split. No resource is booked
     * twice, every idle resource stays available to the group that needs it, and the result is deterministic. Groups
     * come back in the order of their first plan in {@code plans}. Returns a new list.
     */
    public List<P> apply(List<P> plans, int maxSplitCount) {
        return ZoneGroups.apply(costCalculation, trackableAssignment, maxSplitCount, plans, this::apply);
    }

    /**
     * Optimizes the whole list as one group (no zone partitioning). Mutates and returns {@code plans}.
     */
    public List<P> apply(List<P> plans) {
        return apply(new ArrayList<>(), plans);
    }

    /**
     * Scans every unordered pair of plans and applies the first improving move found (first improvement: the
     * longest improving segment for the first start positions that admit one, not the best move overall), then
     * restarts. {@code otherPlans} carries
     * plans from sibling zone groups that must be honoured during resource assignment but are not themselves
     * optimized here. On any accepted move the scan restarts from the beginning ({@code i = -1; break}) so that
     * newly created opportunities are re-examined; {@link #isInterrupted} is polled at pair boundaries to return
     * the best-so-far result gracefully.
     */
    protected List<P> apply(List<P> otherPlans, List<P> plans) {
        for (int i = 0; i < plans.size(); i++) {
            if (!this.supports(plans.get(i))) {
                continue;
            }
            for (int j = i + 1; j < plans.size(); j++) {
                if (isInterrupted()) {
                    return plans;
                }
                if (i == j) {
                    continue;
                }
                if (!this.supports(plans.get(j))) {
                    continue;
                }
                boolean done = apply(otherPlans, plans, plans.get(i), plans.get(j));
                if (done) {
                    i = -1;
                    break;
                }
            }
        }
        return plans;
    }

    /**
     * Enumerates every start-position pair {@code (i, j)} in the two plans and delegates to the segment-level move.
     * {@code getCount()} is the number of stops in a plan, origin and destination included, so depot positions are
     * enumerated too ({@link #supports(Planable, Planable, int, int, int, int)} rejects segments touching them).
     */
    protected boolean apply(List<P> otherPlans, List<P> plans, P firstPlan, P secondPlan) {
        if (firstPlan.equals(secondPlan)) {
            return false;
        }
        if (!this.supports(firstPlan) || !this.supports(secondPlan)) {
            return false;
        }
        for (int i = 0; i < firstPlan.getCount(); i++) {
            for (int j = 0; j < secondPlan.getCount(); j++) {
                if (this.apply(otherPlans, plans, firstPlan, secondPlan, i, j)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The core move/accept step for a fixed pair of start positions. It sweeps segment lengths from longest to
     * shortest ({@code chain}-bounded on both sides, or only the first side when {@code cross} is false), and for
     * each length: copies all plans, applies {@link #doOperation} to the copies, drops a plan left with no
     * customers (for every type), then accepts the copy set only if resource
     * assignment succeeds and {@link #compare} improves on the best set accepted so far. The longest first-side
     * length that admits an improving move ends the sweep, and among its second-side lengths the best accepted set
     * wins. On any acceptance the shared {@code plans} list is replaced in place and every plan is re-run through
     * {@link LocalSearchAlgorithm} before returning {@code true}.
     */
    protected boolean apply(List<P> otherPlans, List<P> plans, P firstPlan, P secondPlan, int i, int j) {
        List<P> bestPlans = plans;

        for (int x = Math.min(chain, firstPlan.getCount()) - 1; x >= 0; x--) {
            int firstPlanEnd = i + x + 1;
            if (firstPlanEnd > firstPlan.getCount()) {
                continue;
            }

            boolean done = false;
            for (int y = (cross ? Math.min(chain, secondPlan.getCount()) : 1) - 1; y >= 0; y--) {
                checkCancel();

                int secondPlanEnd = j + y + 1;
                if (!supportsSecondPlanRange(secondPlan, j, secondPlanEnd)) {
                    continue;
                }

                if (!this.supports(firstPlan, secondPlan, i, j, firstPlanEnd, secondPlanEnd)) {
                    continue;
                }

                int firstIndex = plans.indexOf(firstPlan);
                int secondIndex = plans.indexOf(secondPlan);
                List<P> tempPlans = plans.stream().map(P::copy).collect(Collectors.toList());
                P tempFirstPlan = tempPlans.get(firstIndex);
                P tempSecondPlan = tempPlans.get(secondIndex);

                if (!doOperation(tempPlans, tempFirstPlan, tempSecondPlan, i, j, firstPlanEnd, secondPlanEnd)) {
                    continue;
                }
                // The insertion strategy replaces plans inside tempPlans, so re-read by position rather than trusting
                // the references captured before the operation.
                P movedFirst = tempPlans.get(firstIndex);
                boolean empty = movedFirst.getCustomerCount() == 0;
                // An emptied plan is dropped before the move is judged, for every type: it needs no resource and its
                // depot-to-depot cost must not count against the move.
                if (empty) {
                    tempPlans.remove(firstIndex);
                }
                if (trackableAssignment.assign(otherPlans, tempPlans) && compare(tempPlans, bestPlans)) {
                    if (type == ExchangeRelocateType.SIMPLE) {
                        int movedSecondIndex = empty && firstIndex < secondIndex ? secondIndex - 1 : secondIndex;
                        tempPlans.set(movedSecondIndex, localSearchAlgorithm.apply(tempPlans.get(movedSecondIndex)));
                        if (!empty) {
                            tempPlans.set(firstIndex, localSearchAlgorithm.apply(tempPlans.get(firstIndex)));
                        }
                    }
                    bestPlans = tempPlans;
                    done = true;
                }
            }
            if (done) {
                break;
            }
        }
        if (plans == bestPlans) {
            return false;
        }
        log.debug(getMessage(firstPlan, secondPlan));
        plans.clear();
        plans.addAll(bestPlans);
        plans.replaceAll(localSearchAlgorithm::apply);
        return true;
    }

    /**
     * Whether {@code plan} may take part in a move; {@code true} by default.
     */
    protected boolean supports(P plan) {
        return true;
    }

    /**
     * Whether the two segments may be moved between the plans; by default when the plans' zones neighbour each other.
     */
    protected boolean supports(P firstPlan, P secondPlan, int firstPlanStart, int secondPlanStart, int firstPlanEnd,
            int secondPlanEnd) {
        return costCalculation.isNeighbor(firstPlan.getZone(), secondPlan.getZone());
    }

    /**
     * Whether {@code [start, end)} is a position range of {@code secondPlan} the move may use; by default it must lie
     * within the plan's stops. The relocate variant also admits the slot right behind the last customer.
     */
    protected boolean supportsSecondPlanRange(P secondPlan, int start, int end) {
        return end <= secondPlan.getCount();
    }

    /**
     * Whether {@code newPlans} is accepted over {@code oldPlans}: its summed cost is lower.
     */
    protected boolean compare(List<P> newPlans, List<P> oldPlans) {
        return CostCalculation.totalCost(newPlans) < CostCalculation.totalCost(oldPlans);
    }

    /**
     * Applies the move to the given copies: the first plan's segment {@code [firstPlanStart, firstPlanEnd)} and, for an
     * exchange, the second plan's {@code [secondPlanStart, secondPlanEnd)}; {@code false} if the move cannot be made.
     */
    protected abstract boolean doOperation(List<P> plans, P firstPlan, P secondPlan, int firstPlanStart,
            int secondPlanStart, int firstPlanEnd, int secondPlanEnd);

    /**
     * The debug message logged for an accepted move.
     */
    protected abstract String getMessage(P firstPlan, P secondPlan);

    /**
     * Propagates cancellation to the nested local search and insertion algorithms before cancelling itself.
     */
    @Override
    public void cancel() {
        localSearchAlgorithm.cancel();
        insertionAlgorithm.cancel();
        super.cancel();
    }

    /**
     * Propagates the graceful stop to the nested local search and insertion algorithms before interrupting itself.
     */
    @Override
    public void interrupt() {
        localSearchAlgorithm.interrupt();
        insertionAlgorithm.interrupt();
        super.interrupt();
    }

}
