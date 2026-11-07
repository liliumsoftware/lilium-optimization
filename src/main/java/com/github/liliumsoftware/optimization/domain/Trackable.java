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

/**
 * A resource that executes a plan: a vehicle, driver or similar.
 *
 * <p><strong>Units.</strong> A single {@code Trackable} may stand for several interchangeable units: the
 * {@link com.github.liliumsoftware.optimization.assignment.TrackableAssignment} constructor expands an instance whose
 * {@link #getCount()} is {@code n} into {@code n} copies made with {@link #copy()} and numbered
 * {@code 0 .. n - 1} with {@link #setIndex(int)}, so each unit can be handed out independently. A resource with
 * {@code getCount() == 0} yields no unit at all (the way to mark a resource unavailable); a negative count is rejected
 * with {@link IllegalArgumentException}.
 *
 * <p><strong>Identity.</strong> {@code equals} must compare by code <em>and</em> index: expanded units share
 * {@link #getCode()} and differ only by {@link #getIndex()}, and the assignment layer tells booked units from idle ones
 * with {@code equals}. A resource that was never expanded reports index {@code 0}, the index of the first pool unit,
 * so a plan built with the caller's own instance is recognised as holding that unit. Consequently a resource with
 * {@code getCount() > 1} needs {@link #setIndex(int)}, {@link #getIndex()} and {@code equals} implemented to tell the
 * units apart: with the default no-op {@code setIndex} all its copies would be equal, and the assignment constructor
 * rejects such a fleet with {@link IllegalArgumentException}.
 * {@link #copy()} must be a deep copy.
 *
 * @param <T> the concrete resource type (self type)
 */
public interface Trackable<T extends Trackable<T>> {

    /**
     * Identity of the resource; units expanded from one resource share the code and differ by index.
     *
     * @return the code
     */
    String getCode();

    /**
     * A deep, independent copy, including the index; also used to materialize the {@link #getCount()}
     * interchangeable units.
     *
     * @return the copy
     */
    T copy();

    /**
     * Index of this unit within its {@link #getCount()}-sized group. The assignment layer numbers the expanded units
     * {@code 0 .. getCount() - 1}; a resource that was never expanded reports {@code 0}, so it is the same unit as the
     * first (for a single-unit resource the only) copy in the assignment pool. Implementations that include the index
     * in {@code equals} rely on this: a plan the caller built with its own, never-expanded instance must still be
     * recognised as holding that pool unit.
     *
     * <p>The default returns {@code 0}.
     *
     * @return the unit index
     */
    default int getIndex() {
        return 0;
    }

    /**
     * Records the unit index; called by the assignment layer on each copy when expanding a resource.
     *
     * <p>The default does nothing, which is only correct for single-unit resources.
     *
     * @param index the unit index, {@code 0 .. getCount() - 1}
     */
    default void setIndex(int index) {
    }

    /**
     * How many interchangeable units this resource represents. {@code 0} contributes no unit to the assignment pool
     * (the resource is unavailable); a negative value is rejected by the assignment constructor.
     *
     * <p>The default returns {@code 1}.
     *
     * @return the unit count
     */
    default int getCount() {
        return 1;
    }

    /**
     * Whether the resource returns to its origin at the end of its run, which makes its plans closed tours
     * ({@link Planable#isClosed()}).
     *
     * <p>The default returns {@code false}.
     *
     * @return {@code true} for a returning resource
     */
    default boolean isReturns() {
        return false;
    }

    /**
     * Whether the resource may be dispatched for a second run after returning; the second-dispatch assignment only
     * keeps a used unit in the pool when both this and {@link #isReturns()} hold.
     *
     * <p>The default returns {@link #isReturns()}.
     *
     * @return {@code true} if a second run is allowed
     */
    default boolean isSupportSecondDispatch() {
        return isReturns();
    }

}
