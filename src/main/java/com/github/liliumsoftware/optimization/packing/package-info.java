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
 * Shelf heuristic for packing rectangular boxes into a container.
 *
 * <p>{@link com.github.liliumsoftware.optimization.packing.BinPacking} places
 * {@link com.github.liliumsoftware.optimization.domain.Bin}s into one
 * {@link com.github.liliumsoftware.optimization.domain.Container} level by level and row by row, and reports the
 * result as {@link com.github.liliumsoftware.optimization.packing.Position} records; it also answers whether a set of
 * bins fits and how many identical bins fit. It is a fast feasibility check (for example for a vehicle's load
 * space), not an optimal packer, and has no dependency on the routing algorithms: a consumer may call it from a
 * capacity or feasibility hook of its own. Dimensions are integers in a unit shared by container and bins.
 */
package com.github.liliumsoftware.optimization.packing;
