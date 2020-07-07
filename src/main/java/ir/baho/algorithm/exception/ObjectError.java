package ir.baho.algorithm.exception;

/**
 * A validation/feasibility error: a human-readable {@code defaultMessage} bound to the offending {@code objectName}.
 */
public final class ObjectError {
    private final String defaultMessage;
    private final String objectName;

    /**
     * Creates a {@code ObjectError} from its components.
     */
    public ObjectError(String defaultMessage, String objectName) {
        this.defaultMessage = defaultMessage;
        this.objectName = objectName;
    }

    /**
     * Returns the {@code defaultMessage} component.
     */
    public String defaultMessage() {
        return defaultMessage;
    }

    /**
     * Returns the {@code objectName} component.
     */
    public String objectName() {
        return objectName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ObjectError)) {
            return false;
        }
        ObjectError other = (ObjectError) o;
        return java.util.Objects.equals(defaultMessage, other.defaultMessage)
                && java.util.Objects.equals(objectName, other.objectName);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(defaultMessage, objectName);
    }

    @Override
    public String toString() {
        return "ObjectError["
                + "defaultMessage=" + defaultMessage
                + ", objectName=" + objectName
                + "]";
    }
}
