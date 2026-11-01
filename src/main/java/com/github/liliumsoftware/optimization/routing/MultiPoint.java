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

package com.github.liliumsoftware.optimization.routing;

import java.io.Serial;

/**
 * GeoJSON {@code MultiPoint}: a collection of positions whose order carries no meaning in GeoJSON (the list keeps
 * the order given). Serialized as {@code {"type": "MultiPoint", "coordinates": [[lon, lat], …]}}; also the base of
 * {@link LineString}.
 */
public class MultiPoint extends Geometry<LngLatAlt> {

    /**
     * Pinned to the value the JVM computed for the 3.0.1 release, so instances serialized by it still read back.
     */
    @Serial
    private static final long serialVersionUID = -5585934090492157948L;

    /**
     * Creates an empty collection of positions.
     */
    public MultiPoint() {
    }

    /**
     * Creates a collection holding {@code points} in the given order; the positions are not copied.
     *
     * @param points the positions; must not be {@code null}
     * @throws NullPointerException if {@code points} is {@code null}
     */
    public MultiPoint(LngLatAlt... points) {
        super(points);
    }

    @Override
    public String toString() {
        return "MultiPoint{} " + super.toString();
    }

}
