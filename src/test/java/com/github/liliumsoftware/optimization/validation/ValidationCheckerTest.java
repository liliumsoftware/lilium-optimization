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

package com.github.liliumsoftware.optimization.validation;

import com.github.liliumsoftware.optimization.support.TestInvalidNode;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestValidation;
import com.github.liliumsoftware.optimization.support.TestValidationResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validation runs once, before planning, precisely so that structurally unplannable customers never reach the
 * hot search loops. The partition therefore has to be total (every input node lands in exactly one bucket) and
 * repeatable (a second run must not accumulate results from the first).
 */
class ValidationCheckerTest {

    private final TestValidation validation = new TestValidation();

    @Test
    void partitionsNodesIntoValidAndInvalid() {
        TestNode valid = TestNode.customer("ok", 0, 10, 5);
        TestNode zeroDemand = TestNode.customer("zero", 0, 20, 0);

        validation.validate(List.of(valid, zeroDemand));

        assertThat(validation.getValidNodes()).containsExactly(valid);
        assertThat(validation.getInvalidNodes()).extracting(TestInvalidNode::getCode).containsExactly("zero");
    }

    @Test
    void anInvalidNodeCarriesEveryCauseItAccumulated() {
        TestNode doublyInvalid = TestNode.customer("  ", 0, 10, 0);   // blank code and no demand

        validation.validate(List.of(doublyInvalid));

        assertThat(validation.getInvalidNodes()).hasSize(1);
        assertThat(validation.getInvalidNodes().getFirst().getCause())
                .contains(TestValidation.DEMAND_REQUIRED)
                .contains(TestValidation.CODE_REQUIRED);
    }

    @Test
    void causesAreResolvedThroughTheMessageLookupWithTheirParameters() {
        validation.validate(List.of(TestNode.customer("zero", 0, 20, 0)));

        // The demand rule passes one parameter, so the resolved message reflects that; the code rule passes none.
        assertThat(validation.getInvalidNodes().getFirst().getCause())
                .isEqualTo(TestValidation.DEMAND_REQUIRED + "1");
    }

    @Test
    void everyInputNodeEndsUpInExactlyOneBucket() {
        List<TestNode> nodes = List.of(
                TestNode.customer("a", 0, 10, 1),
                TestNode.customer("b", 0, 20, 0),
                TestNode.customer("c", 0, 30, 3),
                TestNode.customer("", 0, 40, 4));

        validation.validate(nodes);

        assertThat(validation.getValidNodes().size() + validation.getInvalidNodes().size())
                .isEqualTo(nodes.size());
        assertThat(validation.getValidNodes()).extracting(TestNode::getCode).containsExactly("a", "c");
        assertThat(validation.getInvalidNodes()).extracting(TestInvalidNode::getCode).containsExactly("b", "");
    }

    @Test
    void aSecondRunReplacesTheFirstResultRatherThanAddingToIt() {
        validation.validate(List.of(TestNode.customer("bad", 0, 10, 0)));
        assertThat(validation.getInvalidNodes()).hasSize(1);

        validation.validate(List.of(TestNode.customer("good", 0, 10, 1)));

        assertThat(validation.getValidNodes()).extracting(TestNode::getCode).containsExactly("good");
        assertThat(validation.getInvalidNodes()).isEmpty();
    }

    @Test
    void anEmptyInputProducesTwoEmptyBuckets() {
        validation.validate(List.of());

        assertThat(validation.getValidNodes()).isEmpty();
        assertThat(validation.getInvalidNodes()).isEmpty();
    }

    @Test
    void beforeAnyRunTheBucketsAreUnset() {
        TestValidation fresh = new TestValidation();

        assertThat(fresh.getValidNodes()).isNull();
        assertThat(fresh.getInvalidNodes()).isNull();
    }

