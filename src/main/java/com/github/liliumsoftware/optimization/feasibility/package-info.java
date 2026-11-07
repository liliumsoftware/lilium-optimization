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
 * Per-plan feasibility, the domain constraints (capacity, time windows, …) the search must respect.
 *
 * <p>Consumers implement {@link com.github.liliumsoftware.optimization.feasibility.FeasibilityChecker}, which returns
 * a {@link com.github.liliumsoftware.optimization.feasibility.FeasibilityResult} for one plan. The 2-opt local
 * searches and the insertion algorithm consult it for candidate plans, and every inter-route algorithm reaches it
 * through the local search and insertion it is composed with, so it sits on the hot path: it must be cheap,
 * side-effect free and thread-safe, and the shared
 * {@link com.github.liliumsoftware.optimization.feasibility.FeasibilityResult#FEASIBLE} instance avoids an allocation
 * per feasible check. After planning, {@code FeasibilityChecker#getErrors} turns infeasible plans into
 * {@link com.github.liliumsoftware.optimization.exception.ObjectError}s.
 */
package com.github.liliumsoftware.optimization.feasibility;
