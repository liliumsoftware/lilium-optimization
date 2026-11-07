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
 * The interfaces consumers implement against their own entities; the library contains no concrete implementation.
 *
 * <ul>
 *   <li>{@link com.github.liliumsoftware.optimization.domain.Nodeable}: a stop (origin, customer or destination),
 *       identified by a unique code.</li>
 *   <li>{@link com.github.liliumsoftware.optimization.domain.Planable}: a route, an ordered, mutable node list with
 *       a cost and an assigned resource.</li>
 *   <li>{@link com.github.liliumsoftware.optimization.domain.Trackable}: a resource (vehicle, driver), possibly
 *       standing for several indexed units, classified by a
 *       {@link com.github.liliumsoftware.optimization.domain.TrackableCategory}.</li>
 *   <li>{@link com.github.liliumsoftware.optimization.domain.InvalidNodeable}: a node rejected by pre-planning
 *       validation; {@link com.github.liliumsoftware.optimization.domain.PlanModifier}: post-processing of finished
 *       plans.</li>
 *   <li>{@link com.github.liliumsoftware.optimization.domain.Bin} and
 *       {@link com.github.liliumsoftware.optimization.domain.Container}: boxes and their bounding box for the
 *       {@code packing} heuristic.</li>
 * </ul>
 *
 * <p>Every algorithm is parameterized by the self-typed triple {@code <P extends Planable<P, N, T>, N extends
 * Nodeable<N>, T extends Trackable<T>>}. Key contracts: {@code copy()} returns a deep, independent copy (a node copy
 * {@code equals} its source); node and plan codes are unique within a run; {@code Planable#getCost()} equals the sum
 * of the consecutive edge costs of the current node list; {@code Trackable#equals} compares code and index.
 */
package com.github.liliumsoftware.optimization.domain;
