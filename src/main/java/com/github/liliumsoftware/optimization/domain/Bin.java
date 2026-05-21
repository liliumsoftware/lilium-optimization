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

import com.github.liliumsoftware.optimization.packing.BinPacking;

/**
 * A rectangular box to be packed into a {@link Container}. Like {@link Trackable}, a single {@code Bin}
 * whose {@link #getStackCount()} allows it can be materialized into indexed copies during packing.
 * Dimensions are integers in the container's units.
 *
 * @param <B> the concrete bin type (self type)
 */
public interface Bin<B extends Bin<B>> {

    /**
     * Length along the container's x axis, in the container's unit.
     */
    int getLength();

    /**
     * Width along the container's y axis, in the container's unit.
     */
    int getWidth();

    /**
     * Height (stacking direction), in the container's unit.
     */
    int getHeight();

    /**
     * A deep, independent copy, also used to materialize {@link #getStackCount()} indexed copies.
     */
    B copy();

    /**
     * Index of this copy within a multi-bin group. {@link BinPacking#getCount} numbers the copies {@code 0 .. n-1}; a
     * bin that was never expanded reports {@code 0}, the index of the first copy.
     */
    default int getIndex() {
        return 0;
    }

    /**
     * Set when a multi-bin group is expanded; a no-op for singletons.
     */
    default void setIndex(int index) {
    }

    /**
     * How many levels high this bin may be stacked; {@code 1} by default.
     */
    default int getStackCount() {
        return 1;
    }

    /**
     * Footprint {@code length × width} in {@code long} arithmetic.
     */
    default long getArea() {
        return (long) getLength() * getWidth();
    }

    /**
     * {@code length × width × height} in {@code long} arithmetic.
     */
    default long getVolume() {
        return (long) getLength() * getWidth() * getHeight();
    }

}
