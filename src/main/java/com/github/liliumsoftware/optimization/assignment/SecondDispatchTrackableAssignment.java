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

package com.github.liliumsoftware.optimization.assignment;

import com.github.liliumsoftware.optimization.domain.Planable;
import com.github.liliumsoftware.optimization.domain.Trackable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Assignment variant that lets a returning resource be re-used for a second run. Both mutators replace the current
 * pool ({@link #currentTrackables}) that the inherited {@code assign…} entry points draw from:
 * {@link #secondDispatchOnly} narrows it to the units that may run again after a first planning round, and
 * {@link #secondDispatchAndOthers} separates each re-dispatched unit's first run from the rest of the plans and resets
 * the pool to the whole fleet. By default ({@link #supportSecondDispatch}) a held unit qualifies for a second run
 * when its {@link Trackable#isReturns()} and {@link Trackable#isSupportSecondDispatch()} hold; a plan marks itself as
 * a second run through {@link Planable#isSecondDispatch()}. Units are told apart by {@code equals} (code and index),
 * so the expanded units of one multi-unit resource stay independent: re-dispatching one of them neither removes nor
 * re-admits its siblings.
 *
 * <p>The mutators are synchronized, but the {@code assign…} entry points are not: do not call the mutators while an
 * assignment on this instance is in flight.
 *
 * @param <P> the plan type
 * @param <T> the resource type being assigned
 */
public abstract class SecondDispatchTrackableAssignment<P
        extends Planable<P, ?, T>, T extends Trackable<T>> extends TrackableAssignment<P, T> {

    /**
     * Creates the assignment over {@code trackables}.
     *
     * @param trackables the fleet, expanded as described in {@link TrackableAssignment#TrackableAssignment(List)};
     *                   resources must report {@link Trackable#isReturns()} and
     *                   {@link Trackable#isSupportSecondDispatch()} to be eligible for a second run
     */
    public SecondDispatchTrackableAssignment(List<T> trackables) {
        super(trackables);
    }

    /**
     * Narrows the current pool to the units that may run again. The new pool holds the units of the current pool that
     * no plan of {@code plans} holds, followed by the units the plans hold, each as
     * {@link #getTrackableForSecondDispatch} gives it (a fresh copy by default) and each unit once even when a first
     * and a second run both hold it; then every unit that {@link #supportSecondDispatch} rejects is dropped. Plans
     * without a trackable contribute nothing. A held unit enters the pool even if the current pool lacked it.
     *
     * @param plans the plans planned so far; not modified
     */
    public synchronized void secondDispatchOnly(List<P> plans) {
        List<T> plannedTrackables = plans.stream().filter(p -> p.getTrackable() != null)
                .map(this::getTrackableForSecondDispatch).distinct().toList();
        List<T> allTrackables = currentTrackables.stream().filter(t -> !plannedTrackables.contains(t))
                .collect(Collectors.toList());
        allTrackables.addAll(plannedTrackables);
        allTrackables.removeIf(t -> !this.supportSecondDispatch(plans, t));
        currentTrackables = allTrackables;
    }

    /**
     * Separates the first runs of re-dispatched units from the other plans and resets the pool. A first run is a plan
     * that is not a second run but whose unit is also held by a plan of {@code plans} marked
     * {@link Planable#isSecondDispatch()}. First runs are left out by identity, so a plan that merely {@code equals} a
     * first run stays. The new pool is:
     * <ol>
     * <li>the units of the expanded fleet ({@link #getTrackables()}) that no plan of {@code plans} holds, in fleet
     * order, followed by</li>
     * <li>the unit instances held by the remaining plans (those that are not a first run), in plan order, each unit
     * once: a unit {@code equals} to one already listed (because several remaining plans hold it) is skipped, so the
     * instance of the first plan holding it is kept.</li>
     * </ol>
     * No eligibility check is made ({@link #supportSecondDispatch} is not consulted), and an earlier
     * {@link #secondDispatchOnly} narrowing is discarded. Since a first run's unit is also held by its second run,
     * every unit held in {@code plans} comes back exactly once, so when the plans hold fleet units the pool holds every
     * unit of the fleet exactly once, with the held units at its end.
     *
     * @param plans          the planned plans, each with a trackable; not modified
     * @param secondDispatch further plans to plan alongside; appended to the result as given
     * @return a new mutable list: the plans of {@code plans} that are not a first run (the same objects), in their
     *         order, followed by {@code secondDispatch}
     * @throws NullPointerException if a plan in {@code plans} has no trackable (the message names the plan)
     */
    public synchronized List<P> secondDispatchAndOthers(List<P> plans, List<P> secondDispatch) {
        for (P plan : plans) {
            // a null unit would otherwise enter the pool and fail much later, inside a comparator
            Objects.requireNonNull(plan.getTrackable(), () -> "Plan " + plan.getCode() + " has no trackable");
        }
        List<T> secondDispatchTrackables = plans.stream()
                .filter(p -> p.isSecondDispatch())
                .map(P::getTrackable)
                .toList();
        // first runs are left out by position, never through Planable#equals
        List<P> secondPlans = plans.stream()
                .filter(p -> p.isSecondDispatch() || !secondDispatchTrackables.contains(p.getTrackable()))
                .collect(Collectors.toList());

        List<T> allTrackables = new ArrayList<>(trackables);
        allTrackables.removeIf(t -> plans.stream().anyMatch(p -> t.equals(p.getTrackable())));
        for (P plan : secondPlans) {
            if (!allTrackables.contains(plan.getTrackable())) {
                allTrackables.add(plan.getTrackable());
            }
        }
        currentTrackables = allTrackables;

        secondPlans.addAll(secondDispatch);
        return secondPlans;
    }

    /**
     * Whether {@code trackable} may stay in the pool built by {@link #secondDispatchOnly}.
     *
     * <p>By default: no plan of {@code plans} holds it, or it {@link Trackable#isReturns() returns} and
     * {@link Trackable#isSupportSecondDispatch() supports a second dispatch}.
     *
     * @param plans     the plans passed to {@link #secondDispatchOnly}
     * @param trackable a unit of the new pool
     * @return {@code true} to keep the unit
     */
    protected boolean supportSecondDispatch(List<P> plans, T trackable) {
        return plans.stream().noneMatch(p -> trackable.equals(p.getTrackable()))
                || (trackable.isReturns() && trackable.isSupportSecondDispatch());
    }

    /**
     * The unit of {@code plan} as it enters the pool for its second run in {@link #secondDispatchOnly}; must be
     * {@code equals} to the plan's unit, since the pool and {@link #supportSecondDispatch} compare by {@code equals}.
     * By default a {@link Trackable#copy()} of the plan's unit.
     *
     * @param plan a plan holding a trackable
     * @return the unit to put into the pool
     */
    protected T getTrackableForSecondDispatch(P plan) {
        return plan.getTrackable().copy();
    }

}
