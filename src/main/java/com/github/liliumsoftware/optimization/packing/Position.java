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

/**
 * Placement of one {@link Bin} inside a container, as produced by {@link BinPacking#pack}: the corner of its
 * footprint nearest the container origin, the level it sits on, and whether it was rotated 90° in the horizontal
 * plane to fit. There is no z coordinate: the vertical offset of a level is the sum of the heights of the tallest bin
 * of each level below it, which callers derive from the placements themselves.
 *
 * @param bin    the placed bin: the caller's own object, not a copy
 * @param xbl    offset of the footprint's corner along the container length (x), in container units
 * @param ybl    offset of the footprint's corner along the container width (y), in container units
 * @param level  the level the bin sits on, starting at {@code 1} for the container floor
 * @param rotate {@code true} if the bin is rotated, so its width runs along x and its length along y
 * @param <B>    the bin type
 */
public record Position<B extends Bin<B>>(B bin, int xbl, int ybl, int level, boolean rotate) {

}
