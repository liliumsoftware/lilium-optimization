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

package com.github.liliumsoftware.optimization.exception;

/**
 * A user-facing error bound to the object it concerns, in the shape of a validation framework's object error.
 * {@link com.github.liliumsoftware.optimization.feasibility.FeasibilityChecker#getErrors(java.util.List)} produces
 * one per infeasible plan. Despite the package, this is a value, not an exception.
 *
 * @param defaultMessage the resolved, human-readable message
 * @param objectName     the code of the offending object (for an infeasible plan: its resource's code, or the plan's
 *                       own code while it has no resource)
 */
public record ObjectError(String defaultMessage, String objectName) {

}
