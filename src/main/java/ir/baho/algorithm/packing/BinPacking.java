package ir.baho.algorithm.packing;

import ir.baho.algorithm.domain.Bin;
import ir.baho.algorithm.domain.Container;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Shelf (guillotine) heuristic for placing 3D boxes into one container. Bins are packed largest-area-first
 * into rows along the length; each row's height opens the next shelf level, and levels stack until the
 * container height is exhausted. A bin may be rotated 90° in the horizontal plane when that is the only way
 * it fits the current row (it does not fit the free space unrotated, or its unrotated width exceeds the row's cut
 * width). This is a fast feasibility heuristic, not an optimal packer: {@link #isValid}
 * reports whether every given bin was placed, and {@link #getCount} probes how many identical bins fit. Bins are
 * tracked by position in the input, so bins that are {@code equals} to each other (for example records of the same
 * size) are each placed in their own right.
 *
 * @param <C> the container type
 * @param <B> the bin type
 */
@RequiredArgsConstructor
public class BinPacking<C extends Container<C>, B extends Bin<B>> {

    private final C container;

    /**
     * How many copies of {@code bin} fit into the container, found by packing 1, 2, 3, … copies until one no longer
     * fits. O(k) packings for an answer of k.
     */
    public int getCount(B bin) {
        int i = 1;
        while (isValid(IntStream.range(0, i).mapToObj(value -> {
            B copy = bin.copy();
            copy.setIndex(value);
            return copy;
        }).toList())) {
            i++;
        }
        return i - 1;
    }

    /**
     * Whether every given bin can be placed.
     */
    public boolean isValid(List<B> bins) {
        return bins.size() == pack(bins).size();
    }

    /**
     * Places as many of {@code bins} as the shelf heuristic manages, largest area first, and returns their
     * positions. Bins that do not fit are simply absent; an empty list is returned for empty input or for any bin
     * with a non-positive dimension. The input list is not modified.
     */
    public List<Position<B>> pack(List<B> bins) {
        if (bins.isEmpty() || bins.stream().anyMatch(b -> b.getLength() <= 0 || b.getWidth() <= 0
                || b.getHeight() <= 0)) {
            return new ArrayList<>();
        }
        bins = bins.stream().sorted(Comparator.comparingLong((B o) -> o.getArea()).reversed()).toList();
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
                if (deltaHeight + bin.getHeight() > container.getHeight()) {
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
