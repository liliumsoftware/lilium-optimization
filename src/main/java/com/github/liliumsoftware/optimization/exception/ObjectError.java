package com.github.liliumsoftware.optimization.exception;

/**
 * A validation/feasibility error: a human-readable {@code defaultMessage} bound to the offending {@code objectName}.
 */
public record ObjectError(String defaultMessage, String objectName) {

}
