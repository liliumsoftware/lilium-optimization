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
 * Inter-plan improvement: moves that change which plan serves which customers.
 *
 * <ul>
 *   <li>{@link AbstractExchangeAlgorithm}: swaps customer segments between two plans.</li>
 *   <li>{@link AbstractRelocateAlgorithm}: moves a customer segment from one plan to another, in either direction.</li>
 *   <li>{@link AbstractDestroyRepairAlgorithm}: removes a chain of badly placed customers and re-inserts them with an
 *       insertion algorithm (large neighbourhood search).</li>
 * </ul>
 * Exchange and relocate share the search of {@link AbstractExchangeRelocateAlgorithm} and are configured by an
 * {@link ExchangeRelocateType}, a segment length ({@code chain}) and a nearest-neighbour limit ({@code neighbors}).
 * All three are composed from a {@link com.github.liliumsoftware.optimization.calculation.PathCostCalculation}, a
 * {@link com.github.liliumsoftware.optimization.assignment.TrackableAssignment}, a
 * {@link com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm} and an
 * {@link com.github.liliumsoftware.optimization.insertion.AbstractInsertionAlgorithm}.
 *
 * <p>Every move is built on deep copies of the whole plan list and accepted only when the resources can be assigned
 * and the summed cost strictly drops; the list passed in is mutated by replacing its elements, never the caller's plan
 * objects. The {@code apply(List, int)} entry points split the plans into independent zone groups and run them through
 * {@link com.github.liliumsoftware.optimization.split.ZoneGroups}. {@code cancel()} aborts, {@code interrupt()}
 * returns the moves accepted so far.
 */
package com.github.liliumsoftware.optimization.optimization;
