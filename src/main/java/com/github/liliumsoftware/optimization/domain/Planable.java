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

package com.github.liliumsoftware.optimization.domain;

import java.util.List;

/**
 * A single route/plan: an ordered sequence of stops executed by one {@link Trackable} resource.
 *
 * <p>This is one corner of the self-referential {@code <P, N, T>} triple every algorithm in the library is
 * parameterized by. Implementations are supplied by the consuming application against its own entities; nothing
 * concrete lives in this library.
 *
 * <p><strong>Layout.</strong> The node list is expected to be laid out as {@code ORIGIN, CUSTOMER…, DESTINATION},
 * and either depot stop may be omitted: an open plan has no destination, and a plan may start directly at its first
 * customer ({@code CUSTOMER…, DESTINATION}). Insertion, destroy-repair, relocate, 2-opt and the merge algorithms
 * treat every position between the depot stops that are present as movable.
 *
 * <p><strong>Copies and cost.</strong> Every move in the library is built by {@link #copy() copying} plans and
 * mutating the copies' node lists, and accepted by comparing {@link #getCost()}; the plans passed in are never
 * modified. The copy must therefore be a real deep copy (a shallow copy that shares the node list corrupts the
 * search), and {@link #getCost()} must reflect the node list as it is when read.
 *
 * <p><strong>Equality.</strong> Loops terminate on accepted-move progress, never on plan equality. A few steps still
 * locate a plan in its list with {@code equals} ({@code indexOf}, {@code contains}, {@code remove(Object)} in
 * insertion, exchange/relocate, merge/un-merge and assignment), always looking for an element of that very list, so
 * value-, code- or identity-based equality all work as long as two different plans of one run are never equal: plan
 * codes must be unique within a run.
 *
 * @param <P> the concrete plan type (self type)
 * @param <N> the stop type carried by this plan
 * @param <T> the resource type executing this plan
 */
public interface Planable<P extends Planable<P, N, T>, N extends Nodeable<N>, T extends Trackable<T>> {

    /**
     * Unique identity of this plan within a run; plans sharing a code are treated as the same plan.
     *
     * @return the code
     */
    String getCode();

    /**
     * The ordered stops. The list must be mutable and owned by this plan: moves insert into and remove from it on
     * copies of the plan.
     *
     * @return the live, mutable node list
     */
    List<N> getNodes();

    /**
     * The resource currently assigned to this plan.
     *
     * @return the assigned resource, or {@code null} while the plan is unassigned
     */
    T getTrackable();

    /**
     * Total cost of traversing {@link #getNodes()} in order: the objective the algorithms minimize. It must equal
     * {@link com.github.liliumsoftware.optimization.calculation.CostCalculation#calculateCost(Planable)} for the
     * <em>current</em> node list, i.e. the sum of the consecutive edge costs (plan-aware edge costs included). Derive
     * it from the nodes when read rather than returning a value cached before the last change: several moves mutate
     * the node lists of copies and compare {@code getCost()} directly, without recosting first, so a stale value makes
     * them accept or reject the wrong moves.
     *
     * @return the cost of the current node sequence
     */
    long getCost();

    /**
     * A deep, independent copy. Mutating the result (its node list, its nodes, its resource) must never affect this
     * instance, or vice versa.
     *
     * @return the copy
     */
    P copy();

    /**
     * Zone of this plan, consulted by the zone predicates of
     * {@link com.github.liliumsoftware.optimization.calculation.PathCostCalculation} when moving customers between
     * plans, and by the zone splitter for a plan without customers.
     *
     * <p>The default returns {@code "NoZone"}, the default zone of {@link Nodeable#getZone()}.
     *
     * @return the zone name, or {@code null} for an unrestricted plan
     */
    default String getZone() {
        return "NoZone";
    }

    /**
     * Number of stops, origin and destination included.
     *
     * <p>The default returns {@code getNodes().size()}.
     *
     * @return the stop count
     */
    default int getCount() {
        return getNodes().size();
    }

    /**
     * Number of {@link Nodeable.Type#CUSTOMER} stops.
     *
     * @return the customer count
     */
    default int getCustomerCount() {
        return (int) getNodes().stream().filter(n -> n.getType() == Nodeable.Type.CUSTOMER).count();
    }

    /**
     * The {@link Nodeable.Type#ORIGIN} stop.
     *
     * @return the origin, or {@code null} if the plan has none
     */
    default N getOrigin() {
        return getNodes().stream().filter(n -> n.getType() == Nodeable.Type.ORIGIN).findAny().orElse(null);
    }

    /**
     * The {@link Nodeable.Type#DESTINATION} stop.
     *
     * @return the destination, or {@code null} for an open plan
     */
    default N getDestination() {
        return getNodes().stream().filter(n -> n.getType() == Nodeable.Type.DESTINATION).findAny().orElse(null);
    }

    /**
     * The customer stops in route order.
     *
     * @return a new unmodifiable list; later changes to the plan are not reflected in it
     */
    default List<N> getCustomers() {
        return getNodes().stream().filter(n -> n.getType() == Nodeable.Type.CUSTOMER).toList();
    }

    /**
     * Whether the plan is a closed tour, i.e. its resource returns to the origin ({@link Trackable#isReturns()}).
     * Closedness is a property of the resource, so a plan must be assigned before it is asked.
     *
     * @return the assigned resource's {@link Trackable#isReturns()}
     * @throws NullPointerException if no resource is assigned
     */
    default boolean isClosed() {
        return getTrackable().isReturns();
    }

    /**
     * Whether this plan is a returning resource's second run; see
     * {@link com.github.liliumsoftware.optimization.assignment.SecondDispatchTrackableAssignment}.
     *
     * <p>The default returns {@code false}.
     *
     * @return {@code true} for a second-run plan
     */
    default boolean isSecondDispatch() {
        return false;
    }

}
