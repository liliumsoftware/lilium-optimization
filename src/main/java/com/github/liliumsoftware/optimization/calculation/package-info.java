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
 * The cost model shared by every algorithm.
 *
 * <p>{@link com.github.liliumsoftware.optimization.calculation.CostCalculation} defines a plan's cost as the sum of
 * its consecutive edge costs, with a plan-aware edge hook and an overflow-checked plan-set total.
 * {@link com.github.liliumsoftware.optimization.calculation.PathCostCalculation}, the implementation every algorithm
 * takes, keeps an all-pairs matrix (supplied by the consumer's {@code getCostMatrix}, for example from the
 * {@code routing} package), a nearest-neighbour ranking per node, the zone predicates driven by
 * {@link com.github.liliumsoftware.optimization.OptimizationType} and a neighbours matrix, and medoid helpers.
 *
 * <p>Key contracts: {@code initializeCostMatrix} must be called with every node of the run (unique codes) before any
 * algorithm runs; costs are integral {@code long}s (milliseconds, metres), stored as 32-bit cells when they fit and
 * 64-bit cells otherwise; sums that overflow a {@code long} throw {@link java.lang.ArithmeticException} instead of
 * wrapping; and the matrix is published as one immutable snapshot, so concurrent single queries are safe while
 * re-initializing during a planning run is not.
 */
package com.github.liliumsoftware.optimization.calculation;
