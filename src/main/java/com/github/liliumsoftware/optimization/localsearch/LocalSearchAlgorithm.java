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

/**
 * Base for intra-plan improvement: {@link #apply(Planable)} returns an equal-or-better ordering of a single
 * plan's stops. Every construction and inter-plan move funnels touched plans through an implementation of
 * this type, so a no-op default ({@code apply} returning the input) is a valid identity local search.
 */
public abstract class LocalSearchAlgorithm<P extends Planable<P, ?, ?>> extends BaseAlgorithm {

    /**
     * Improves {@code plan}.
     *
     * @return a plan no worse than {@code plan}; may be {@code plan} itself if no improvement is found
     */
    public abstract P apply(P plan);

    /**
     * Whether this search applies to {@code plan} at all; {@code true} by default.
     */
    protected boolean supports(P plan) {
        return true;
    }

    /**
     * Whether the move defined by positions {@code i} and {@code j} may be tried; by default only when the node at
     * {@code i} is a customer (depots never move).
     */
    protected boolean supports(P plan, int i, int j) {
        return plan.getNodes().get(i).getType() == Nodeable.Type.CUSTOMER;
    }

}
