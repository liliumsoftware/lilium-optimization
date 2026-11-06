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

package com.github.liliumsoftware.optimization.calculation;

import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.domain.Planable;

import java.util.Collection;
import java.util.List;

/**
 * The cost model the algorithms optimize against. A plan's cost is, by definition, the sum of the costs of its
 * consecutive edges, and {@link Planable#getCost()} must agree with {@link #calculateCost(Planable)}: this is the
 * single invariant every move relies on when it accepts a candidate only if the summed cost improves.
 *
 * <p>Implementors normally supply the edge cost {@link #calculateCost(Nodeable, Nodeable)} (see
 * {@link PathCostCalculation}, the matrix-backed implementation every algorithm takes); the plan-level and
 * context-aware overloads have working defaults. The {@code (plan, start, end)} overload is the hook for costs that
 * depend on the enclosing plan (e.g. the assigned resource) rather than on the edge alone.
 *
 * <p><strong>Overflow.</strong> Costs are {@code long}. Every sum the library forms (a plan's edges here, a plan set's
 * costs in {@link #totalCost}, the medoid sums of {@code PathCostCalculation}) is checked: a sum that does not fit a
 * {@code long} throws {@link ArithmeticException} instead of wrapping to a negative value that every "is it cheaper"
 * comparison would mistake for a huge saving.
 *
 * @param <P> the plan type
 * @param <N> the node type
 */
public interface CostCalculation<P extends Planable<P, N, ?>, N extends Nodeable<N>> {

    /**
     * The summed {@link Planable#getCost()} of {@code plans}: the objective the inter-plan algorithms compare.
     *
     * @param plans the plans to sum; not modified
     * @return the total cost, {@code 0} for an empty collection
     * @throws ArithmeticException if the sum does not fit a {@code long}
     */
    static long totalCost(Collection<? extends Planable<?, ?, ?>> plans) {
        long total = 0;
        for (Planable<?, ?, ?> plan : plans) {
            total = CostSums.add(total, plan.getCost(), "plan set containing", plan.getCode());
        }
        return total;
    }

    /**
     * Cost of the directed edge {@code start -> end}.
     *
     * <p>The default throws {@link UnsupportedOperationException}; implementations that rely on the default
     * {@link #calculateCost(Planable, Nodeable, Nodeable)} must override it.
     *
     * @param start the node the edge leaves
     * @param end   the node the edge enters
     * @return the edge cost
     * @throws UnsupportedOperationException unless overridden
     */
    default long calculateCost(N start, N end) {
        throw new UnsupportedOperationException("Not Implemented");
    }

    /**
     * The cost of {@code plan}'s current node sequence: the sum of
     * {@link #calculateCost(Planable, Nodeable, Nodeable)} over each consecutive node pair of
     * {@link Planable#getNodes()}. The loop runs over the node list itself ({@code getNodes().size()}), not over
     * {@link Planable#getCount()}, so an overridden {@code getCount} cannot make it skip edges or read past the list.
     * The 2-opt searches and insertion cost their candidate copies with it.
     *
     * @param plan the plan to cost; not modified by the default
     * @return the summed edge costs, {@code 0} for a plan with fewer than two stops
     * @throws ArithmeticException if the sum does not fit a {@code long}
     */
    default long calculateCost(P plan) {
        List<N> nodes = plan.getNodes();
        long cost = 0;
        for (int i = 0; i < nodes.size() - 1; i++) {
            cost = CostSums.add(cost, calculateCost(plan, nodes.get(i), nodes.get(i + 1)), "plan", plan.getCode());
        }
        return cost;
    }

    /**
     * Cost of the directed edge {@code start -> end} as part of {@code plan}: the hook for costs that depend on the
     * enclosing plan, such as its assigned resource.
     *
     * <p>The default ignores {@code plan} and returns the plan-independent
     * {@link #calculateCost(Nodeable, Nodeable)}.
     *
     * @param plan  the plan the edge belongs to
     * @param start the node the edge leaves
     * @param end   the node the edge enters
     * @return the edge cost
     */
    default long calculateCost(P plan, N start, N end) {
        return calculateCost(start, end);
    }

}
