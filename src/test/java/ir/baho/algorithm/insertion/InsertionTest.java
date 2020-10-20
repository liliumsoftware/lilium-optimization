package ir.baho.algorithm.insertion;

import ir.baho.algorithm.support.AlwaysFeasibleChecker;
import ir.baho.algorithm.support.MatrixCostCalculation;
import ir.baho.algorithm.support.MaxCustomersFeasibilityChecker;
import ir.baho.algorithm.support.TestInsertion;
import ir.baho.algorithm.support.TestInsertionCandidate;
import ir.baho.algorithm.support.TestNode;
import ir.baho.algorithm.support.TestPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins cheapest/regret insertion: delta-cost correctness, the cannot-insert sentinel, and feasibility gating.
 */
class InsertionTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);

    private MatrixCostCalculation calc(List<TestNode> nodes) {
        MatrixCostCalculation calc = new MatrixCostCalculation();
        calc.initializeCostMatrix(nodes);
        return calc;
    }

    @Test
    void cheapestInsertionPicksMinimalDeltaPosition() {
        TestNode a = TestNode.customer("A", 0, 10);
        TestNode b = TestNode.customer("B", 0, 30);
        TestNode mid = TestNode.customer("M", 0, 20);   // best placed between A and B
        MatrixCostCalculation calc = calc(List.of(origin, a, b, mid));

        TestPlan plan = new TestPlan("p", new ArrayList<>(List.of(origin, a, b)));
        calc.calculateCost(plan);
        TestInsertion insertion = new TestInsertion(calc, new AlwaysFeasibleChecker(), false);

        TestInsertionCandidate candidate = insertion.getBestInsertionCandidate(List.of(plan), mid);
        assertThat(candidate).isNotNull();
        // O->A->M->B: inserting M between A(10) and B(30) adds |10-20|+|20-30|-|10-30| = 10+10-20 = 0.
        assertThat(candidate.getDeltaCost()).isZero();
        assertThat(Objects.requireNonNull(candidate.getCandidatePlan(), "no slot found").getNodes())
                .containsExactly(origin, a, mid, b);
    }

    @Test
    void deltaCostEqualsCandidateMinusOriginalCost() {
        TestNode a = TestNode.customer("A", 0, 10);
        TestNode b = TestNode.customer("B", 0, 30);
        TestNode far = TestNode.customer("F", 0, 100);
        MatrixCostCalculation calc = calc(List.of(origin, a, b, far));

        TestPlan plan = new TestPlan("p", new ArrayList<>(List.of(origin, a, b)));
        long originalCost = calc.calculateCost(plan);
        TestInsertion insertion = new TestInsertion(calc, new AlwaysFeasibleChecker(), false);

        TestInsertionCandidate candidate = insertion.getBestInsertionCandidate(List.of(plan), far);
        assertThat(candidate.getDeltaCost())
                .isEqualTo(Objects.requireNonNull(candidate.getCandidatePlan()).getCost() - originalCost);
        assertThat(candidate.getDeltaCost()).isPositive();
    }

    @Test
    void insertIntoEmptyPlanListReturnsNothing() {
        MatrixCostCalculation calc = calc(List.of(origin, TestNode.customer("A", 1, 1)));
        TestInsertion insertion = new TestInsertion(calc, new AlwaysFeasibleChecker(), false);
        assertThat(insertion.insert(new ArrayList<>(), List.of(TestNode.customer("A", 1, 1)))).isEmpty();
    }

    @Test
    void infeasiblePlanYieldsNoInsertionCandidate() {
        TestNode a = TestNode.customer("A", 0, 10);
        TestNode b = TestNode.customer("B", 0, 20);
        MatrixCostCalculation calc = calc(List.of(origin, a, b));
        TestPlan plan = new TestPlan("p", new ArrayList<>(List.of(origin, a)));
        calc.calculateCost(plan);
        // Plan already holds its max of 1 customer, so nothing else may be inserted.
        TestInsertion insertion = new TestInsertion(calc, new MaxCustomersFeasibilityChecker(1), false);
        assertThat(insertion.getBestInsertionCandidate(List.of(plan), b)).isNull();
    }

    @Test
    void insertAllReturnsEveryInsertedNode() {
        TestNode a = TestNode.customer("A", 0, 10);
        TestNode b = TestNode.customer("B", 0, 20);
        TestNode c = TestNode.customer("C", 0, 30);
        MatrixCostCalculation calc = calc(List.of(origin, a, b, c));
        TestPlan plan = new TestPlan("p", new ArrayList<>(List.of(origin, a)));
        calc.calculateCost(plan);
        List<TestPlan> plans = new ArrayList<>(List.of(plan));
        TestInsertion insertion = new TestInsertion(calc, new AlwaysFeasibleChecker(), false);

        List<TestNode> inserted = insertion.insert(plans, List.of(b, c));
        assertThat(inserted).containsExactlyInAnyOrder(b, c);
        assertThat(plans.get(0).getNodes()).contains(b, c);
    }

    @Test
    void regretAndCheapestBothInsertAllFeasibleNodes() {
        TestNode a = TestNode.customer("A", 0, 10);
        TestNode b = TestNode.customer("B", 5, 5);
        TestNode c = TestNode.customer("C", 20, 2);
        MatrixCostCalculation calc = calc(List.of(origin, a, b, c));

        for (boolean regret : new boolean[] {false, true}) {
            TestPlan plan = new TestPlan("p", new ArrayList<>(List.of(origin, a)));
            calc.calculateCost(plan);
            List<TestPlan> plans = new ArrayList<>(List.of(plan));
            List<TestNode> inserted = new TestInsertion(calc, new AlwaysFeasibleChecker(), regret)
                    .insert(plans, List.of(b, c));
            assertThat(inserted).as("regret=%s", regret).containsExactlyInAnyOrder(b, c);
        }
    }

    @Test
    void cannotInsertSentinelHasMonthDeltaAndNullCandidate() {
        TestNode a = TestNode.customer("A", 0, 10);
        TestPlan plan = new TestPlan("p", List.of(origin, a));
        InsertionCandidate<TestPlan, TestNode> sentinel = new TestInsertionCandidate(plan, a);
        assertThat(sentinel.getCandidatePlan()).isNull();
        assertThat(sentinel.getDeltaCost()).isEqualTo(2592000000L);
    }

}
