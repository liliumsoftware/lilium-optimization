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
 * GeoJSON {@code LineString}: an ordered path of positions, the shape {@link RoutingProvider#getRoute} returns a
 * route geometry as. The number of positions is not checked (GeoJSON requires two or more). Serialized as
 * {@code {"type": "LineString", "coordinates": [[lon, lat], …]}}.
 */
public class LineString extends MultiPoint {

    /**
     * Pinned to the value the JVM computed for the 3.0.1 release, so instances serialized by it still read back.
     */
    @Serial
    private static final long serialVersionUID = 4410510653763357723L;

    /**
     * Creates an empty path.
     */
    public LineString() {
    }

    /**
     * Creates a path through {@code points} in order; the positions are not copied.
     *
     * @param points the positions of the path; must not be {@code null}
     * @throws NullPointerException if {@code points} is {@code null}
     */
    public LineString(LngLatAlt... points) {
        super(points);
    }

    @Override
    public String toString() {
        return "LineString{} " + super.toString();
    }

}
