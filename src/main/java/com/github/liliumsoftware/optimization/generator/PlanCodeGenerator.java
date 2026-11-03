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

package com.github.liliumsoftware.optimization.generator;

/**
 * Strategy for minting plan codes, held by {@link com.github.liliumsoftware.optimization.BaseAlgorithmRunner} for the
 * concrete runner to use (typically while finalizing plans); the algorithms never call it. Plan codes must be unique
 * within a run (see {@link com.github.liliumsoftware.optimization.domain.Planable#getCode()}).
 * {@link DateTimePlanCodeGenerator} is the bundled implementation.
 */
public interface PlanCodeGenerator {

    /**
     * Mints a new plan code.
     *
     * @return a code distinct from every code this generator returned before
     */
    String generate();

}
