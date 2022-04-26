package ir.baho.algorithm.optimization;

import ir.baho.algorithm.assignment.TrackableAssignment;
import ir.baho.algorithm.calculation.PathCostCalculation;
import ir.baho.algorithm.domain.Nodeable;
import ir.baho.algorithm.domain.Planable;
import ir.baho.algorithm.insertion.AbstractInsertionAlgorithm;
import ir.baho.algorithm.localsearch.LocalSearchAlgorithm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Inter-plan <em>relocate</em>: moves a segment out of one plan and into another (a one-directional transfer,
 * unlike {@link AbstractExchangeAlgorithm}), trying both plan orderings so either can be the donor. It is an
 * exchange with an empty return segment, hence {@code cross = false}. A plan emptied of customers is dropped.
 *
 * <p>For {@link ExchangeRelocateType#SIMPLE} and {@link ExchangeRelocateType#TWO_OPT} the target position {@code j}
 * means "in front of stop {@code j}", for every customer of the target plan and additionally for the slot right behind
 * its last customer (in front of the destination, or the end of an open plan); that append slot qualifies when the
 * last customer is among the segment start's {@code neighbors} nearest neighbours.
 */
public abstract class AbstractRelocateAlgorithm<P
        extends Planable<P, N, ?>, N extends Nodeable<N>> extends AbstractExchangeRelocateAlgorithm<P, N> {

    /**
     * Creates a relocate move.
     *
     * @param type      how the removed segment is re-added to the target plan
     * @param neighbors how many nearest neighbours of a segment start qualify as targets
     * @param chain     maximum segment length moved
     */
    public AbstractRelocateAlgorithm(PathCostCalculation<P, N, ?> pathCostCalculation,
            TrackableAssignment<P, ?> trackableAssignment, LocalSearchAlgorithm<P> localSearchAlgorithm,
            AbstractInsertionAlgorithm<P, N, ?> insertionAlgorithm, ExchangeRelocateType type, int neighbors,
            int chain) {
        super(pathCostCalculation, trackableAssignment, localSearchAlgorithm, insertionAlgorithm, type, neighbors,
                chain, false);
    }

    @Override
    protected boolean apply(List<P> otherPlans, List<P> plans, P firstPlan, P secondPlan) {
        if (firstPlan.equals(secondPlan)) {
            return false;
        }
        if (!this.supports(firstPlan) || !this.supports(secondPlan)) {
            return false;
        }
        if (type == ExchangeRelocateType.INSERTION) {
            for (int i = 0; i < firstPlan.getCount(); i++) {
                if (this.apply(otherPlans, plans, firstPlan, secondPlan, i, 1)) {
                    return true;
                }
            }
            return false;
        }
        // Up to getCount() inclusive: the position behind the last stop is the append slot of an open plan.
        for (int i = 0; i <= firstPlan.getCount(); i++) {
            for (int j = 0; j <= secondPlan.getCount(); j++) {
                if (this.apply(otherPlans, plans, firstPlan, secondPlan, i, j)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected boolean apply(List<P> otherPlans, List<P> plans, P firstPlan, P secondPlan, int i, int j) {
        boolean result = super.apply(otherPlans, plans, firstPlan, secondPlan, i, j);
        if (result) {
            return true;
        }
        return super.apply(otherPlans, plans, secondPlan, firstPlan, j, i);
    }

    /**
     * The slot right behind the last customer: in front of the destination, or the end of an open plan.
     */
    private int appendSlot(P plan) {
        return plan.getDestination() == null ? plan.getCount() : plan.getCount() - 1;
    }

    private boolean isAppendSlot(P plan, int position) {
        return type != ExchangeRelocateType.INSERTION && position == appendSlot(plan);
    }

    /**
     * Also admits the append slot of the target plan for the fixed-position strategies.
     */
    @Override
    protected boolean supportsSecondPlanRange(P secondPlan, int start, int end) {
        return super.supportsSecondPlanRange(secondPlan, start, end) || isAppendSlot(secondPlan, start);
    }

    @Override
    protected boolean doOperation(List<P> plans, P firstPlan, P secondPlan, int firstPlanStart, int secondPlanStart,
            int firstPlanEnd, int secondPlanEnd) {
        List<N> firstPlanSwapCandidates = new ArrayList<>(firstPlan.getNodes().subList(firstPlanStart, firstPlanEnd));
        firstPlanSwapCandidates.forEach(n -> firstPlan.getNodes().remove(firstPlan.getNodes().indexOf(n)));

        return switch (type) {
            case TWO_OPT -> {
                secondPlan.getNodes().addAll(secondPlanStart, firstPlanSwapCandidates);
                plans.set(plans.indexOf(firstPlan), localSearchAlgorithm.apply(firstPlan));
                plans.set(plans.indexOf(secondPlan), localSearchAlgorithm.apply(secondPlan));
                yield true;
            }
            case INSERTION -> {
                List<N> firstInsertedNodes = insertionAlgorithm.insert(plans, firstPlanSwapCandidates,
                        plans.indexOf(secondPlan));
                yield firstInsertedNodes.size() == firstPlanSwapCandidates.size();
            }
            case SIMPLE -> {
                secondPlan.getNodes().addAll(secondPlanStart, firstPlanSwapCandidates);
                yield true;
            }
        };
    }

    @Override
    protected boolean supports(P firstPlan, P secondPlan, int firstPlanStart, int secondPlanStart, int firstPlanEnd,
            int secondPlanEnd) {
        boolean append = isAppendSlot(secondPlan, secondPlanStart);
        if (append && secondPlanStart == 0) {
            return false;
        }
        if (firstPlan.getNodes().subList(firstPlanStart, firstPlanEnd)
                .stream().anyMatch(n -> n.getType() != Nodeable.Type.CUSTOMER)
                || (type != ExchangeRelocateType.INSERTION && !append
                && secondPlan.getNodes().subList(secondPlanStart, secondPlanEnd).stream()
                .anyMatch(n -> n.getType() != Nodeable.Type.CUSTOMER))) {
            return false;
        }
        if (!super.supports(firstPlan, secondPlan, firstPlanStart, secondPlanStart, firstPlanEnd, secondPlanEnd)) {
            return false;
        }
        if (type != ExchangeRelocateType.INSERTION) {
            N firstNode = firstPlan.getNodes().get(firstPlanStart);
            // The append slot follows the last customer, so that customer is the neighbour the segment joins.
            N secondNode = secondPlan.getNodes().get(append ? secondPlanStart - 1 : secondPlanStart);
            Predicate<N> excludePredicate = e -> firstPlan.getNodes().stream()
                    .noneMatch(n -> Objects.equals(e.getCode(), n.getCode()));
            Predicate<N> neighborsPredicate = n -> costCalculation.isNeighbor(n.getZone(), firstNode.getZone());
            return costCalculation.getClosestNodes(firstNode.getCode()).stream()
                    .filter(excludePredicate) // exclude nodes on the same plan
                    .filter(neighborsPredicate) // exclude non-neighbors
                    .limit(neighbors) // use only first neighbors
                    .anyMatch(n -> Objects.equals(n.getCode(), secondNode.getCode())); // check if target matches
        }
        return true;
    }

    @Override
    protected String getMessage(P firstPlan, P secondPlan) {
        return "Relocate plan";
    }

}
