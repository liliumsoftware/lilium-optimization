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
 * Pre-processing that splits work into pieces the planner can handle: oversized stops and independent zone groups.
 *
 * <p>Node splitting: {@link com.github.liliumsoftware.optimization.split.AbstractSplitAlgorithm} carves every stop
 * that is too big for the smallest resource into fitting pieces and produces no plans;
 * {@link com.github.liliumsoftware.optimization.split.AbstractBigNodeSplitAlgorithm} carves stops no resource can
 * carry whole against the biggest resources, emitting one single-node plan per piece and consuming that resource (it
 * is stateful; use one instance per planning run). Both rewrite the caller's node list in place; a carved piece keeps
 * the stop's code and every remainder needs a code of its own.
 *
 * <p>Zone grouping: {@link com.github.liliumsoftware.optimization.split.ZoneSplitAlgorithm} partitions plans or nodes
 * into groups that cannot interact under the zone neighbour relation of
 * {@link com.github.liliumsoftware.optimization.calculation.PathCostCalculation}, capped at a maximum group count.
 * {@link com.github.liliumsoftware.optimization.split.ZoneGroups} runs an inter-route optimizer over those groups in
 * parallel against one shared fleet (each group first limited to the units it holds, then an ordered pass over the
 * idle units), so the result is deterministic and no unit is booked twice; the zone-group entry points of the
 * exchange/relocate, destroy-repair and balance algorithms are built on it.
 */
package com.github.liliumsoftware.optimization.split;
