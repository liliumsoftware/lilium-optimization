package ir.baho.algorithm.performance;

import ir.baho.algorithm.OptimizationType;
import ir.baho.algorithm.optimization.ExchangeRelocateType;
import ir.baho.algorithm.support.AlwaysFeasibleChecker;
import ir.baho.algorithm.support.MatrixCostCalculation;
import ir.baho.algorithm.support.PlanInvariants;
import ir.baho.algorithm.support.Problems;
import ir.baho.algorithm.support.RecostingLocalSearch;
import ir.baho.algorithm.support.TestAssignment;
import ir.baho.algorithm.support.TestClarkeWright;
import ir.baho.algorithm.support.TestDestroyRepair;
import ir.baho.algorithm.support.TestExchange;
import ir.baho.algorithm.support.TestInsertion;
import ir.baho.algorithm.support.TestPlan;
import ir.baho.algorithm.support.TestTwoOpt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Smoke tests for catastrophic regressions only — an accidental infinite loop, an exponential blow-up, or a
 * memory explosion — not a benchmark.
 *
 * <p>The ceilings are deliberately enormous relative to the real runtime (the whole class finishes in a couple
 * of seconds on ordinary hardware), so they cannot fail because a machine is busy; they only fail if something
 * stops terminating in any reasonable time. No assertion here depends on wall-clock precision.
 */
class ScalabilitySmokeTest {

    @ParameterizedTest
    @CsvSource({"10, 5", "50, 10", "100, 20"})
    void constructionScalesToRealisticInstanceSizes(int customerCount, int vehicleCount) {
        Problems.Problem problem = Problems.random(customerCount, customerCount, vehicleCount, 20, 5_000);
        MatrixCostCalculation calculation = problem.calculation();

        List<TestPlan> plans = assertTimeoutPreemptively(Duration.ofMinutes(2), () ->
                new TestClarkeWright(calculation, problem.assignment(),
                        new TestTwoOpt(calculation, new AlwaysFeasibleChecker()),
                        problem.destination(), OptimizationType.NO_RESTRICTION)
                        .plan(problem.origin(), problem.customers()));

        PlanInvariants.assertNodeLayout(plans);
        PlanInvariants.assertNoDuplicateCustomers(plans);
        PlanInvariants.assertEveryPlanHasTrackable(plans);
        PlanInvariants.assertTrackablesAreDistinct(plans);
        PlanInvariants.assertCustomerCountWithinCapacity(plans);
        assertThat(plans).hasSizeLessThanOrEqualTo(vehicleCount);
    }

    @Test
    void theFullPipelineTerminatesOnAFiftyCustomerInstance() {
        Problems.Problem problem = Problems.random(4242L, 50, 10, 20, 5_000);
        MatrixCostCalculation calculation = problem.calculation();
        TestAssignment assignment = problem.assignment();

        List<TestPlan> plans = assertTimeoutPreemptively(Duration.ofMinutes(3), () -> {
            List<TestPlan> current = new ArrayList<>(new TestClarkeWright(calculation, assignment,
                    new TestTwoOpt(calculation, new AlwaysFeasibleChecker()),
                    problem.destination(), OptimizationType.NO_RESTRICTION)
                    .plan(problem.origin(), problem.customers()));
            current = new ArrayList<>(new TestExchange(calculation, assignment,
                    new RecostingLocalSearch(calculation),
                    new TestInsertion(calculation, new AlwaysFeasibleChecker(), false),
                    ExchangeRelocateType.SIMPLE, 5, 2, true).apply(current));
            return new ArrayList<>(new TestDestroyRepair(calculation, assignment,
                    new RecostingLocalSearch(calculation),
                    new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), 2, 0).apply(current));
        });

        PlanInvariants.assertNodeLayout(plans);
        PlanInvariants.assertNoDuplicateCustomers(plans);
        PlanInvariants.assertCustomerCountWithinCapacity(plans);
        PlanInvariants.assertLibraryCostMatchesIndependentCost(plans, calculation::calculateCost);
    }

    @Test
    void costMatrixInitializationStaysTractableForAHundredNodes() {
        Problems.Problem problem = Problems.random(99L, 100, 10, 20, 10_000);

        MatrixCostCalculation calculation = assertTimeoutPreemptively(Duration.ofMinutes(1),
                () -> problem.calculation());

        // Every node must rank every node, including itself: 101 entries per row for 100 customers + depot.
        assertThat(calculation.getClosestNodes("C0")).hasSize(problem.allNodes().size());
        assertThat(calculation.calculateCost(problem.origin(), problem.customer("C0"))).isNotNegative();
    }

    @Test
    void aParallelZoneSplitRunOverManyPlansTerminates() {
        Problems.Problem problem = Problems.random(7L, 40, 8, 20, 4_000);
        MatrixCostCalculation calculation = problem.calculation();
        TestAssignment assignment = problem.assignment();
        List<TestPlan> plans = new ArrayList<>(new TestClarkeWright(calculation, assignment,
                new TestTwoOpt(calculation, new AlwaysFeasibleChecker()),
                problem.destination(), OptimizationType.NO_RESTRICTION)
                .plan(problem.origin(), problem.customers()));
        List<TestPlan> before = PlanInvariants.deepCopy(plans);

        // Smoke test for the zone-split entry point at scale. With NO_RESTRICTION every plan neighbours every other,
        // so all plans form a single zone group: this checks termination and conservation of customers, not
        // concurrent execution (ZoneSplitAlgorithmTest covers the grouping).
        List<TestPlan> result = assertTimeoutPreemptively(Duration.ofMinutes(2), () ->
                new TestExchange(calculation, assignment, new RecostingLocalSearch(calculation),
                        new TestInsertion(calculation, new AlwaysFeasibleChecker(), false),
                        ExchangeRelocateType.SIMPLE, 5, 2, true).apply(plans, 4));

        assertThat(PlanInvariants.customersOf(result))
                .containsExactlyInAnyOrderElementsOf(PlanInvariants.customersOf(before));
        PlanInvariants.assertNoDuplicateCustomers(result);
        PlanInvariants.assertTrackablesAreDistinct(result);
    }

}
