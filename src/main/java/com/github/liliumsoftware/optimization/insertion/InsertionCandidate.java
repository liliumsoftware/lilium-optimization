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

package com.github.liliumsoftware.optimization.insertion;

import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.domain.Planable;
import lombok.Data;

/**
 * The result of trying to insert one node into one plan: the {@code originalPlan}, the {@code candidatePlan}
 * with the node inserted, and the {@code deltaCost} between them. Subclasses extend this to carry
 * domain-specific data the insertion heuristics compare on.
 *
 * <p>A candidate built with the {@code (originalPlan, newNode)} constructor is the "cannot insert" sentinel:
 * its {@code candidatePlan} is {@code null} and its delta cost is {@link #MONTH}, a value large enough to
 * sort such candidates last without overflowing when summed. {@link AbstractInsertionAlgorithm} recognises a sentinel
 * by its {@code null} candidate plan.
 *
 * <p>The fields are final, so a candidate is shallowly immutable; the plans and node it references are mutable and
 * are not copied. {@code equals}, {@code hashCode} and {@code toString} cover all four fields (and therefore compare,
 * hash and print whole plans); {@code equals} is subclass-aware through {@code canEqual} and does not call
 * {@code super}.
 *
 * @param <P> the plan type
 * @param <N> the node type
 */
@Data
public class InsertionCandidate<P extends Planable<P, N, ?>, N extends Nodeable<N>> {

    /**
     * Sentinel delta cost (30 days in milliseconds, {@code 2_592_000_000}) marking a node that cannot be inserted into
     * a plan: larger than any realistic insertion delta, yet small enough that the regret computation's sum over the
     * plans cannot overflow a {@code long} for any realistic number of plans.
     */
    protected static final long MONTH = 2592000000L;

    /**
     * The plan the node would be inserted into, as found in the caller's plan list; never modified by the insertion.
     */
    private final P originalPlan;
    /**
     * The copy of the original plan with the node inserted, or {@code null} for the "cannot insert" sentinel.
     */
    private final P candidatePlan;
    /**
     * The node the candidate is about: for a real candidate the copy inserted into the candidate plan, for a sentinel
     * built by {@link AbstractInsertionAlgorithm} the caller's customer.
     */
    private final N newNode;
    /**
     * The cost the insertion adds: {@code candidatePlan.getCost() - originalPlan.getCost()} for a real candidate,
     * {@link #MONTH} for the sentinel. May be negative (for example with a non-metric matrix).
     */
    private final long deltaCost;

    /**
     * A real candidate: {@code candidatePlan} is {@code originalPlan} with {@code newNode} inserted, and the delta is
     * {@code candidatePlan.getCost() - originalPlan.getCost()}, read once here, so both costs must already reflect the
     * plans' nodes.
     *
     * @param originalPlan  the plan before the insertion
     * @param candidatePlan the copy with {@code newNode} inserted; must not be {@code null}
     * @param newNode       the inserted node
     * @throws NullPointerException if {@code originalPlan} or {@code candidatePlan} is {@code null}
     */
    public InsertionCandidate(P originalPlan, P candidatePlan, N newNode) {
        this.originalPlan = originalPlan;
        this.candidatePlan = candidatePlan;
        this.newNode = newNode;
        this.deltaCost = candidatePlan.getCost() - originalPlan.getCost();
    }

    /**
     * The "cannot insert" sentinel for {@code newNode} into {@code originalPlan} ({@code candidatePlan == null},
     * delta {@link #MONTH}).
     *
     * @param originalPlan the plan that cannot take the node
     * @param newNode      the node that cannot be inserted
     */
    public InsertionCandidate(P originalPlan, N newNode) {
        this.originalPlan = originalPlan;
        this.candidatePlan = null;
        this.newNode = newNode;
        this.deltaCost = MONTH;
    }

}
