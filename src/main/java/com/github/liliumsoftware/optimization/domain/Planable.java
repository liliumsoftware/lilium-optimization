package com.github.liliumsoftware.optimization.domain;

import java.util.List;

/**
 * A single route/plan: an ordered sequence of stops executed by one {@link Trackable} resource.
 *
 * <p>This is one corner of the self-referential {@code <P, N, T>} triple every algorithm in the
 * library is parameterized by. Implementations are supplied by the consuming application against
 * its own entities; nothing concrete lives in this library.
 *
 * <p><strong>Contract the algorithms rely on.</strong> The node list is expected to be laid out as
 * {@code ORIGIN, CUSTOMER…, DESTINATION}, and either depot stop may be omitted: an open plan has no destination, and a
 * plan may start directly at its first customer ({@code CUSTOMER…, DESTINATION}). Insertion, destroy-repair, relocate,
 * 2-opt and the merge algorithms treat every position between the stops that are present as movable. Every move in the
 * library is built by {@link #copy() copying} plans and mutating the copies, and accepted by comparing costs; loops
 * terminate on accepted-move progress, never on plan equality. A few steps still locate a plan in its list with
 * {@code equals} ({@code indexOf}, {@code contains}, {@code remove(Object)} in insertion, exchange/relocate, merge and
 * assignment), always looking for an element of that very list, so value-, code- or identity-based equality all work
 * as long as two different plans of one run are never equal: plan codes must be unique. The copy must be a real deep
 * {@link #copy()}; a shallow copy that shares the node list will corrupt search state.
 *
 * @param <P> the concrete plan type (self type)
 * @param <N> the stop type carried by this plan
 * @param <T> the resource type executing this plan
 */
public interface Planable<P extends Planable<P, N, T>, N extends Nodeable<N>, T extends Trackable<T>> {

    /**
     * Unique identity of this plan within a run; drives {@code equals}-based lookups in the algorithms.
     */
    String getCode();

    /**
     * The ordered stops. The list must be mutable: moves insert into and remove from it on copies of the plan.
     */
    List<N> getNodes();

    /**
     * The resource currently assigned to this plan, or {@code null} while the plan is unassigned.
     */
    T getTrackable();

    /**
     * Total cost of traversing {@link #getNodes()} in order; the objective the algorithms minimize.
     */
    long getCost();

    /**
     * A deep, independent copy. Mutating the result must never affect this instance (or vice versa).
     */
    P copy();

    /**
     * Zone of this plan, used for zone-restricted optimization; defaults to the shared {@code "NoZone"}.
     */
    default String getZone() {
        return "NoZone";
    }

    /**
     * Number of stops including origin/destination; {@code getNodes().size()} by default.
     */
    default int getCount() {
        return getNodes().size();
    }

    /**
     * Number of {@link Nodeable.Type#CUSTOMER} stops.
     */
    default int getCustomerCount() {
        return (int) getNodes().stream().filter(n -> n.getType() == Nodeable.Type.CUSTOMER).count();
    }

    /**
     * The {@link Nodeable.Type#ORIGIN} stop, or {@code null} if none.
     */
    default N getOrigin() {
        return getNodes().stream().filter(n -> n.getType() == Nodeable.Type.ORIGIN).findAny().orElse(null);
    }

    /**
     * The {@link Nodeable.Type#DESTINATION} stop, or {@code null} for an open plan.
     */
    default N getDestination() {
        return getNodes().stream().filter(n -> n.getType() == Nodeable.Type.DESTINATION).findAny().orElse(null);
    }

    /**
     * The customer stops in route order (a new unmodifiable list).
     */
    default List<N> getCustomers() {
        return getNodes().stream().filter(n -> n.getType() == Nodeable.Type.CUSTOMER).toList();
    }

    /**
     * Whether the resource returns to origin, i.e. the plan is a closed tour rather than an open path. Closedness is
     * a property of the resource, so a plan must be assigned before it is asked.
     *
     * @throws NullPointerException if no resource is assigned
     */
    default boolean isClosed() {
        return getTrackable().isReturns();
    }

    /**
     * Whether this plan is a returning resource's second run; see the {@code assignment} package.
     */
    default boolean isSecondDispatch() {
        return false;
    }

}
