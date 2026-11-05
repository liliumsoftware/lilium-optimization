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
 * Inter-plan <em>exchange</em>: swaps a segment of one plan with a segment of another, so both plans give up a chain of
 * customers and receive one, accepting only assignable, cost-reducing swaps. The search, acceptance, local search and
 * signal handling are those of {@link AbstractExchangeRelocateAlgorithm}.
 *
 * <p>Every plan pair is tried in both orientations: either plan's segment may be the first one. A swap of
 * {@code [i, i + x)} of the first plan with {@code [j, j + y)} of the second plan is tried only when both segments
 * consist of customers (depots never move), the plans' zones are neighbours, and the first stop of the second segment
 * is among the {@code neighbors} nearest nodes of the first stop of the first segment (see the class description of
 * {@link AbstractExchangeRelocateAlgorithm}). {@code x} ranges up to {@code chain}; {@code y} up to {@code chain} with
 * {@code cross}, otherwise {@code y = 1}, so without {@code cross} a multi-stop segment of either plan may be swapped
 * against a single customer of the other. The segments are re-added as the
 * {@link ExchangeRelocateType} prescribes:
 * <ul>
 *   <li>{@code SIMPLE}: each segment takes the other's place, in its original order;</li>
 *   <li>{@code TWO_OPT}: as {@code SIMPLE}, then both plans go through the local search before the swap is judged;</li>
 *   <li>{@code INSERTION}: the first segment's customers are inserted into the second plan, then the second segment's
 *       into the first plan, each by the insertion algorithm restricted to that plan; the swap is rejected unless
 *       every customer is placed.</li>
 * </ul>
 *
 * @param <P> the plan type
 * @param <N> the node type
 */
