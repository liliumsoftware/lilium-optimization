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

/**
 * Outcome of checking a single plan's feasibility with {@link FeasibilityChecker#getResult}. An infeasible result
 * carries a message {@link #getCause() cause} key plus {@link #getParams() params} for localization, which
 * {@link FeasibilityChecker#getErrors(java.util.List)} resolves through {@link FeasibilityChecker#getMessage}. The
 * shared {@link #FEASIBLE} instance lets the common, feasible case avoid allocating a result object on the hot path.
 */
public interface FeasibilityResult {

    /**
     * Shared feasible outcome: {@link #isFeasible()} is {@code true}, {@link #getCause()} is {@code null} and
     * {@link #getParams()} returns one shared empty array on every call (nothing is allocated per call; an empty
     * array has no elements to change, so sharing it is safe). Stateless, so it may be returned from any thread.
     */
    FeasibilityResult FEASIBLE = new FeasibilityResult() {
        private static final Object[] NO_PARAMS = {};

        @Override
        public boolean isFeasible() {
            return true;
        }

        @Override
        public String getCause() {
            return null;
        }

        @Override
        public Object[] getParams() {
            return NO_PARAMS;
        }
    };

    /**
     * Whether the plan passed.
     *
     * <p>The default returns {@code false}, so a custom result is infeasible unless it says otherwise.
     *
     * @return {@code true} if the plan is feasible
     */
    default boolean isFeasible() {
        return false;
    }

    /**
     * Message key of the violated constraint.
     *
     * @return the key, or {@code null} when feasible
     */
    String getCause();

    /**
     * Message parameters for {@link #getCause()}.
     *
     * @return the parameters; should never be {@code null} (use an empty array)
     */
    Object[] getParams();

}
