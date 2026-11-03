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
 * A single reason a node failed pre-planning validation: a message {@link #getCause() cause} key plus its
 * {@link #getParams() params}, reported by {@link ValidationChecker}'s per-node {@code validate} hook and resolved to
 * text with {@link ValidationChecker}'s {@code getMessage}.
 */
public interface ValidationResult {

    /**
     * Message key of the violated rule.
     *
     * @return the key
     */
    String getCause();

    /**
     * Message parameters for {@link #getCause()}.
     *
     * @return the parameters; should never be {@code null} (use an empty array)
     */
    Object[] getParams();

}
