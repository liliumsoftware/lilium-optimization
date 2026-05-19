package com.github.liliumsoftware.optimization.support;

import com.github.liliumsoftware.optimization.validation.ValidationChecker;
import com.github.liliumsoftware.optimization.validation.ValidationResult;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Concrete pre-planning validation with two independent rules, so a single node can accumulate more than one
 * cause: a customer needs a positive demand, and it needs a non-blank code.
 */
public final class TestValidation extends ValidationChecker<TestNode, TestInvalidNode> {

    public static final String DEMAND_REQUIRED = "demand.must.be.positive";
    public static final String CODE_REQUIRED = "code.must.not.be.blank";

    @Override
    protected void validate(TestNode node, Consumer<ValidationResult> consumer) {
        if (node.getDemand() <= 0) {
            consumer.accept(new TestValidationResult(DEMAND_REQUIRED, node.getDemand()));
        }
        if (node.getCode() == null || node.getCode().isBlank()) {
            consumer.accept(new TestValidationResult(CODE_REQUIRED));
        }
    }

    @Override
    protected TestInvalidNode toInvalidNode(TestNode node, List<ValidationResult> results) {
        return new TestInvalidNode(node.getCode(), node.getZone(),
                results.stream().map(this::getCause).collect(Collectors.joining("; ")));
    }

    @Override
    protected String getMessage(String key, Object... params) {
        return params.length == 0 ? key : key + params.length;
    }

}
