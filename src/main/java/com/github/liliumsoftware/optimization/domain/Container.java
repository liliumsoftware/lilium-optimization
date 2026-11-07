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
 * The bounding box that {@link Bin}s are packed into by
 * {@link com.github.liliumsoftware.optimization.packing.BinPacking}; inner dimensions are integers in the unit shared
 * with the bins.
 *
 * @param <C> the concrete container type (self type)
 */
public interface Container<C extends Container<C>> {

    /**
     * Inner length (x axis): the direction along which bins are placed side by side within a row.
     *
     * @return the inner length
     */
    int getLength();

    /**
     * Inner width (y axis): the direction along which rows are laid out on a shelf level.
     *
     * @return the inner width
     */
    int getWidth();

    /**
     * Inner height (stacking direction): the shelf levels stack until it is used up.
     *
     * @return the inner height
     */
    int getHeight();

    /**
     * A deep, independent copy. The library itself never calls it.
     *
     * @return a new container with the same dimensions
     */
    C copy();

}
