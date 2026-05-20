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
import com.github.liliumsoftware.optimization.insertion.AbstractInsertionAlgorithm;
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Inter-plan <em>exchange</em>: swaps a segment of one plan with a segment of another (both plans give up a
 * chain and receive one), accepting only feasible, cost-reducing swaps. Candidate targets are limited to the
 * source node's {@code neighbors} nearest neighbours in the same/neighbouring zone, so the O(plans² × nodes²)
 * search stays tractable. Only customer segments are eligible; depot endpoints never move.
 */
public abstract class AbstractExchangeAlgorithm<P
        extends Planable<P, N, ?>, N extends Nodeable<N>> extends AbstractExchangeRelocateAlgorithm<P, N> {

    /**
     * Creates an exchange move.
     *
     * @param type      how removed segments are re-added
     * @param neighbors how many nearest neighbours of a segment start qualify as targets
     * @param chain     maximum segment length moved from either plan
     * @param cross     {@code true} to let the second plan's segment be up to {@code chain} stops long as well;
     *                  {@code false} to swap the first plan's segment against a single stop of the second plan (it is
     *                  still a two-way swap, not a one-way move — that is {@link AbstractRelocateAlgorithm})
     */
    public AbstractExchangeAlgorithm(PathCostCalculation<P, N, ?> pathCostCalculation,
            TrackableAssignment<P, ?> trackableAssignment, LocalSearchAlgorithm<P> localSearchAlgorithm,
            AbstractInsertionAlgorithm<P, N, ?> insertionAlgorithm, ExchangeRelocateType type, int neighbors, int chain,
            boolean cross) {
        super(pathCostCalculation, trackableAssignment, localSearchAlgorithm, insertionAlgorithm, type, neighbors,
                chain, cross);
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
        if (!super.supports(firstPlan, secondPlan, firstPlanStart, secondPlanStart, firstPlanEnd, secondPlanEnd)) {
            return false;
        }
        N firstNode = firstPlan.getNodes().get(firstPlanStart);
        N secondNode = secondPlan.getNodes().get(secondPlanStart);
        Predicate<N> excludePredicate = e -> firstPlan.getNodes().stream().noneMatch(n -> Objects.equals(e.getCode(),
                n.getCode()));
        Predicate<N> neighborsPredicate = n -> costCalculation.isNeighbor(n.getZone(), firstNode.getZone());
        return costCalculation.getClosestNodes(firstNode.getCode()).stream()
                .filter(excludePredicate) // exclude nodes on the same plan
                .filter(neighborsPredicate) // exclude non-neighbors
                .limit(neighbors) // use only first neighbors
                .anyMatch(n -> Objects.equals(n.getCode(), secondNode.getCode())); // check if target matches
    }

    @Override
    protected String getMessage(P firstPlan, P secondPlan) {
        return "Exchange plan";
    }

}