public abstract class AbstractExchangeAlgorithm<P
        extends Planable<P, N, ?>, N extends Nodeable<N>> extends AbstractExchangeRelocateAlgorithm<P, N> {

    /**
     * Creates an exchange move.
     *
     * @param pathCostCalculation  supplies the nearest-node ranking and zone neighbourhood; must be initialized with
     *                             every node of the plans
     * @param trackableAssignment  re-assigns the resources of every candidate plan set
     * @param localSearchAlgorithm improves the touched plans and, after every accepted swap, every plan; must not
     *                             modify its argument
     * @param insertionAlgorithm   re-inserts the swapped segments under {@link ExchangeRelocateType#INSERTION}
     * @param type                 how the swapped segments are re-added
     * @param neighbors            how many nearest nodes of a segment's first stop qualify as swap partners
     * @param chain                the maximum number of stops in a swapped segment
     * @param cross                {@code true} to let the second plan's segment be up to {@code chain} stops long as
     *                             well; {@code false} to swap the first plan's segment against a single customer of the
     *                             second plan (still a two-way swap, not a one-way move: that is
     *                             {@link AbstractRelocateAlgorithm})
     */
    public AbstractExchangeAlgorithm(PathCostCalculation<P, N, ?> pathCostCalculation,
            TrackableAssignment<P, ?> trackableAssignment, LocalSearchAlgorithm<P> localSearchAlgorithm,
            AbstractInsertionAlgorithm<P, N, ?> insertionAlgorithm, ExchangeRelocateType type, int neighbors, int chain,
            boolean cross) {
        super(pathCostCalculation, trackableAssignment, localSearchAlgorithm, insertionAlgorithm, type, neighbors,
                chain, cross);
    }

    /**
     * Tries the swap in both orientations: first with {@code firstPlan}'s segment starting at {@code i} as the first
     * segment and {@code secondPlan}'s segment starting at {@code j} as the second, then, if that was not accepted,
     * the other way round. The orientation decides which plan's segment starts the neighbour test and, without
     * {@code cross}, which plan gives up the segment of up to {@code chain} stops (the other gives up a single
     * customer), so both plans of a pair get both roles.
     *
     * @param otherPlans the plans of the other zone groups, honoured by the resource assignment
     * @param plans      the plans being improved; its elements are replaced when a move is accepted
     * @param firstPlan  an element of {@code plans}
     * @param secondPlan another element of {@code plans}
     * @param i          a position in {@code firstPlan}
     * @param j          a position in {@code secondPlan}
     * @return {@code true} if a move was accepted and {@code plans} changed
     * @throws CancelException     if the search was cancelled
     * @throws ArithmeticException if a summed cost does not fit a {@code long}
     */
    @Override
    protected boolean apply(List<P> otherPlans, List<P> plans, P firstPlan, P secondPlan, int i, int j) {
        return super.apply(otherPlans, plans, firstPlan, secondPlan, i, j)
                || super.apply(otherPlans, plans, secondPlan, firstPlan, j, i);
    }

    @Override
    protected boolean doOperation(List<P> plans, P firstPlan, P secondPlan, int firstPlanStart, int secondPlanStart,
            int firstPlanEnd, int secondPlanEnd) {
        List<N> firstPlanSwapCandidates = new ArrayList<>(firstPlan.getNodes().subList(firstPlanStart, firstPlanEnd));
        List<N> secondPlanSwapCandidates = new ArrayList<>(secondPlan.getNodes().subList(secondPlanStart,
                secondPlanEnd));

        firstPlanSwapCandidates.forEach(n -> firstPlan.getNodes().remove(firstPlan.getNodes().indexOf(n)));
        secondPlanSwapCandidates.forEach(n -> secondPlan.getNodes().remove(secondPlan.getNodes().indexOf(n)));

        return switch (type) {
            case TWO_OPT -> {
                firstPlan.getNodes().addAll(firstPlanStart, secondPlanSwapCandidates);
                secondPlan.getNodes().addAll(secondPlanStart, firstPlanSwapCandidates);
                plans.set(plans.indexOf(firstPlan), localSearchAlgorithm.apply(firstPlan));
                plans.set(plans.indexOf(secondPlan), localSearchAlgorithm.apply(secondPlan));
                yield true;
            }
            case INSERTION -> {
                List<N> firstInsertedNodes = insertionAlgorithm.insert(plans, firstPlanSwapCandidates,
                        plans.indexOf(secondPlan));
                if (firstInsertedNodes.size() != firstPlanSwapCandidates.size()) {
                    yield false;
                }
                List<N> secondInsertedNodes = insertionAlgorithm.insert(plans, secondPlanSwapCandidates,
                        plans.indexOf(firstPlan));
                yield secondInsertedNodes.size() == secondPlanSwapCandidates.size();
            }
            case SIMPLE -> {
                firstPlan.getNodes().addAll(firstPlanStart, secondPlanSwapCandidates);
                secondPlan.getNodes().addAll(secondPlanStart, firstPlanSwapCandidates);
                yield true;
            }
        };
    }

    @Override
    protected boolean supports(P firstPlan, P secondPlan, int firstPlanStart, int secondPlanStart, int firstPlanEnd,
            int secondPlanEnd) {
        if (firstPlan.getNodes().subList(firstPlanStart, firstPlanEnd)
                .stream().anyMatch(n -> n.getType() != Nodeable.Type.CUSTOMER)
                || secondPlan.getNodes().subList(secondPlanStart, secondPlanEnd).stream()
                .anyMatch(n -> n.getType() != Nodeable.Type.CUSTOMER)) {
            return false;
        }
        return super.supports(firstPlan, secondPlan, firstPlanStart, secondPlanStart, firstPlanEnd, secondPlanEnd);
    }

    @Override
    protected boolean supportsNeighbor(P firstPlan, P secondPlan, int firstPlanStart, int secondPlanStart) {
        return isAmongNeighbors(firstPlan, firstPlan.getNodes().get(firstPlanStart),
                secondPlan.getNodes().get(secondPlanStart));
    }

    @Override
    protected String getMessage(P firstPlan, P secondPlan) {
        return "Exchange plan";
    }

}
