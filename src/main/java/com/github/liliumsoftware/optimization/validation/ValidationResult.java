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

package com.github.liliumsoftware.optimization.validation;

/**
 * A single reason a node failed pre-planning validation: a message {@code cause} key plus its {@code params}.
 */
public interface ValidationResult {

    /**
     * Message key of the violated rule.
     */
    String getCause();

    /**
     * Message parameters for {@link #getCause()}; never {@code null}.
     */
    Object[] getParams();

}
