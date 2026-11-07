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
 * Cheapest and regret insertion of unrouted customers into existing plans.
 *
 * <p>{@link com.github.liliumsoftware.optimization.insertion.AbstractInsertionAlgorithm} probes every legal slot of
 * every considered plan on copies, keeps per plan the feasible slot with the smallest cost delta (feasibility from a
 * {@link com.github.liliumsoftware.optimization.feasibility.FeasibilityChecker}, zone admission from
 * {@code supports}), and chooses the plan by a comparator; batches are inserted either one by one (cheapest) or by
 * largest regret summed over all plans. Each probe is an
 * {@link com.github.liliumsoftware.optimization.insertion.InsertionCandidate}, whose delta is the difference of
 * {@code Planable#getCost()} before and after; a candidate without a candidate plan is the "cannot insert" sentinel.
 *
 * <p>Insertion is the repair step of the inter-route algorithms (exchange, relocate, destroy-repair, balance)
 * and of k-medoids. Accepted insertions replace elements of the caller's plan list with enlarged copies; plan objects
 * are never modified. Insertion is construction: an {@code interrupt()} never makes a batch skip an insertable
 * customer, while {@code cancel()} aborts it.
 */
package com.github.liliumsoftware.optimization.insertion;
