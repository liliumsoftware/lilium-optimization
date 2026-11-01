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

/**
 * OSRM routing client and the GeoJSON model it speaks.
 *
 * <p>{@link com.github.liliumsoftware.optimization.routing.RoutingProvider} is the only concrete link between the
 * library and a road network: it asks an OSRM server for an all-pairs duration matrix (integer milliseconds, the unit
 * of the library's cost model), single-trip durations and distances, and route geometries. A consumer typically calls
 * it from its
 * {@link com.github.liliumsoftware.optimization.calculation.PathCostCalculation#getCostMatrix(java.util.List)}
 * implementation; nothing else in the library depends on this package, and any other routing engine can be plugged in
 * the same way. Failures of the service surface as the unchecked
 * {@link com.github.liliumsoftware.optimization.routing.RoutingException}; invalid caller input as
 * {@link java.lang.IllegalArgumentException}.
 *
 * <p>The GeoJSON classes ({@link com.github.liliumsoftware.optimization.routing.GeoJsonObject},
 * {@link com.github.liliumsoftware.optimization.routing.Point},
 * {@link com.github.liliumsoftware.optimization.routing.MultiPoint},
 * {@link com.github.liliumsoftware.optimization.routing.LineString},
 * {@link com.github.liliumsoftware.optimization.routing.LngLatAlt},
 * {@link com.github.liliumsoftware.optimization.routing.Crs})
 * are mutable Jackson 3 beans with value-based {@code equals}; treat them as immutable once shared. Positions use the
 * GeoJSON array form {@code [lon, lat(, alt, …)]} through
 * {@link com.github.liliumsoftware.optimization.routing.LngLatAltSerializer} and
 * {@link com.github.liliumsoftware.optimization.routing.LngLatAltDeserializer}; polymorphic typing is by name over a
 * closed subtype list. This package needs {@code jackson-databind} 3.x on the classpath, which the library declares
 * as an optional dependency.
 */
package com.github.liliumsoftware.optimization.routing;
