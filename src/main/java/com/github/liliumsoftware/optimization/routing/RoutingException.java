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

package com.github.liliumsoftware.optimization.routing;

import java.io.Serial;

/**
 * Raised by {@link RoutingProvider} when a routing request fails: the server cannot be reached, the exchange times out
 * (cause {@link java.net.http.HttpTimeoutException}) or the calling thread is interrupted (cause
 * {@link InterruptedException}, interrupt flag restored), the server returns a non-200 status, or its response cannot
 * be parsed or lacks the expected routes, fields or table shape. Invalid caller input (a bad coordinate or base URL)
 * is reported as {@link IllegalArgumentException} instead. Unchecked.
 */
public class RoutingException extends RuntimeException {

    /**
     * Pinned to the value the JVM computed for the 3.0.1 release, so instances serialized by it still read back.
     */
    @Serial
    private static final long serialVersionUID = -1803339220209367941L;

    /**
     * Creates an exception without a cause.
     *
     * @param message what the routing service did or returned
     */
    public RoutingException(String message) {
        super(message);
    }

    /**
     * Creates an exception wrapping the underlying I/O, timeout, interrupt or parsing failure.
     *
     * @param message   what went wrong with the request
     * @param throwable the underlying failure; may be {@code null}
     */
    public RoutingException(String message, Throwable throwable) {
        super(message, throwable);
    }

}
