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
 * Exceptions and error values shared by the library.
 *
 * <ul>
 *   <li>{@link com.github.liliumsoftware.optimization.exception.CancelException}: thrown from inside the algorithms'
 *       loops once a run has been cancelled or its thread interrupted.</li>
 *   <li>{@link com.github.liliumsoftware.optimization.exception.PlanningException}: never thrown by the library;
 *       offered to consumers' runners for their own planning failures.</li>
 *   <li>{@link com.github.liliumsoftware.optimization.exception.ObjectError}: a user-facing message bound to an
 *       object's code, produced by
 *       {@link com.github.liliumsoftware.optimization.feasibility.FeasibilityChecker#getErrors(java.util.List)}.</li>
 * </ul>
 *
 * <p>Invalid input is reported with the standard {@link java.lang.IllegalArgumentException} (for example duplicate
 * node codes) and {@link java.lang.IllegalStateException} (for example a query before the cost matrix is
 * initialized), and cost overflow with {@link java.lang.ArithmeticException}.
 */
package com.github.liliumsoftware.optimization.exception;
