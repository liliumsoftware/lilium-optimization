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

/**
 * Raised when a routing request fails: the server cannot be reached or the call is interrupted, it returns a
 * non-200 status, or its response cannot be parsed or lacks the expected routes or fields.
 */
public class RoutingException extends RuntimeException {

    /**
     * Creates an exception without a cause.
     *
     * @param message what the routing service did or returned
     */
    public RoutingException(String message) {
        super(message);
    }

    /**
     * Wraps the underlying I/O or parsing failure.
     */
    public RoutingException(String message, Throwable throwable) {
        super(message, throwable);
    }

}
