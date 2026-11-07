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
 * Changing the number of plans along zone boundaries.
 *
 * <ul>
 *   <li>{@link AbstractMergeAlgorithm}: fuses two plans when that lowers the summed cost and the resources can still
 *       be assigned.</li>
 *   <li>{@link AbstractSingleUnMergeAlgorithm}: pulls the customers of one zone onto the plan that serves only that
 *       zone.</li>
 *   <li>{@link AbstractDoubleUnMergeAlgorithm}: separates two plans that serve the same two zones so that each keeps
 *       one.</li>
 * </ul>
 * The un-merges are driven by the zone policy
 * ({@link com.github.liliumsoftware.optimization.OptimizationType}) and accept a move whenever the resources can be
 * assigned, without a cost check. All three build every candidate on deep copies, run the touched plans through a
 * {@link com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm}, replace the contents of the list
 * passed in when a move is accepted (never the caller's plan objects), and terminate because every accepted move
 * reduces the number of plans or of plan zones. {@code cancel()} aborts, {@code interrupt()} returns the moves
 * accepted so far.
 */
package com.github.liliumsoftware.optimization.merge;
