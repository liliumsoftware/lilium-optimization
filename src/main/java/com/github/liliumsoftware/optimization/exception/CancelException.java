package com.github.liliumsoftware.optimization.exception;

/**
 * Thrown from inside the hot loops to abort a cancelled run, discarding any partial result.
 */
public class CancelException extends RuntimeException {

    /**
     * Creates an exception without a cause.
     *
     * @param message why the run was cancelled
     */
    public CancelException(String message) {
        super(message);
    }

}
