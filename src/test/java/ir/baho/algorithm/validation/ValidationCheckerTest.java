package ir.baho.algorithm.validation;

import ir.baho.algorithm.support.TestInvalidNode;
import ir.baho.algorithm.support.TestNode;
import ir.baho.algorithm.support.TestValidation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(validation.getInvalidNodes().get(0).getCause())
                .contains(TestValidation.DEMAND_REQUIRED)
                .contains(TestValidation.CODE_REQUIRED);
    }

    @Test
    void causesAreResolvedThroughTheMessageLookupWithTheirParameters() {
        validation.validate(List.of(TestNode.customer("zero", 0, 20, 0)));

        // The demand rule passes one parameter, so the resolved message reflects that; the code rule passes none.
        assertThat(validation.getInvalidNodes().get(0).getCause())
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

}