    @Test
    void publishedPartitionsAreUnmodifiableSnapshots() {
        TestValidation checker = new TestValidation();
        checker.validate(new ArrayList<>(List.of(TestNode.customer("A", 0, 1, 1), TestNode.customer("B", 0, 2, 1))));
        List<TestNode> valid = checker.getValidNodes();

        assertThatThrownBy(() -> valid.add(TestNode.customer("C", 0, 3, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> checker.getInvalidNodes().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        // A later validate() publishes a new partition; the earlier snapshot is untouched.
        checker.validate(List.of(TestNode.customer("C", 0, 3, 1)));
        assertThat(valid).extracting(TestNode::getCode).containsExactly("A", "B");
        assertThat(checker.getValidNodes()).extracting(TestNode::getCode).containsExactly("C");
    }

    @Test
    void afterTwoRunsBothGettersReflectTheSecondRun() {
        // Each run has a valid and an invalid node, with different codes, so a getter still showing the first run
        // (or a mix of the two) fails.
        validation.validate(List.of(TestNode.customer("v1", 0, 1, 1), TestNode.customer("i1", 0, 2, 0)));
        validation.validate(List.of(TestNode.customer("i2", 0, 3, 0), TestNode.customer("v2", 0, 4, 1),
                TestNode.customer("v3", 0, 5, 2)));

        assertThat(validation.getValidNodes()).extracting(TestNode::getCode).containsExactly("v2", "v3");
        assertThat(validation.getInvalidNodes()).extracting(TestInvalidNode::getCode).containsExactly("i2");
    }

    @Test
    void bothPublishedListsRejectEveryKindOfModification() {
        validation.validate(List.of(TestNode.customer("v", 0, 1, 1), TestNode.customer("i", 0, 2, 0)));
        List<TestNode> valid = validation.getValidNodes();
        List<TestInvalidNode> invalid = validation.getInvalidNodes();

        assertThatThrownBy(() -> valid.set(0, TestNode.customer("x", 0, 9, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(valid::removeFirst).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> invalid.add(new TestInvalidNode("x", "z", "c")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> invalid.removeIf(node -> true)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(validation.getValidNodes()).hasSize(1);
        assertThat(validation.getInvalidNodes()).hasSize(1);
    }

    @Test
    void theCallersInputListIsNeitherModifiedNorShared() {
        List<TestNode> input = new ArrayList<>(List.of(TestNode.customer("v", 0, 1, 1), TestNode.customer("i", 0, 2,
                0)));

        validation.validate(input);
        input.clear();

        assertThat(validation.getValidNodes()).extracting(TestNode::getCode).containsExactly("v");
        assertThat(validation.getInvalidNodes()).hasSize(1);
    }

    @Test
    void anInvalidNodeConversionReturningNullIsKept() {
        // toInvalidNode is the subclass's business; a null conversion is published as a null element, not dropped.
        List<List<ValidationResult>> seen = new ArrayList<>();
        ValidationChecker<TestNode, TestInvalidNode> nullConverting = new ValidationChecker<>() {
            @Override
            protected void validate(TestNode node, Consumer<ValidationResult> consumer) {
                if (node.getDemand() <= 0) {
                    consumer.accept(new TestValidationResult("demand", node.getDemand()));
                    consumer.accept(new TestValidationResult("again"));
                }
            }

            @Override
            protected TestInvalidNode toInvalidNode(TestNode node, List<ValidationResult> results) {
                seen.add(results);
                return null;
            }

            @Override
            protected String getMessage(String key, Object... params) {
                return key;
            }
        };

        nullConverting.validate(List.of(TestNode.customer("a", 0, 1, 0), TestNode.customer("b", 0, 2, 1)));

        assertThat(nullConverting.getInvalidNodes()).hasSize(1).containsOnlyNulls();
        assertThat(nullConverting.getValidNodes()).extracting(TestNode::getCode).containsExactly("b");
        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst()).extracting(ValidationResult::getCause).containsExactly("demand", "again");
    }

}
