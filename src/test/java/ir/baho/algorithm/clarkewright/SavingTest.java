package ir.baho.algorithm.clarkewright;

import ir.baho.algorithm.OptimizationType;
import ir.baho.algorithm.support.MatrixCostCalculation;
import ir.baho.algorithm.support.NoOpLocalSearch;
import ir.baho.algorithm.support.TestAssignment;
import ir.baho.algorithm.support.TestClarkeWright;
import ir.baho.algorithm.support.TestNode;
import ir.baho.algorithm.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Clarke-Wright savings formulas, the candidate-pair generation, and the best-first ordering — the
 * inputs that decide which merges the greedy core even considers.
 */
class SavingTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode i = TestNode.customer("I", 0, 10, 1);
    private final TestNode j = TestNode.customer("J", 0, 30, 1);

    private TestClarkeWright clarkeWright() {
        return clarkeWright(List.of(origin, i, j));
    }

    private TestClarkeWright clarkeWright(List<TestNode> nodes) {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(nodes);
        return new TestClarkeWright(calculation, new TestAssignment(List.of(new TestTrackable("t", 100))),
                new NoOpLocalSearch(), null, OptimizationType.NO_RESTRICTION);
    }

    // ---------------------------------------------------------------- the formulas

    @Test
    void savingValueFollowsTheClassicFormula() {
        TestClarkeWright cw = clarkeWright();
        // c(I,O)=10, c(O,J)=30, c(I,J)=20 ; s = c(I,O)+c(O,J) - lambda*c(I,J)
        assertThat(cw.savingValue(origin, i, j, 1.0)).isEqualTo(10 + 30 - 1.0 * 20);
        assertThat(cw.savingValue(origin, i, j, 0.5)).isEqualTo(10 + 30 - 0.5 * 20);
        assertThat(cw.savingValue(origin, i, j, 0.0)).isEqualTo(40);
    }

    @Test
    void aLargerLambdaMakesLongMergesLessAttractive() {
        TestClarkeWright cw = clarkeWright();

        // λ scales the direct edge term, so the saving strictly decreases as λ grows.
        assertThat(cw.savingValue(origin, i, j, 2.0)).isLessThan(cw.savingValue(origin, i, j, 1.0));
        assertThat(cw.savingValue(origin, i, j, 1.0)).isLessThan(cw.savingValue(origin, i, j, 0.5));
    }

    @Test
    void betaVariantCombinesBothMergeDirections() {
        TestClarkeWright cw = clarkeWright();
        // With a symmetric metric s(i,j) == s(j,i) == 20 at lambda 1, so
        // result = (beta + max) + ((1 - beta) * min) = (0.5 + 20) + (0.5 * 20) = 30.5
        assertThat(cw.savingValue(origin, i, j, 1.0, 0.5)).isEqualTo(30.5);
    }

    @Test
    void savingIsSymmetricUnderASymmetricMetric() {
        TestClarkeWright cw = clarkeWright();

        assertThat(cw.savingValue(origin, i, j, 1.0)).isEqualTo(cw.savingValue(origin, j, i, 1.0));
    }

    // ---------------------------------------------------------------- pair generation

    @Test
    void everyOrderedCustomerPairGetsASavingAndSelfPairsAreExcluded() {
        TestNode third = TestNode.customer("K", 0, 50, 1);
        TestClarkeWright cw = clarkeWright(List.of(origin, i, j, third));

        List<Saving> savings = cw.savings(origin, List.of(i, j, third), 1.0, 0);

        // n customers yield n × (n - 1) directed pairs: both orientations, never a node with itself.
        assertThat(savings).hasSize(6);
        assertThat(savings).noneSatisfy(saving ->
                assertThat(saving.origin().getCode()).isEqualTo(saving.destination().getCode()));
    }

    @Test
    void theDepotIsNeverPartOfASavingPair() {
        TestClarkeWright cw = clarkeWright();

        // The origin is excluded even when it is handed in among the customers.
        List<Saving> savings = cw.savings(origin, List.of(origin, i, j), 1.0, 0);

        assertThat(savings).hasSize(2);
        assertThat(savings).allSatisfy(saving -> {
            assertThat(saving.origin().getCode()).isNotEqualTo("O");
            assertThat(saving.destination().getCode()).isNotEqualTo("O");
        });
    }

    @Test
    void savingsComeBackBestFirst() {
        TestNode near = TestNode.customer("N", 0, 12, 1);   // pairs cheaply with I
        TestClarkeWright cw = clarkeWright(List.of(origin, i, j, near));

        List<Saving> savings = cw.savings(origin, List.of(i, j, near), 1.0, 0);

        assertThat(savings).isSortedAccordingTo((left, right) -> Double.compare(right.value(), left.value()));
        assertThat(savings.getFirst().value()).isEqualTo(
                savings.stream().mapToDouble(Saving::value).max().orElseThrow());
    }

    @Test
    void reversedValueComparatorSortsBestFirstThenByOriginCode() {
        Saving low = new Saving(TestNode.customer("A", 0, 0, 1), j, 5);
        Saving high = new Saving(TestNode.customer("B", 0, 0, 1), j, 10);
        Saving lowLaterCode = new Saving(TestNode.customer("Z", 0, 0, 1), j, 5);

        List<Saving> savings = new ArrayList<>(List.of(lowLaterCode, low, high));
        savings.sort(Saving.reversedValueComparator());

        assertThat(savings).containsExactly(high, low, lowLaterCode);
    }

    @Test
    void savingRendersItsPairAndValueForLogging() {
        assertThat(new Saving(i, j, 12.5).toString())
                .contains("I").contains("J").contains("12.5");
    }

    @Test
    void betaSavingsAreProducedWhenBetaIsPositive() {
        TestClarkeWright cw = clarkeWright();

        List<Saving> plain = cw.savings(origin, List.of(i, j), 1.0, 0);
        List<Saving> blended = cw.savings(origin, List.of(i, j), 1.0, 0.5);

        // The beta path must produce the same pairs, in the same best-first order, with the blended value.
        assertThat(blended).hasSameSizeAs(plain);
        assertThat(blended.get(0).value()).isNotEqualTo(plain.get(0).value());
        assertThat(blended.get(0).value()).isEqualTo(30.5);
    }

}
