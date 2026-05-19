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
