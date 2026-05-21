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
import java.util.stream.Collectors;

/**
 * Assignment variant that lets a returning resource be re-used for a second run. {@link #secondDispatchOnly}
 * restricts the available pool to resources that support a second dispatch; {@link #secondDispatchAndOthers}
 * separates each resource's first plan from its second so the two runs can be planned and assigned coherently.
 * Requires resources whose {@link Trackable#isReturns()} and {@link Trackable#isSupportSecondDispatch()} hold.
 * Units are told apart by {@code equals} (code and index), so the expanded units of one multi-unit resource stay
 * independent: re-dispatching one of them neither removes nor re-admits its siblings.
 */
public abstract class SecondDispatchTrackableAssignment<P
        extends Planable<P, ?, T>, T extends Trackable<T>> extends TrackableAssignment<P, T> {

    /**
     * Creates the assignment over {@code trackables}.
     *
     * @param trackables the fleet; resources must report {@link Trackable#isReturns()} and
     *                   {@link Trackable#isSupportSecondDispatch()} to be eligible for a second run
     */
    public SecondDispatchTrackableAssignment(List<T> trackables) {
        super(trackables);
    }

    /**
     * Narrows the current pool to resources that may run again: the trackables of {@code plans} (as fresh copies,
     * each unit once even when a first and a second run both hold it) plus unused ones, keeping only those that
     * support a second dispatch. Plans without a trackable contribute nothing.
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
     * Splits {@code plans} into each returning resource's first run and everything else, narrows the pool to
     * resources not yet used plus those eligible for a second run, and returns the second-run plans together with
     * {@code secondDispatch}. Every plan in {@code plans} must already have a trackable assigned.
     */
    public synchronized List<P> secondDispatchAndOthers(List<P> plans, List<P> secondDispatch) {
        List<T> secondDispatchTrackables = plans.stream()
                .filter(p -> p.isSecondDispatch())
                .map(P::getTrackable)
                .toList();
        List<P> firstPlans = plans.stream()
                .filter(p -> secondDispatchTrackables.contains(p.getTrackable()) && !p.isSecondDispatch())
                .toList();

        List<P> secondPlans = new ArrayList<>(plans);
        secondPlans.removeAll(firstPlans);

        List<T> allTrackables = new ArrayList<>(trackables);
        allTrackables.removeIf(t -> plans.stream().anyMatch(p -> t.equals(p.getTrackable())));
        allTrackables.addAll(secondPlans.stream().map(P::getTrackable).toList());
        currentTrackables = allTrackables;

        secondPlans.addAll(secondDispatch);
        return secondPlans;
    }

    /**
     * Whether {@code trackable} may stay in the pool: not used by {@code plans}, or returning and dispatchable twice.
     */
    protected boolean supportSecondDispatch(List<P> plans, T trackable) {
        return plans.stream().noneMatch(p -> trackable.equals(p.getTrackable()))
                || (trackable.isReturns() && trackable.isSupportSecondDispatch());
    }

    /**
     * The unit of {@code plan} as it enters its second run; a copy by default.
     */
    protected T getTrackableForSecondDispatch(P plan) {
        return plan.getTrackable().copy();
    }

}
