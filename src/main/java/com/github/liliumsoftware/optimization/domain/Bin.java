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
 * A rectangular box to be packed into a {@link Container} by {@link BinPacking}. Dimensions are integers in the
 * container's unit; the packer never places a bin with a non-positive dimension (it skips that bin and packs the
 * others).
 *
 * <p>{@link BinPacking} may rotate a bin by 90&deg; in the horizontal plane (swapping length and width), never tips it
 * over, and only places it on shelf levels up to {@link #getStackCount()}. It tracks bins by their position in the
 * input, so {@code equals} is never consulted and equal bins are each placed in their own right. Copies are made only
 * by {@link BinPacking#getCount}, which replicates one bin with {@link #copy()} and numbers the copies with
 * {@link #setIndex(int)}.
 *
 * @param <B> the concrete bin type (self type)
 */
public interface Bin<B extends Bin<B>> {

    /**
     * Length along the container's length (x axis) when the bin is not rotated.
     *
     * @return the length, in the container's unit; must be positive for the bin to be packed
     */
    int getLength();

    /**
     * Width along the container's width (y axis) when the bin is not rotated.
     *
     * @return the width, in the container's unit; must be positive for the bin to be packed
     */
    int getWidth();

    /**
     * Height along the container's stacking direction; bins are never tipped over, so this is always vertical.
     *
     * @return the height, in the container's unit; must be positive for the bin to be packed
     */
    int getHeight();

    /**
     * A deep, independent copy. {@link BinPacking#getCount} uses it to replicate the bin it probes with; the source
     * bin is not modified.
     *
     * @return a new bin with the same dimensions and stack count
     */
    B copy();

    /**
     * Index of this copy among the copies {@link BinPacking#getCount} made of one bin, numbered {@code 0 .. n-1}.
     *
     * <p>The default returns {@code 0}, the index of the first copy, which is also what a bin that was never
     * replicated reports.
     *
     * @return the copy index
     */
    default int getIndex() {
        return 0;
    }

    /**
     * Records the index of this copy; called by {@link BinPacking#getCount} on each copy it makes.
     *
     * <p>The default does nothing, so {@link #getIndex()} keeps returning {@code 0}; override both to tell the
     * copies apart.
     *
     * @param index the copy index, {@code 0 .. n-1}
     */
    default void setIndex(int index) {
    }

    /**
     * The highest shelf level (1-based, the container floor being level {@code 1}) this bin may be placed on.
     * {@code 1} keeps the bin on the floor; a value below {@code 1} means the bin is never placed.
     *
     * <p>The default returns {@code 1}.
     *
     * @return the highest permitted level
     */
    default int getStackCount() {
        return 1;
    }

    /**
     * Footprint {@code length × width}, computed in {@code long} so it cannot overflow; {@link BinPacking} places
     * bins largest footprint first.
     *
     * @return the footprint area, in the container's unit squared
     */
    default long getArea() {
        return (long) getLength() * getWidth();
    }

    /**
     * {@code length × width × height}, computed in {@code long} so it cannot overflow.
     *
     * @return the volume, in the container's unit cubed
     */
    default long getVolume() {
        return (long) getLength() * getWidth() * getHeight();
    }

}
