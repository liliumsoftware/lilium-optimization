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

package com.github.liliumsoftware.optimization.packing;

import com.github.liliumsoftware.optimization.domain.Bin;
import com.github.liliumsoftware.optimization.domain.Container;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Shelf (guillotine) heuristic for placing 3D boxes into one container. This is a fast feasibility heuristic, not an
 * optimal packer: {@link #pack} returns the placements, {@link #isValid} reports whether every given bin was placed,
 * and {@link #getCount} probes how many identical bins fit.
 *
 * <p><strong>Layout.</strong> Bins are taken largest footprint ({@link Bin#getArea()}) first; bins of equal area keep
 * their input order. Each level spans the whole container floor and is filled row by row: a row runs along the
 * container length (x) from {@code x = 0}, its depth along the width (y) is fixed by the first bin placed in it, and
 * later bins join the row only if they are no deeper. A bin may be rotated 90° in the horizontal plane when that is
 * the only way it fits the current row (it does not fit the free space unrotated, or its unrotated width exceeds the
 * row's depth). When a row takes no bin the level is full and the next level starts on the whole floor again; its
 * vertical offset is the sum of the heights of the tallest bin of each level below. A bin is only placed if its own
 * height fits under the container height above that offset. Packing stops when a level takes no bin at all.
 *
 * <p><strong>Stacking.</strong> {@link Bin#getStackCount()} is the highest level (1-based) a bin may be placed on.
 * It is not checked against the bins underneath: a bin with stack count 2 may sit above one with stack count 1, and
 * an upper-level bin may sit partly or wholly over empty floor (there is no support check). {@link Position} carries
 * the level but no z coordinate; derive it from the heights of the lower levels if needed.
 *
 * <p>Bins are tracked by position in the input, so bins that are {@code equals} to each other (for example records of
 * the same size) are each placed in their own right. A bin with a non-positive length, width or height is never
 * placed (it is skipped, and the other bins are packed as if it were absent); the container's dimensions are not
 * validated, and a container with a non-positive dimension simply fits nothing.
 *
 * <p>Holds no mutable state besides the container reference: one instance may be used from several threads as long
 * as the container and the bins are not mutated concurrently.
 *
 * @param <C> the container type
 * @param <B> the bin type
 */
@RequiredArgsConstructor
public class BinPacking<C extends Container<C>, B extends Bin<B>> {

    /**
     * The container every call packs into; held by reference, not copied, and not validated.
     */
    private final C container;

    /**
     * How many copies of {@code bin} fit into the container under this heuristic.
     *
     * <p>Copies of one bin are identical, so the heuristic fills the same sequence of slots one copy after the other:
     * packing {@code k} copies places exactly {@code min(k, capacity)} of them (the placed copies are always a prefix
     * of that sequence). The count is therefore found by packing 1, 2, 4, … copies until a packing leaves one out and
     * reading the capacity off that packing: O(log k) packings for an answer of {@code k}, instead of the k + 1
     * packings of trying every count. The copies are made with {@link Bin#copy()} and numbered {@code 0 .. n-1} with
     * {@link Bin#setIndex(int)}. The doubling stops at 2<sup>30</sup> copies, so larger capacities are reported as
     * 2<sup>30</sup>.
     *
     * @param bin the bin to replicate; not modified
     * @return how many copies fit; {@code 0} when not even one does (or a dimension of the bin is not positive)
     * @throws NullPointerException if {@code bin} is {@code null} or its {@code copy()} returns {@code null}
     */
    public int getCount(B bin) {
        int copies = 1;
        while (true) {
            int placed = pack(copies(bin, copies)).size();
            if (placed < copies || copies > Integer.MAX_VALUE / 2) {
                return placed;
            }
            copies *= 2;
        }
    }

    private List<B> copies(B bin, int count) {
        return IntStream.range(0, count).mapToObj(value -> {
            B copy = bin.copy();
            copy.setIndex(value);
            return copy;
        }).toList();
    }

    /**
     * Whether every given bin can be placed, i.e. {@link #pack} places all of them.
     *
     * @param bins the bins to place; not modified
     * @return {@code true} if all bins fit (always for an empty list); {@code false} if any is left out, including
     *         any bin with a non-positive dimension, which is never placed
     * @throws NullPointerException if {@code bins} or one of its elements is {@code null}
     */
    public boolean isValid(List<B> bins) {
        return bins.size() == pack(bins).size();
    }

    /**
     * Places as many of {@code bins} as the shelf heuristic manages (see the class description) and returns their
     * placements. Bins that do not fit are simply absent from the result. A bin with a non-positive length, width or
     * height is skipped: it is never placed and does not affect the placement of the others. A rotated placement
     * ({@link Position#rotate()}) occupies the bin's width along the container length and its length along the
     * container width.
     *
     * @param bins the bins to place; neither the list nor the bins are modified
     * @return a new mutable list of placements in placement order (level by level, row by row), each referring to the
     *         caller's bin object; empty when {@code bins} is empty or no bin fits
     * @throws NullPointerException if {@code bins} or one of its elements is {@code null}
     */
    public List<Position<B>> pack(List<B> bins) {
        bins = bins.stream()
                .filter(b -> b.getLength() > 0 && b.getWidth() > 0 && b.getHeight() > 0)
                .sorted(Comparator.comparingLong((B o) -> o.getArea()).reversed())
                .toList();
        if (bins.isEmpty()) {
            return new ArrayList<>();
        }
        int deltaWidth = container.getWidth();
        int deltaHeight = 0;
        int ybl = 0;
        int level = 1;
        int maxHeight = 0;
        int count = 0;
        List<Position<B>> positions = new ArrayList<>();
        // Placed bins are tracked by their position in the sorted input, never by equals: two equal bins (e.g. records
        // of the same size) are two boxes to place.
        boolean[] placed = new boolean[bins.size()];
        while (true) {
            int cutWidth = 0;
            int deltaLength = container.getLength();
            int xbl = 0;
            int placedBefore = positions.size();

            for (int k = 0; k < bins.size(); k++) {
                if (placed[k]) {
                    continue;
                }
                B bin = bins.get(k);
                if (level > bin.getStackCount()) {
                    continue;
                }
                // Vertical gate: a bin may only sit on this level if the height already consumed by the
                // levels below plus the bin's own height stays within the container. Without this a taller
                // upper level (or a single over-tall bin) would be committed before its height was checked.
                // Written as a subtraction: deltaHeight never exceeds the container height, so this cannot overflow the
                // way deltaHeight + bin.getHeight() could for a very tall bin.
                if (bin.getHeight() > container.getHeight() - deltaHeight) {
                    continue;
                }
                boolean fitsUnrotated = deltaLength >= bin.getLength() && deltaWidth >= bin.getWidth()
                        && (cutWidth == 0 || cutWidth >= bin.getWidth());
                // Rotation is tried whenever the unrotated bin does not fit, including when only the row's cut width
                // blocks it.
                boolean fitsRotated = deltaLength >= bin.getWidth() && deltaWidth >= bin.getLength()
                        && (cutWidth == 0 || cutWidth >= bin.getLength());
                if (fitsUnrotated) {
                    cutWidth = Math.max(cutWidth, bin.getWidth());
                    positions.add(new Position<>(bin, xbl, ybl, level, false));
                    placed[k] = true;
                    xbl += bin.getLength();
                    deltaLength -= bin.getLength();
                    maxHeight = Math.max(maxHeight, bin.getHeight());
                } else if (fitsRotated) {
                    cutWidth = Math.max(cutWidth, bin.getLength());
                    positions.add(new Position<>(bin, xbl, ybl, level, true));
                    placed[k] = true;
                    xbl += bin.getWidth();
                    deltaLength -= bin.getWidth();
                    maxHeight = Math.max(maxHeight, bin.getHeight());
                }
            }
            if (positions.size() == placedBefore) {
                // nothing fitted into this row (so cutWidth is 0 and the row advance below is a no-op): the level is
                // full, move up a level or stop
                if (positions.size() == count || deltaHeight >= container.getHeight()) {
                    break;
                }
                level++;
                deltaWidth = container.getWidth();
                ybl = 0;
                deltaHeight += maxHeight;
                maxHeight = 0;   // height is per-level: reset so the next level's offset counts only its own bins
                count = positions.size();
            }
            deltaWidth -= cutWidth;
            ybl += cutWidth;
        }
        return positions;
    }

}
