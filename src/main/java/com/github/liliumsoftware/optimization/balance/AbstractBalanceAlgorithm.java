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

package com.github.liliumsoftware.optimization.balance;

import com.github.liliumsoftware.optimization.BaseAlgorithm;
import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.calculation.CostCalculation;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.domain.Planable;
import com.github.liliumsoftware.optimization.exception.CancelException;
import com.github.liliumsoftware.optimization.split.ZoneGroups;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * Base for load balancing between plans: moving customers from loaded plans into light ones, trading cost for
 * evenness. Subclasses decide what "balanced" means ({@link AbstractNodeBalanceAlgorithm} for customer counts,
 * {@link AbstractCapacityBalanceAlgorithm} for a domain capacity measure) and implement {@link #doOperation}.
 *
 * <p>{@link #apply(List)} partitions the plans into independent zone groups (so balancing never moves work across
 * non-neighbouring zones) and balances the groups through {@link ZoneGroups}: first in parallel, each group limited to
 * the resources its own plans hold, then, for the groups that wanted an idle resource, once more one after the other in
 * input order against the current plans of the other groups. No resource is booked twice and the result does not
 * depend on thread timing. Balancing never modifies the list passed in or its plan objects; it returns a new list.
 *
 * <p><strong>Thread-safety.</strong> No mutable state beyond the signal flags; one instance may run on independent
 * lists concurrently, and the collaborators must be thread-safe because zone groups run in parallel.
 *
 * @param <P> the plan type
 * @param <N> the node type
 */
@RequiredArgsConstructor
public abstract class AbstractBalanceAlgorithm<P
        extends Planable<P, N, ?>, N extends Nodeable<N>> extends BaseAlgorithm {

    /**
     * Supplies the zone neighbourhood that decides which customers may move into a plan, and the zone grouping.
     */
    protected final PathCostCalculation<P, N, ?> costCalculation;
    /**
     * Assigns the resources of every candidate plan set; a transfer whose plans cannot all be assigned is rejected.
     */
    protected final TrackableAssignment<P, ?> trackableAssignment;

    /**
     * Position of {@code element} in {@code list} by identity, or {@code -1}; balancing locates plans by position so
     * that it does not depend on the consumer's {@code equals}.
     */
    static int indexOfInstance(List<?> list, Object element) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == element) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Position of the node with {@code code} in {@code nodes}, or {@code -1}; customers are located by their unique
     * code, so a {@link Planable#getCustomers()} that returns copies rather than the route's own instances still works.
     */
    static int indexOfCode(List<? extends Nodeable<?>> nodes, String code) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).getCode().equals(code)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The plan positions ordered by ascending {@code measure} (stable, so ties keep list order).
     */
    static <P> List<Integer> ascendingPositions(List<P> plans, ToLongFunction<P> measure) {
        List<Integer> positions = new ArrayList<>(plans.size());
        for (int i = 0; i < plans.size(); i++) {
            positions.add(i);
        }
        positions.sort(Comparator.comparingLong(i -> measure.applyAsLong(plans.get(i))));
        return positions;
    }

    /**
     * Balances {@code plans} zone group by zone group through {@link ZoneGroups} (see the class description).
     *
     * @param plans the plans to balance; neither the list nor its plan objects are modified
     * @return a new list holding every group's result, groups in the order of their first plan in {@code plans}
     * @throws CancelException     if the balancing was cancelled
     * @throws ArithmeticException if a summed cost or capacity does not fit a {@code long}
     */
    public List<P> apply(List<P> plans) {
        return ZoneGroups.apply(costCalculation, trackableAssignment, Math.max(1, plans.size()), plans,
                this::doOperation);
    }

    /**
     * Balances one zone group against the plans of the other groups. Implementations must not modify {@code plans} or
     * its plan objects, and must assign every candidate plan set with
     * {@link TrackableAssignment#assignGreedy(List, List) trackableAssignment.assignGreedy(otherPlans, candidate)},
     * which keeps the resources of {@code otherPlans} out of reach.
     *
     * @param otherPlans the plans of the other zone groups; not modified
     * @param plans      the group to balance; not modified
     * @return the balanced plans, or {@code plans} itself when nothing was moved
     * @throws CancelException if the balancing was cancelled
     */
    public abstract List<P> doOperation(List<P> otherPlans, List<P> plans);

    /**
     * The customers of {@code donors} that may move into {@code minPlan}, each mapped to the donor it comes from, in
     * donor and route order. The default implementation admits a customer when its zone is a neighbour of
     * {@code minPlan}'s ({@link PathCostCalculation#isNeighbor}). The balancing subclasses pass only the admissible
     * donors; an override must map every customer to one of the given donor instances.
     *
     * @param donors  copies of the plans that may give up a customer; not modified
     * @param minPlan a copy of the plan that receives a customer
     * @return the candidate customers and their donors, in a map with a deterministic iteration order
     */
    protected Map<N, P> getOtherPlanNodes(List<P> donors, P minPlan) {
        Map<N, P> planNodes = new LinkedHashMap<>();   // insertion order keeps candidate evaluation deterministic
        donors.forEach(plan -> plan.getCustomers().stream()
                .filter(node -> costCalculation.isNeighbor(minPlan.getZone(), node.getZone()))
                .forEach(node -> planNodes.put(node, plan)));
        return planNodes;
    }

    /**
     * Orders candidate plan sets; each balancing step keeps the minimum, and ties in evenness between states seen are
     * broken by it. The default implementation orders by ascending {@link CostCalculation#totalCost summed cost}, which
     * throws {@link ArithmeticException} when a sum does not fit a {@code long}.
     *
     * @return the comparator of candidate plan sets
     */
    protected Comparator<List<P>> plansComparator() {
        return Comparator.comparingLong(CostCalculation::totalCost);
    }

    /**
     * The message logged at DEBUG level for a balancing step that moves a customer into {@code plan}; only called when
     * DEBUG is enabled.
     *
     * @param plan the receiving plan, before the transfer
     * @return the log message
     */
    protected abstract String getMessage(P plan);
}
