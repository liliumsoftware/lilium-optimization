package ir.baho.algorithm.merge;

import ir.baho.algorithm.BaseAlgorithm;
import ir.baho.algorithm.assignment.TrackableAssignment;
import ir.baho.algorithm.calculation.CostCalculation;
import ir.baho.algorithm.calculation.PathCostCalculation;
import ir.baho.algorithm.domain.Nodeable;
import ir.baho.algorithm.domain.Planable;
import ir.baho.algorithm.domain.Trackable;
import ir.baho.algorithm.localsearch.LocalSearchAlgorithm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Fuses two compatible plans into one whenever doing so lowers total cost.
 *
 * <p>A merge drops the first plan's destination and the second plan's origin, concatenates the
 * remaining node sequences into the first plan, re-optimizes it with local search, then re-assigns
 * trackables across the shrunken plan set. The candidate is accepted only if resource assignment
 * succeeds ({@link TrackableAssignment#assignAndExchange}) <em>and</em> summed cost strictly
 * improves; on acceptance the caller's {@code plans} list is mutated in place. By default merging is
 * restricted to plans in neighbouring zones (see {@link #supports(Planable, Planable)}).
 *
 * <p>The outer scan restarts from the top on every accepted merge ({@code i = -1; break}) and
 * settles when no further improving merge exists. {@code cancel()} aborts the scan with a
 * {@link ir.baho.algorithm.exception.CancelException}, {@code interrupt()} ends it with the merges
 * accepted so far; both are forwarded to the local search.
 *
 * @param <P> plan type
 * @param <N> node type
 * @param <T> trackable (resource) type
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractMergeAlgorithm<P
        extends Planable<P, N, T>, N extends Nodeable<N>, T extends Trackable<T>> extends BaseAlgorithm {

    protected final PathCostCalculation<P, N, ?> costCalculation;
    protected final TrackableAssignment<P, T> trackableAssignment;
    protected final LocalSearchAlgorithm<P> localSearchAlgorithm;

    /**
     * Scans all ordered plan pairs, merging any pair that improves cost, and restarts from the
     * start after each accepted merge until a fixed point (or interruption) is reached.
     *
     * @return the same {@code plans} instance, mutated in place
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
     * Attempts to merge the pair, trying both orientations (first+second, then second+first) since
     * concatenation is directional (destination-of-A joined to customers-of-B).
     *
     * @return {@code true} if either orientation was accepted and {@code plans} was updated
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
     * Builds the merged candidate on deep copies (first plan absorbs the second, second removed),
     * re-assigns trackables, and commits only on cost improvement — otherwise leaves {@code plans}
     * untouched.
     *
     * @return {@code true} if the merge was accepted and committed
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
                log.debug(getMessage(firstPlan, secondPlan));
                return true;
            }
        }
        return false;
    }

    /**
     * Per-plan eligibility gate; override to exclude plans from merging (default: all eligible).
     */
    protected boolean supports(P plan) {
        return true;
    }

    /**
     * Pairwise eligibility; by default only plans in neighbouring zones may be merged.
     */
    protected boolean supports(P firstPlan, P secondPlan) {
        return costCalculation.isNeighbor(firstPlan.getZone(), secondPlan.getZone());
    }

    /**
     * Acceptance criterion: the candidate set must have strictly lower summed cost.
     */
    protected boolean compare(List<P> newPlans, List<P> oldPlans) {
        return CostCalculation.totalCost(newPlans) < CostCalculation.totalCost(oldPlans);
    }

    /**
     * Debug message logged on an accepted merge; override to describe the merged pair.
     */
    protected String getMessage(P firstPlan, P secondPlan) {
        return "Merge plans";
    }

    /**
     * Propagates cancellation to the nested local-search algorithm before cancelling itself.
     */
    @Override
    public void cancel() {
        localSearchAlgorithm.cancel();
        super.cancel();
    }

    /**
     * Propagates the graceful stop to the nested local search before interrupting itself.
     */
    @Override
    public void interrupt() {
        localSearchAlgorithm.interrupt();
        super.interrupt();
    }

}
