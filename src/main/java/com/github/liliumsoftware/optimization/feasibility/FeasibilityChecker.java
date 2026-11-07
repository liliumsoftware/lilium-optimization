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

package com.github.liliumsoftware.optimization.feasibility;

import com.github.liliumsoftware.optimization.domain.Planable;
import com.github.liliumsoftware.optimization.exception.ObjectError;

import java.util.ArrayList;
import java.util.List;

/**
 * Judges whether a single plan is feasible (capacity, time windows, whatever the domain enforces). The 2-opt local
 * searches and the insertion algorithm consult it for candidate plans; the inter-route algorithms reach it through the
 * local search and insertion they are composed with. {@link #getErrors(List)} turns infeasible plans into user-facing
 * {@link ObjectError}s after the fact.
 *
 * <p><strong>Hot path.</strong> {@link #getResult} runs inside the search loops, so it must be cheap. It must also be
 * free of side effects: the library only asks about candidates that could be accepted (2-opt and insertion check
 * feasibility only for a candidate whose cost beats the best found so far), so how often, and for which candidates,
 * it is called is an implementation detail. Implementations must be thread-safe, because the Clarke-Wright λ/β sweep
 * and the zone groups run local searches in parallel.
 *
 * @param <P> the plan type
 */
public interface FeasibilityChecker<P extends Planable<P, ?, ?>> {

    /**
     * Judges {@code plan}. Must not modify the plan or depend on being called for any particular candidate (see the
     * class description).
     *
     * @param plan the candidate plan, typically a modified copy
     * @return the outcome, never {@code null}; return {@link FeasibilityResult#FEASIBLE} for a feasible plan to avoid
     *         an allocation
     */
    FeasibilityResult getResult(P plan);

    /**
     * Resolves a {@link FeasibilityResult#getCause() cause key} and its parameters to a user-facing message.
     *
     * @param key    the message key of the violated constraint
     * @param params the message parameters
     * @return the resolved message
     */
    String getMessage(String key, Object... params);

    /**
     * One {@link ObjectError} per infeasible plan, in list order, carrying the {@link #getMessage resolved message}
     * of the result's cause and bound to the assigned resource's code, or to the plan's own code when the plan has no
     * resource yet. Calls {@link #getResult} once per plan.
     *
     * @param plans the plans to check; not modified
     * @return a new mutable list, empty when every plan is feasible
     */
    default List<ObjectError> getErrors(List<P> plans) {
        List<ObjectError> errors = new ArrayList<>();
        for (P plan : plans) {
            FeasibilityResult result = getResult(plan);
            if (!result.isFeasible()) {
                // An unassigned plan is legitimate before assignment; bind the error to the plan itself then.
                String objectName = plan.getTrackable() != null ? plan.getTrackable().getCode() : plan.getCode();
                errors.add(new ObjectError(getMessage(result.getCause(), result.getParams()), objectName));
            }
        }
        return errors;
    }

}
