package com.github.liliumsoftware.optimization.domain;

/**
 * The bounding box that {@link Bin}s are packed into; dimensions are integers in a shared unit.
 */
public interface Container<C extends Container<C>> {

    /**
     * Inner length (x axis).
     */
    int getLength();

    /**
     * Inner width (y axis).
     */
    int getWidth();

    /**
     * Inner height (stacking direction).
     */
    int getHeight();

    /**
     * A deep, independent copy.
     */
    C copy();

}
