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

package com.github.liliumsoftware.optimization.domain;

/**
 * A node that pre-planning validation rejected, carrying the {@link #getCause() reason} it was excluded.
 * {@link com.github.liliumsoftware.optimization.validation.ValidationChecker} produces these through its
 * {@code toInvalidNode} hook; they never take part in planning.
 *
 * @param <N> the concrete invalid-node type (self type)
 */
public interface InvalidNodeable<N extends InvalidNodeable<N>> extends Nodeable<N> {

    /**
     * Human-readable reason the node was rejected.
     *
     * @return the reason, typically the resolved messages of every violated rule
     */
    String getCause();

    /**
     * The role of the rejected node.
     *
     * <p>The default returns {@link Type#CUSTOMER}, since only customers are validated before planning.
     *
     * @return {@link Type#CUSTOMER} unless overridden
     */
    @Override
    default Type getType() {
        return Type.CUSTOMER;
    }

}
