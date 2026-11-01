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
 * Assignment of resources (trackables: vehicles, drivers) to plans, and repair of those assignments.
 *
 * <p>{@link com.github.liliumsoftware.optimization.assignment.TrackableAssignment} is the central template: it expands
 * the fleet into indexed units ({@link com.github.liliumsoftware.optimization.domain.Trackable#getCount()}), offers
 * them to plans through the consumer's {@code assign}, {@code comparator} and {@code getCategory} hooks, and repairs
 * the result with relocate, exchange and reassign passes. Two variants change the pool:
 * {@link com.github.liliumsoftware.optimization.assignment.InfiniteTrackableAssignment} never withdraws a unit (an
 * unlimited fleet), and {@link com.github.liliumsoftware.optimization.assignment.SecondDispatchTrackableAssignment}
 * re-admits returning units for a second run.
 * {@link com.github.liliumsoftware.optimization.assignment.TrackableAlternativeFinder} lists the resources that may
 * stand in for a given one.
 *
 * <p>The Clarke-Wright, k-medoids, exchange/relocate, destroy-repair, merge and balance templates take a
 * {@code TrackableAssignment} and call it whenever routes change;
 * {@link com.github.liliumsoftware.optimization.split.ZoneGroups} uses it to let parallel zone groups share
 * one fleet without booking a unit twice. Key contracts: units are told apart by {@code equals} (code and index); the
 * greedy steps mutate the given plans in place, while the repair passes probe on deep copies and put the accepted
 * copies into the caller's list, so callers re-read the list; the repair loops terminate on accepted-move progress,
 * never on plan equality.
 */
package com.github.liliumsoftware.optimization.assignment;
