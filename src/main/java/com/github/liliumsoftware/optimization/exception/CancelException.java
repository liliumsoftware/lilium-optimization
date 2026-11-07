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
 * Aborts a cancelled run. Thrown by {@link com.github.liliumsoftware.optimization.BaseAlgorithm#checkCancel()}, which
 * the algorithms call inside their hot loops, once
 * {@link com.github.liliumsoftware.optimization.BaseAlgorithm#cancel()} has been called on the algorithm (directly,
 * through a composite algorithm or through
 * {@link com.github.liliumsoftware.optimization.BaseAlgorithmRunner#cancel()}) or the thread running it has been
 * interrupted; the thread's interrupt status is left set.
 *
 * <p>The aborted call returns no result. Inter-route algorithms commit accepted moves by replacing elements of the plan
 * list they were given, so that list may already hold the moves accepted before the abort; the plan objects
 * originally passed in are never modified.
 */
public class CancelException extends RuntimeException {

    /**
     * Pinned to the value the JVM computed for the 3.0.1 release, so instances serialized by it still read back.
     */
    @Serial
    private static final long serialVersionUID = -7498818888117772306L;

    /**
     * Creates an exception without a cause.
     *
     * @param message why the run was cancelled
     */
    public CancelException(String message) {
        super(message);
    }

}
