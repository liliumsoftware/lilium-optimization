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

package com.github.liliumsoftware.optimization.localsearch;

import com.github.liliumsoftware.optimization.BaseAlgorithm;
import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.domain.Planable;
import com.github.liliumsoftware.optimization.exception.CancelException;

/**
 * Base for intra-plan improvement: {@link #apply(Planable)} returns an equal-or-better ordering of a single plan's
 * stops. Clarke-Wright, exchange/relocate, destroy-repair, merge and un-merge funnel every plan they touch through an
 * implementation of this type, so it is the single place where a consumer plugs in route improvement (for example
 * {@link AbstractTwoOptAlgorithm} or {@link AbstractOpenTwoOptAlgorithm}). An implementation whose {@code apply}
 * returns its argument unchanged is a valid identity local search.
 *
 * <p><strong>Contract.</strong> {@code apply} must not modify its argument: it returns either a new plan (typically an
 * improved copy) or the argument itself, untouched. Callers rely on this: destroy-repair, for instance, runs the local
 * search over the plan objects of the caller's list when no move was accepted, and the library promises that the
 * caller's plan objects are never modified. Implementations should honour {@link #cancel()} (through
 * {@link #checkCancel()}) and {@link #interrupt()} (returning the best plan found so far) inside their loops; the
 * composite algorithms forward both signals to their local search.
 *
 * <p><strong>Thread-safety.</strong> Implementations should hold no mutable state beyond the two signal flags of
 * {@link BaseAlgorithm}, so that one instance may improve independent plans concurrently (the library calls it from
 * parallel zone groups and from the parallel Clarke-Wright sweep).
 *
 * @param <P> the plan type
 */
public abstract class LocalSearchAlgorithm<P extends Planable<P, ?, ?>> extends BaseAlgorithm {

    /**
     * Creates a local search with both signals cleared.
     */
    public LocalSearchAlgorithm() {
    }

    /**
     * Improves the stop order of {@code plan}.
     *
     * @param plan the plan to improve; must not be modified
     * @return a plan no worse than {@code plan}, or {@code plan} itself when no improvement is found or
     *         {@link #supports(Planable)} rejects it
     * @throws CancelException if the search was cancelled
     */
    public abstract P apply(P plan);

    /**
     * Whether this search applies to {@code plan} at all; the implementations in this package return an unsupported
     * plan unchanged. The default implementation returns {@code true}.
     *
     * @param plan the plan about to be improved
     * @return {@code true} if {@link #apply(Planable)} may work on {@code plan}
     */
    protected boolean supports(P plan) {
        return true;
    }

    /**
     * Whether the move defined by positions {@code i} and {@code j} of {@code plan} may be tried; the 2-opt variants
     * ask it for every position pair before building a candidate. The default implementation returns {@code true}
     * only when the node at {@code i} is a {@link Nodeable.Type#CUSTOMER} (depots never move).
     *
     * @param plan the current best plan
     * @param i    the first position of the move
     * @param j    the second (exclusive end) position of the move
     * @return {@code true} if the move may be evaluated
     */
    protected boolean supports(P plan, int i, int j) {
        return plan.getNodes().get(i).getType() == Nodeable.Type.CUSTOMER;
    }

}
