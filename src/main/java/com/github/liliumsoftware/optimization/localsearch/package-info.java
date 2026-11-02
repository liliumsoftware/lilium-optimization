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
 * Intra-plan improvement: reordering the stops of a single plan.
 *
 * <p>{@link LocalSearchAlgorithm} is the extension point every construction and inter-plan algorithm calls on the plans
 * it touches (Clarke-Wright merges, exchange/relocate, destroy-repair, merge and un-merge). Its {@code apply} must not
 * modify its argument: it returns an improved new plan or the argument itself. Two first-improvement 2-opt templates
 * are provided:
 * <ul>
 *   <li>{@link AbstractTwoOptAlgorithm}: segment reversals on a closed tour; the origin stays first and a trailing
 *       destination stays last.</li>
 *   <li>{@link AbstractOpenTwoOptAlgorithm}: for open paths, adds two moves that relocate a segment to the end of the
 *       path.</li>
 * </ul>
 * Both cost candidates through a {@link com.github.liliumsoftware.optimization.calculation.PathCostCalculation},
 * accept only strictly cheaper candidates that the
 * {@link com.github.liliumsoftware.optimization.feasibility.FeasibilityChecker} judges feasible, build every candidate
 * on a deep copy, abort on {@code cancel()} and return their best plan so far on {@code interrupt()}. The consumer
 * supplies the hook that turns a node order into a plan.
 */
package com.github.liliumsoftware.optimization.localsearch;
