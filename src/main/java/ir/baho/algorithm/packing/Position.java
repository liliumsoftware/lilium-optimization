package ir.baho.algorithm.packing;

import ir.baho.algorithm.domain.Bin;

/**
 * Placement of one {@link Bin} inside a container: its bottom-left corner ({@code xbl}, {@code ybl}), the
 * stacking {@code level} (shelf) it sits on, and whether it was {@code rotate}d 90° in the horizontal plane
 * to fit.
 */
public record Position<B extends Bin<B>>(B bin, int xbl, int ybl, int level, boolean rotate) {

}
