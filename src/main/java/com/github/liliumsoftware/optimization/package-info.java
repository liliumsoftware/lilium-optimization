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
 * Vehicle-routing and plan-optimization heuristics as a framework of abstract algorithm templates.
 *
 * <p>Consumers implement the three self-typed domain interfaces of
 * {@link com.github.liliumsoftware.optimization.domain} ({@code Nodeable}, {@code Planable}, {@code Trackable})
 * against their own entities, subclass
 * {@link com.github.liliumsoftware.optimization.calculation.PathCostCalculation} to supply a cost matrix, and subclass
 * the {@code Abstract*Algorithm} templates of the sub-packages (construction in {@code clarkewright},
 * {@code insertion} and {@code cluster}; improvement in {@code localsearch}, {@code optimization}, {@code merge} and
 * {@code balance}; resources in {@code assignment}; pre-processing in {@code validation} and {@code split}).
 *
 * <p>This package holds the shared roots:
 * <ul>
 *   <li>{@link com.github.liliumsoftware.optimization.BaseAlgorithm}: the base of every template, with the
 *       {@code cancel()} (abort) and {@code interrupt()} (graceful stop) signals.</li>
 *   <li>{@link com.github.liliumsoftware.optimization.BaseAlgorithmRunner}: the skeleton of a consumer's planning run
 *       (initialize the cost matrix, plan, finalize), fanning the signals out to every algorithm it owns.</li>
 *   <li>{@link com.github.liliumsoftware.optimization.OptimizationType}: whether zone restrictions apply.</li>
 * </ul>
 *
 * <p>Contracts the whole library relies on: {@code copy()} is a deep copy, moves are built on copies and accepted by
 * cost comparison, inter-route algorithms replace elements of the plan list they are given (never the plan objects
 * passed in), costs are integral {@code long}s, and the cost matrix is initialized with every node before any
 * algorithm runs. Use one set of algorithm instances per planning run, since the signals are never reset.
 */
package com.github.liliumsoftware.optimization;
