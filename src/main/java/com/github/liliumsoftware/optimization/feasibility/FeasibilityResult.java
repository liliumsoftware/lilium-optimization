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
 * Outcome of checking a single plan's feasibility. An infeasible result carries a message {@code cause} key
 * plus {@code params} for localization. The shared {@link #FEASIBLE} singleton avoids allocating on the hot
 * path, where the common case is "feasible".
 */
public interface FeasibilityResult {

    /**
     * Shared feasible outcome; reused to avoid per-check allocation in the search loops.
     */
    FeasibilityResult FEASIBLE = new FeasibilityResult() {
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
            return new Object[0];
        }
    };

    /**
     * Whether the plan passed; {@code false} by default so a custom result is infeasible unless it says otherwise.
     */
    default boolean isFeasible() {
        return false;
    }

    /**
     * Message key of the violated constraint, or {@code null} when feasible.
     */
    String getCause();

    /**
     * Message parameters for {@link #getCause()}; never {@code null}.
     */
    Object[] getParams();

}
