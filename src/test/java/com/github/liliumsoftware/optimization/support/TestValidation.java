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
