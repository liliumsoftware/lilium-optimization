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
 * Plan code generation.
 *
 * <p>{@link com.github.liliumsoftware.optimization.generator.PlanCodeGenerator} is the strategy a
 * {@link com.github.liliumsoftware.optimization.BaseAlgorithmRunner} holds for its concrete runner to name plans,
 * typically while finalizing them; the algorithms never call it. Plan codes must be unique within a run.
 * {@link com.github.liliumsoftware.optimization.generator.DateTimePlanCodeGenerator}, the bundled implementation,
 * mints timestamp-plus-random-suffix codes that are distinct per generator instance and is thread-safe.
 */
package com.github.liliumsoftware.optimization.generator;
