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
 * Load balancing: moving customers into light plans, trading cost for evenness.
 *
 * <ul>
 *   <li>{@link AbstractNodeBalanceAlgorithm}: evens out customer counts.</li>
 *   <li>{@link AbstractCapacityBalanceAlgorithm}: lifts every plan to a minimum of a domain capacity measure.</li>
 * </ul>
 * Both extend {@link AbstractBalanceAlgorithm}, which runs independent zone groups through
 * {@link com.github.liliumsoftware.optimization.split.ZoneGroups}. A balancing step moves one customer, placed by an
 * {@link com.github.liliumsoftware.optimization.insertion.AbstractInsertionAlgorithm} into the receiving plan, from a
 * zone-neighbouring donor, re-assigns the resources and keeps the cheapest admissible transfer; every step strictly
 * improves evenness, so balancing terminates. A donor always keeps at least one customer, so balancing never empties a
 * plan. Balancing works on copies and returns a new list: neither the list
 * passed in nor its plans are modified. {@code cancel()} aborts, {@code interrupt()} returns the best state seen so
 * far.
 */
package com.github.liliumsoftware.optimization.balance;
