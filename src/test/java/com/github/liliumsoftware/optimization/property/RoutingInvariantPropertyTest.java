package com.github.liliumsoftware.optimization.property;

import com.github.liliumsoftware.optimization.OptimizationType;
import com.github.liliumsoftware.optimization.optimization.ExchangeRelocateType;
import com.github.liliumsoftware.optimization.support.AlwaysFeasibleChecker;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.PlanInvariants;
import com.github.liliumsoftware.optimization.support.Problems;
import com.github.liliumsoftware.optimization.support.RecostingLocalSearch;
import com.github.liliumsoftware.optimization.support.TestAssignment;
import com.github.liliumsoftware.optimization.support.TestClarkeWright;
import com.github.liliumsoftware.optimization.support.TestDestroyRepair;
import com.github.liliumsoftware.optimization.support.TestExchange;
import com.github.liliumsoftware.optimization.support.TestInsertion;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestRelocate;
import com.github.liliumsoftware.optimization.support.TestTwoOpt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based sweep: instead of asserting particular routes, this generates many valid random instances and
 * checks the invariants that must hold for <em>any</em> heuristic on <em>any</em> input.
 *
 * <p>Every instance is derived from an explicit seed, so a failure is exactly reproducible by re-running the
 * reported seed — no hidden randomness, no flakiness. Instances are kept small (≤ 12 customers) to keep the
 * suite fast; the scalability behaviour is covered separately by the smoke test.
 */
class RoutingInvariantPropertyTest {

    private TestInsertion insertion(MatrixCostCalculation calculation) {
        return new TestInsertion(calculation, new AlwaysFeasibleChecker(), false);
    }

    // ---------------------------------------------------------------- construction

    @ParameterizedTest
    @CsvSource({
            "1, 6, 3, 3",
            "2, 8, 4, 3",
            "3, 10, 5, 3",
            "4, 12, 4, 4",
            "5, 5, 2, 4",
            "6, 9, 9, 1",
            "7, 12, 3, 6",
            "8, 7, 7, 2"
    })
    void clarkeWrightAlwaysProducesAStructurallyValidPlanSet(long seed, int customerCount, int vehicleCount,
            int capacity) {
        Problems.Problem problem = Problems.random(seed, customerCount, vehicleCount, capacity, 400);
        MatrixCostCalculation calculation = problem.calculation();
        TestAssignment assignment = problem.assignment();

        List<TestPlan> plans = new TestClarkeWright(calculation, assignment,
                new TestTwoOpt(calculation, new AlwaysFeasibleChecker()),
                problem.destination(), OptimizationType.NO_RESTRICTION)
                .plan(problem.origin(), problem.customers());

        PlanInvariants.assertNodeLayout(plans);
        PlanInvariants.assertStartsAndEndsAtDepot(plans, problem.origin(), problem.destination());
        PlanInvariants.assertNoDuplicateCustomers(plans);
        PlanInvariants.assertNoEmptyPlans(plans);
        PlanInvariants.assertEveryPlanHasTrackable(plans);
        PlanInvariants.assertTrackablesAreDistinct(plans);
        PlanInvariants.assertCustomerCountWithinCapacity(plans);
        PlanInvariants.assertLibraryCostMatchesIndependentCost(plans, calculation::calculateCost);
        assertThat(plans.size()).as("never more routes than vehicles (seed %d)", seed)
                .isLessThanOrEqualTo(vehicleCount);
        assertThat(PlanInvariants.customersOf(plans)).as("no customer invented (seed %d)", seed)
                .isSubsetOf(problem.customers());
        assertThat(PlanInvariants.customersOf(plans).size())
                .as("no more customers served than the fleet can carry (seed %d)", seed)
                .isLessThanOrEqualTo(vehicleCount * capacity);
    }

    @ParameterizedTest
    @ValueSource(longs = {11L, 22L, 33L, 44L})
    void constructionServesEveryCustomerWhenTheFleetIsAmple(long seed) {
        // Ample fleet: as many vehicles as customers, each able to take everyone — nothing may be dropped.
        Problems.Problem problem = Problems.random(seed, 8, 8, 8, 300);
        MatrixCostCalculation calculation = problem.calculation();

        List<TestPlan> plans = new TestClarkeWright(calculation, problem.assignment(),
                new TestTwoOpt(calculation, new AlwaysFeasibleChecker()),
                problem.destination(), OptimizationType.NO_RESTRICTION)
                .plan(problem.origin(), problem.customers());

        PlanInvariants.assertAllCustomersVisitedExactlyOnce(plans, problem.customers());
    }

