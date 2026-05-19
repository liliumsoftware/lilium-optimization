package com.github.liliumsoftware.optimization.clarkewright;

import com.github.liliumsoftware.optimization.OptimizationType;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.NoOpLocalSearch;
import com.github.liliumsoftware.optimization.support.TestAssignment;
import com.github.liliumsoftware.optimization.support.TestClarkeWright;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        // With a symmetric metric s(i,j) == s(j,i) == 20 at lambda 1, so every convex blend is 20:
        // result = beta * max + (1 - beta) * min = 0.5 * 20 + 0.5 * 20 = 20.
        // (Regression: the formula used to *add* beta, giving 30.5 - a constant shift that weighted the larger
        // orientation fully whatever beta was.)
        assertThat(cw.savingValue(origin, i, j, 1.0, 0.5)).isEqualTo(20.0);
        assertThat(cw.savingValue(origin, i, j, 1.0, 1.0)).isEqualTo(20.0);
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
    void betaSavingsUseTheBlendedFormulaWhenBetaIsPositive() {
        TestClarkeWright cw = clarkeWright();

        List<Saving> plain = cw.savings(origin, List.of(i, j), 1.0, 0);
        List<Saving> blended = cw.savings(origin, List.of(i, j), 1.0, 0.5);

        // Under this symmetric metric both orientations are worth 20, so any convex blend equals the plain saving;
        // the beta path must still yield the same pairs, best-first. (The asymmetric case is covered separately.)
        assertThat(plain).extracting(Saving::value).containsExactly(20.0, 20.0);
        assertThat(blended).hasSameSizeAs(plain);
        assertThat(blended).extracting(Saving::value).containsExactly(20.0, 20.0);
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
    void betaWeightsTheBetterOrientationUnderAnAsymmetricCostModel() {
        // Depot O, I and J placed so that s(I,J) != s(J,I) under a direction-dependent metric: use the plan-aware
        // cost hook of an anonymous calculation that charges 2x when travelling "down" (decreasing y).
        TestNode o = TestNode.origin("O", 0, 0);
        TestNode p = TestNode.customer("P", 0, 10, 1);
        TestNode q = TestNode.customer("Q", 0, 30, 1);
        PathCostCalculation<TestPlan, TestNode, Long> calculation = new PathCostCalculation<>(
                OptimizationType.NO_RESTRICTION, Map.of()) {
            @Override
            protected Long[][] getCostMatrix(List<TestNode> nodes) {
                Long[][] matrix = new Long[nodes.size()][nodes.size()];
                for (int r = 0; r < nodes.size(); r++) {
                    for (int c = 0; c < nodes.size(); c++) {
                        long base = nodes.get(r).manhattanTo(nodes.get(c));
                        matrix[r][c] = nodes.get(c).getY() < nodes.get(r).getY() ? 2 * base : base;
                    }
                }
                return matrix;
            }
        };
        calculation.initializeCostMatrix(List.of(o, p, q));
        TestClarkeWright cw = new TestClarkeWright(calculation, new TestAssignment(List.of(new TestTrackable("T", 9))),
                new NoOpLocalSearch(), null, OptimizationType.NO_RESTRICTION);

        double spq = cw.savingValue(o, p, q, 1.0);   // c(P,O)=2*10 + c(O,Q)=30 - c(P,Q)=20  = 30
        double sqp = cw.savingValue(o, q, p, 1.0);   // c(Q,O)=2*30 + c(O,P)=10 - c(Q,P)=2*20 = 30
        assertThat(spq).isEqualTo(30.0);
        assertThat(sqp).isEqualTo(30.0);
        // Make the two directions differ via lambda, then check the blend sits between min and max.
        double a = cw.savingValue(o, p, q, 0.5);     // 20 + 30 - 10 = 40
        double b = cw.savingValue(o, q, p, 0.5);     // 60 + 10 - 20 = 50
        assertThat(a).isEqualTo(40.0);
        assertThat(b).isEqualTo(50.0);
        assertThat(cw.savingValue(o, p, q, 0.5, 1.0)).isEqualTo(50.0);   // beta 1 -> the better direction
        assertThat(cw.savingValue(o, p, q, 0.5, 0.5)).isEqualTo(45.0);   // beta 0.5 -> the average
        assertThat(cw.savingValue(o, p, q, 0.5, 0.2)).isEqualTo(0.2 * 50 + 0.8 * 40);
    }

}
