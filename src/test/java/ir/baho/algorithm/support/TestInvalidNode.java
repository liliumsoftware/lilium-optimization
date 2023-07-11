package ir.baho.algorithm.support;

import ir.baho.algorithm.domain.InvalidNodeable;

/**
 * Concrete {@link InvalidNodeable}: a node validation rejected, carrying the joined causes. Inherits the
 * interface's {@code CUSTOMER} type default, since only customers are validated before planning.
 */
public record TestInvalidNode(String code, String zone, String cause) implements InvalidNodeable<TestInvalidNode> {

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getZone() {
        return zone;
    }

    @Override
    public String getCause() {
        return cause;
    }

    @Override
    public TestInvalidNode copy() {
        return new TestInvalidNode(code, zone, cause);
    }

    @Override
    public String toString() {
        return code + "!" + cause;
    }

}
