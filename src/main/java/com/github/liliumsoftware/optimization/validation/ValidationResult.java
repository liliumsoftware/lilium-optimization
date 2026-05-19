package com.github.liliumsoftware.optimization.validation;

/**
 * A single reason a node failed pre-planning validation: a message {@code cause} key plus its {@code params}.
 */
public interface ValidationResult {

    /**
     * Message key of the violated rule.
     */
    String getCause();

    /**
     * Message parameters for {@link #getCause()}; never {@code null}.
     */
    Object[] getParams();

}
