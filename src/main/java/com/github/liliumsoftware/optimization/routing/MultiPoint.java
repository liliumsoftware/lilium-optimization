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

/**
 * GeoJSON {@code MultiPoint}: an unordered-by-contract collection of coordinates.
 */
public class MultiPoint extends Geometry<LngLatAlt> {

    /**
     * An empty collection of points.
     */
    public MultiPoint() {
    }

    /**
     * A collection holding {@code points} in the given order.
     */
    public MultiPoint(LngLatAlt... points) {
        super(points);
    }

    @Override
    public String toString() {
        return "MultiPoint{} " + super.toString();
    }

}
