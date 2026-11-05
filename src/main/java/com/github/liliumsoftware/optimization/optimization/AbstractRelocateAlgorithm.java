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

import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.domain.Planable;
import com.github.liliumsoftware.optimization.exception.CancelException;
import com.github.liliumsoftware.optimization.insertion.AbstractInsertionAlgorithm;
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;

import java.util.ArrayList;
import java.util.List;

/**
 * Inter-plan <em>relocate</em>: moves a segment of customers out of one plan and into another (a one-way transfer,
 * unlike {@link AbstractExchangeAlgorithm}), trying both plans of a pair as the donor. A donor left without customers
 * is dropped from the list. The search, acceptance, local search and signal handling are those of
 * {@link AbstractExchangeRelocateAlgorithm}; {@code cross} is always {@code false}, as the target side is a single
 * position.
 *
 * <p>Under {@link ExchangeRelocateType#SIMPLE} and {@link ExchangeRelocateType#TWO_OPT} a segment of up to
 * {@code chain} customers starting at position {@code i} of the donor is placed in front of stop {@code j} of the
 * target, for every customer {@code j} of the target and additionally for the slot right behind its last customer (in
 * front of the destination, or the end of an open plan). The move is tried only when the target stop, or for that
 * append slot the last customer, is among the {@code neighbors} nearest nodes of the segment's first stop (see the
 * class description of {@link AbstractExchangeRelocateAlgorithm}). {@code SIMPLE} splices the segment in as it is;
 * {@code TWO_OPT} also runs both plans through the local search before the move is judged.
 *
 * <p>A target plan without customers (for example {@code [origin]}, {@code [origin, destination]} or a plan without
 * stops) has exactly one slot, behind its origin and in front of its destination (position 0 when it has neither),
 * the slot the insertion algorithm would use as well. It has no customer to rank, and its depot usually shares its
 * code with the donor's origin, which the neighbour ranking leaves out, so such a target is always eligible: the
 * neighbour limit is not applied, and whether opening it pays off is left to the cost comparison and the resource
 * assignment. The extra work is one candidate per donor segment and empty plan.
 *
 * <p>Under {@link ExchangeRelocateType#INSERTION} the insertion algorithm, restricted to the target plan, chooses the
 * positions, so only the donor's segment start varies and no neighbour test applies; the move is rejected unless every
 * customer is placed. Any plan may be the target, whatever its number of stops (a plan without customers, or an open
 * plan made of a single customer, included).
 *
 * @param <P> the plan type
 * @param <N> the node type
 */
