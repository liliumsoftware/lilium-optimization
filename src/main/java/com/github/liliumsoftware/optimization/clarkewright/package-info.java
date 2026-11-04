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
 * Clarke-Wright savings construction: builds an initial plan set from a depot and a list of customers.
 *
 * <p>{@link com.github.liliumsoftware.optimization.clarkewright.AbstractClarkeWrightAlgorithm} seeds one route per
 * customer, computes a {@link com.github.liliumsoftware.optimization.clarkewright.Saving} for every ordered customer
 * pair from the seed routes' real starts and ends, and merges routes greedily in descending saving order whenever the
 * {@link com.github.liliumsoftware.optimization.assignment.TrackableAssignment} can still serve the result; each merged
 * route is improved by the supplied {@link com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm}.
 * By default savings of any sign are merged (the λ/β parameters only reorder the merges; {@code isMergeable} can skip
 * savings). A grid of
 * {@link com.github.liliumsoftware.optimization.clarkewright.LambdaBeta} parameters is evaluated in parallel (per zone
 * under {@code LOCATION_ZONE}), each outcome a {@link com.github.liliumsoftware.optimization.clarkewright.PlanList},
 * and the set serving the most customers, then the cheapest, is kept.
 * {@link com.github.liliumsoftware.optimization.clarkewright.AbstractPartialClarkeWrightAlgorithm} re-runs the
 * construction for the customers an existing plan set leaves uncovered, on the units it leaves free (first directly,
 * then through resource hand-overs).
 * {@link com.github.liliumsoftware.optimization.clarkewright.PlanMerger} is the copy-and-replace step of one merge.
 *
 * <p>Construction always completes: {@code interrupt()} does not stop a merge pass (only the partial variant's
 * hand-overs), while {@code cancel()} aborts it. The caller's plans are never modified; previous plans are returned as
 * copies, and a previous plan that can no longer be re-assigned keeps its resource and takes no part in merging.
 * Customers no resource can serve are left out of the result.
 */
package com.github.liliumsoftware.optimization.clarkewright;
