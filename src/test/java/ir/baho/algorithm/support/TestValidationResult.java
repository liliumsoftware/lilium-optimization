package ir.baho.algorithm.support;

import ir.baho.algorithm.validation.ValidationResult;

/**
 * Concrete {@link ValidationResult}: a message key plus its localization parameters.
 */
public record TestValidationResult(String cause, Object... params) implements ValidationResult {

    @Override
    public String getCause() {
        return cause;
    }

    @Override
    public Object[] getParams() {
        return params;
    }

}
