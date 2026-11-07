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

import java.io.Serial;

/**
 * Unchecked failure for a planning run that cannot complete. The library itself never throws it (its algorithms
 * report bad input with {@link IllegalArgumentException} / {@link IllegalStateException} and aborts with
 * {@link CancelException}); it is offered to consumers'
 * {@link com.github.liliumsoftware.optimization.BaseAlgorithmRunner} implementations as a common type for their own
 * planning failures.
 */
public class PlanningException extends RuntimeException {

    /**
     * Pinned to the value the JVM computed for the 3.0.1 release, so instances serialized by it still read back.
     */
    @Serial
    private static final long serialVersionUID = 4311213161005661931L;

    /**
     * Creates an exception without a cause.
     *
     * @param msg what could not be planned
     */
    public PlanningException(String msg) {
        super(msg);
    }

    /**
     * Wraps an underlying failure; the message is the cause's {@code toString()}, or {@code null} without a
     * cause.
     *
     * @param e the underlying failure, or {@code null}
     */
    public PlanningException(Throwable e) {
        super(e);
    }

    /**
     * Wraps an underlying failure with a message.
     *
     * @param msg what could not be planned
     * @param e   the underlying failure
     */
    public PlanningException(String msg, Throwable e) {
        super(msg, e);
    }

}