public abstract class AbstractRelocateAlgorithm<P
        extends Planable<P, N, ?>, N extends Nodeable<N>> extends AbstractExchangeRelocateAlgorithm<P, N> {

    /**
     * Creates a relocate move.
     *
     * @param pathCostCalculation  supplies the nearest-node ranking and zone neighbourhood; must be initialized with
     *                             every node of the plans
     * @param trackableAssignment  re-assigns the resources of every candidate plan set
     * @param localSearchAlgorithm improves the touched plans and, after every accepted move, every plan; must not
     *                             modify its argument
     * @param insertionAlgorithm   re-inserts the moved segment under {@link ExchangeRelocateType#INSERTION}
     * @param type                 how the moved segment is re-added to the target plan
     * @param neighbors            how many nearest nodes of a segment's first stop qualify as targets
     * @param chain                the maximum number of stops in a moved segment
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
            // The insertion algorithm chooses the target position itself, so only the donor's segment start
            // varies. Each direction is scanned over *its own* donor's positions: a single reversed call per
            // first-plan index would only ever try the second plan's segment at index 1.
            for (int i = 0; i < firstPlan.getCount(); i++) {
                if (super.apply(otherPlans, plans, firstPlan, secondPlan, i, 1)) {
                    return true;
                }
            }
            for (int j = 0; j < secondPlan.getCount(); j++) {
                if (super.apply(otherPlans, plans, secondPlan, firstPlan, j, 1)) {
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

    /**
     * Tries the move in both directions for {@link ExchangeRelocateType#SIMPLE} and
     * {@link ExchangeRelocateType#TWO_OPT}: first {@code firstPlan} donates its segment starting at {@code i} into
     * {@code secondPlan} at {@code j}, then, if that was not accepted, {@code secondPlan} donates its segment starting
     * at {@code j} into {@code firstPlan} at {@code i}.
     *
     * @param otherPlans the plans of the other zone groups, honoured by the resource assignment
     * @param plans      the plans being improved; its elements are replaced when a move is accepted
     * @param firstPlan  an element of {@code plans}
     * @param secondPlan another element of {@code plans}
     * @param i          a position in {@code firstPlan}, up to its {@code getCount()}
     * @param j          a position in {@code secondPlan}, up to its {@code getCount()}
     * @return {@code true} if a move was accepted and {@code plans} changed
     * @throws CancelException     if the search was cancelled
     * @throws ArithmeticException if a summed cost does not fit a {@code long}
     */
    @Override
    protected boolean apply(List<P> otherPlans, List<P> plans, P firstPlan, P secondPlan, int i, int j) {
        boolean result = super.apply(otherPlans, plans, firstPlan, secondPlan, i, j);
        if (result) {
            return true;
        }
        return super.apply(otherPlans, plans, secondPlan, firstPlan, j, i);
    }

    /**
     * The slot right behind the last customer: in front of the destination, or the end of an open plan. For a plan
     * without customers it is the plan's only slot: behind its origin and in front of its destination (position 0 when
     * it has neither), the slot the insertion algorithm uses as well.
     */
    private int appendSlot(P plan) {
        return plan.getDestination() == null ? plan.getCount() : plan.getCount() - 1;
    }

    private boolean isAppendSlot(P plan, int position) {
        return type != ExchangeRelocateType.INSERTION && position == appendSlot(plan);
    }

    /**
     * Under {@link ExchangeRelocateType#SIMPLE} and {@link ExchangeRelocateType#TWO_OPT} also admits the append slot of
     * the target plan (right behind its last customer, or the only slot of a plan without customers). Under
     * {@link ExchangeRelocateType#INSERTION} admits every target plan, whatever its number of stops: the insertion
     * algorithm chooses the positions itself, so the target range is ignored.
     *
     * @param secondPlan the target plan
     * @param start      the target position
     * @param end        the exclusive end of the target range
     * @return {@code true} under {@code INSERTION}; otherwise {@code true} if the range lies within the plan's stops or
     *         {@code start} is the append slot
     */
    @Override
    protected boolean supportsSecondPlanRange(P secondPlan, int start, int end) {
        return type == ExchangeRelocateType.INSERTION || super.supportsSecondPlanRange(secondPlan, start, end)
                || isAppendSlot(secondPlan, start);
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
        if (firstPlan.getNodes().subList(firstPlanStart, firstPlanEnd)
                .stream().anyMatch(n -> n.getType() != Nodeable.Type.CUSTOMER)
                || (type != ExchangeRelocateType.INSERTION && !append
                && secondPlan.getNodes().subList(secondPlanStart, secondPlanEnd).stream()
                .anyMatch(n -> n.getType() != Nodeable.Type.CUSTOMER))) {
            return false;
        }
        return super.supports(firstPlan, secondPlan, firstPlanStart, secondPlanStart, firstPlanEnd, secondPlanEnd);
    }

    @Override
    protected boolean supportsNeighbor(P firstPlan, P secondPlan, int firstPlanStart, int secondPlanStart) {
        // An insertion picks its own positions, and a plan without customers offers a single slot, so there is no
        // target stop to rank: both are always eligible.
        if (type == ExchangeRelocateType.INSERTION || secondPlan.getCustomerCount() == 0) {
            return true;
        }
        // The append slot follows the last customer, so that customer is the neighbour the segment joins.
        int anchor = isAppendSlot(secondPlan, secondPlanStart) ? secondPlanStart - 1 : secondPlanStart;
        return isAmongNeighbors(firstPlan, firstPlan.getNodes().get(firstPlanStart),
                secondPlan.getNodes().get(anchor));
    }

    @Override
    protected String getMessage(P firstPlan, P secondPlan) {
        return "Relocate plan";
    }

}