    @ParameterizedTest
    @ValueSource(longs = {101L, 202L, 303L})
    void constructionIsReproducibleForTheSameSeed(long seed) {
        Problems.Problem problem = Problems.random(seed, 9, 4, 4, 250);

        assertThat(plan(problem)).as("same seed, same plan set").isEqualTo(plan(problem));
    }

    private List<TestPlan> plan(Problems.Problem problem) {
        MatrixCostCalculation calculation = problem.calculation();
        return new TestClarkeWright(calculation, problem.assignment(),
                new TestTwoOpt(calculation, new AlwaysFeasibleChecker()),
                problem.destination(), OptimizationType.NO_RESTRICTION)
                .plan(problem.origin(), problem.customers());
    }

    @Test
    void differentSeedsExploreDifferentInstancesRatherThanRepeatingOne() {
        // Guards the generator itself: if every seed produced the same instance the sweep above would be a
        // single test wearing eight hats.
        assertThat(Problems.random(1L, 6, 3, 3, 400).customers())
                .isNotEqualTo(Problems.random(2L, 6, 3, 3, 400).customers());
    }

    // ---------------------------------------------------------------- optimization

    @ParameterizedTest
    @CsvSource({
            "1, 8, 4, 4",
            "2, 10, 5, 4",
            "3, 12, 6, 3",
            "4, 7, 3, 5",
            "5, 11, 4, 5"
    })
    void everyOptimizerPreservesTheCustomerSetAndNeverWorsensCost(long seed, int customerCount, int vehicleCount,
            int capacity) {
        Problems.Problem problem = Problems.random(seed, customerCount, vehicleCount, capacity, 400);
        MatrixCostCalculation calculation = problem.calculation();
        TestAssignment assignment = problem.assignment();
        List<TestPlan> constructed = new ArrayList<>(new TestClarkeWright(calculation, assignment,
                new TestTwoOpt(calculation, new AlwaysFeasibleChecker()),
                problem.destination(), OptimizationType.NO_RESTRICTION)
                .plan(problem.origin(), problem.customers()));
        List<TestNode> served = PlanInvariants.customersOf(constructed);
        long before = PlanInvariants.referenceTotalCost(constructed);

        List<List<TestPlan>> results = List.of(
                new TestExchange(calculation, assignment, new RecostingLocalSearch(calculation),
                        insertion(calculation), ExchangeRelocateType.SIMPLE, 4, 2, true)
                        .apply(PlanInvariants.deepCopyMutable(constructed)),
                new TestRelocate(calculation, assignment, new RecostingLocalSearch(calculation),
                        insertion(calculation), ExchangeRelocateType.SIMPLE, 4, 2)
                        .apply(PlanInvariants.deepCopyMutable(constructed)),
                new TestDestroyRepair(calculation, assignment, new RecostingLocalSearch(calculation),
                        insertion(calculation), 2, 0)
                        .apply(PlanInvariants.deepCopyMutable(constructed)));

        for (List<TestPlan> result : results) {
            assertThat(PlanInvariants.customersOf(result))
                    .as("customer set preserved (seed %d)", seed)
                    .containsExactlyInAnyOrderElementsOf(served);
            assertThat(PlanInvariants.referenceTotalCost(result))
                    .as("cost never worsens (seed %d)", seed).isLessThanOrEqualTo(before);
            PlanInvariants.assertNodeLayout(result);
            PlanInvariants.assertNoDuplicateCustomers(result);
            PlanInvariants.assertNoEmptyPlans(result);
            PlanInvariants.assertTrackablesAreDistinct(result);
            PlanInvariants.assertCustomerCountWithinCapacity(result);
            assertThat(result.size()).as("route count never exceeds the fleet (seed %d)", seed)
                    .isLessThanOrEqualTo(vehicleCount);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {7L, 77L, 777L})
    void localSearchNeverWorsensAndReachesAFixedPoint(long seed) {
        Problems.Problem problem = Problems.random(seed, 7, 1, 7, 200);
        MatrixCostCalculation calculation = problem.calculation();
        TestPlan plan = problem.plan("p", problem.customers());
        long before = PlanInvariants.referenceCost(plan);
        TestTwoOpt twoOpt = new TestTwoOpt(calculation, new AlwaysFeasibleChecker());

        TestPlan once = twoOpt.apply(plan.copy());
        TestPlan twice = twoOpt.apply(once.copy());

        assertThat(PlanInvariants.referenceCost(once)).isLessThanOrEqualTo(before);
        assertThat(PlanInvariants.referenceCost(twice))
                .as("already a local optimum after one pass (seed %d)", seed)
                .isEqualTo(PlanInvariants.referenceCost(once));
        assertThat(once.getNodes()).containsExactlyInAnyOrderElementsOf(plan.getNodes());
    }
}
