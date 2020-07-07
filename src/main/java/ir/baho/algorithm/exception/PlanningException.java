package ir.baho.algorithm.exception;

/**
 * Unchecked failure raised when a planning run cannot complete.
 */
public class PlanningException extends RuntimeException {

    /**
     * Creates an exception without a cause.
     *
     * @param msg what could not be planned
     */
    public PlanningException(String msg) {
        super(msg);
    }

    /**
     * Wraps an underlying failure.
     */
    public PlanningException(Throwable e) {
        super(e);
    }

    /**
     * Wraps an underlying failure with a message.
     */
    public PlanningException(String msg, Throwable e) {
        super(msg, e);
    }

}
