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

package com.github.liliumsoftware.optimization.clarkewright;

import com.github.liliumsoftware.optimization.OptimizationType;
import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.NoOpLocalSearch;
import com.github.liliumsoftware.optimization.support.PlanInvariants;
import com.github.liliumsoftware.optimization.support.Problems;
import com.github.liliumsoftware.optimization.support.RecostingLocalSearch;
import com.github.liliumsoftware.optimization.support.TestAssignment;
import com.github.liliumsoftware.optimization.support.TestClarkeWright;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the Clarke-Wright contracts that the end-to-end tests in {@link ClarkeWrightAlgorithmTest} only exercise
 * indirectly: which λ/β outcome the sweep keeps (best plan set, first grid combination on a tie), identity-based plan
 * handling in the merge core, how previous plans interact with construction, and the merge of non-positive savings.
 *
 * <p>The sweep instance is closed through {@code Z(0,0)} with customers {@code A(-20,-40)}, {@code B(30,-30)},
 * {@code C(10,-40)}, {@code E(-20,-20)} and two capacity-2 vehicles. Distances to the depot are A 60, B 60, C 50,
 * E 40 and between customers AB 60, AC 30, AE 20, BC 30, BE 60, CE 50, so the closed saving is
 * {@code d(i) + d(j) − λ·c(i,j)}:
 * <ul>
 *   <li>λ = 0.1 merges AB (114) first, so C and E pair up: {@code {A,B} 180 + {C,E} 140 = 320};</li>
 *   <li>λ = 1 merges AC (80) first and BE is left: {@code {A,C} 140 + {B,E} 160 = 300};</li>
 *   <li>λ = 2 merges AE (60) first, then BC (50): {@code {A,E} 120 + {B,C} 140 = 260}, the best.</li>
 * </ul>
 */
class ClarkeWrightContractTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode returnDepot = TestNode.destination("Z", 0, 0);
    private final TestNode a = TestNode.customer("A", -20, -40, 1);
    private final TestNode b = TestNode.customer("B", 30, -30, 1);
    private final TestNode c = TestNode.customer("C", 10, -40, 1);
    private final TestNode e = TestNode.customer("E", -20, -20, 1);

    private static MatrixCostCalculation calculation(List<TestNode> nodes) {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(nodes);
        return calculation;
    }

    /**
     * Each plan rendered as its customer codes in route order, e.g. {@code "A-E"}.
     */
    private static List<String> routes(List<TestPlan> plans) {
        return plans.stream().map(p -> p.getCustomers().stream().map(TestNode::getCode)
                .collect(Collectors.joining("-"))).toList();
    }

    private List<TestNode> sweepCustomers() {
        return List.of(a, b, c, e);
    }

    private MatrixCostCalculation sweepCalculation() {
        return calculation(List.of(origin, a, b, c, e, returnDepot));
    }

    private SweepProbe sweep(MatrixCostCalculation calculation, List<Double> lambdas, List<Double> betas) {
        return new SweepProbe(calculation, new TestAssignment(List.of(new TestTrackable("T", 2, 2, false))),
                new NoOpLocalSearch(), returnDepot, OptimizationType.NO_RESTRICTION, lambdas, betas);
    }

    // ---------------------------------------------------------------- λ/β sweep

    @Test
    void eachLambdaOfTheInstanceGivesADifferentPlanSet() {
        // The premise of the sweep tests below, computed by hand in the class comment.
        MatrixCostCalculation calculation = sweepCalculation();

        assertThat(routes(sweep(calculation, List.of(0.1), List.of()).plan(origin, sweepCustomers())))
                .containsExactlyInAnyOrder("A-B", "C-E");
        assertThat(routes(sweep(calculation, List.of(1.0), List.of()).plan(origin, sweepCustomers())))
                .containsExactlyInAnyOrder("A-C", "B-E");
        assertThat(routes(sweep(calculation, List.of(2.0), List.of()).plan(origin, sweepCustomers())))
                .containsExactlyInAnyOrder("A-E", "B-C");
    }

    @Test
    void theLambdaSweepReturnsTheBestSingleLambdaRunWhateverTheOrder() {
        MatrixCostCalculation calculation = sweepCalculation();
        List<TestPlan> best = sweep(calculation, List.of(2.0), List.of()).plan(origin, sweepCustomers());

        for (List<Double> lambdas : List.of(List.of(0.1, 1.0, 2.0), List.of(2.0, 1.0, 0.1), List.of(1.0, 2.0, 0.1))) {
            List<TestPlan> swept = sweep(calculation, lambdas, List.of()).plan(origin, sweepCustomers());

            assertThat(swept).as("lambdas %s", lambdas).isEqualTo(best);
            assertThat(PlanInvariants.referenceTotalCost(swept)).as("lambdas %s", lambdas).isEqualTo(260);
            PlanInvariants.assertValidSolution(swept, origin, returnDepot, sweepCustomers());
        }
    }

    @Test
    void theWinningOutcomeIsTaggedWithTheLambdaThatProducedIt() {
        SweepProbe probe = sweep(sweepCalculation(), List.of(0.1, 1.0, 2.0), List.of());

        PlanList<TestPlan> best = probe.best(origin, sweepCustomers());

        assertThat(best.lambdaBeta()).isEqualTo(new LambdaBeta(2.0));
        assertThat(best.lambdaBeta().beta()).isZero();
        assertThat(routes(best.plans())).containsExactlyInAnyOrder("A-E", "B-C");
    }

    @Test
    void onATieTheFirstGridCombinationWins() {
        // A consumer comparator that only counts served customers ties all three outcomes (each serves everyone), so
        // the first λ of the configuration must win, whichever it is.
        MatrixCostCalculation calculation = sweepCalculation();
        for (double first : new double[] {0.1, 1.0, 2.0}) {
            List<Double> lambdas = new ArrayList<>(List.of(0.1, 1.0, 2.0));
            lambdas.remove(first);
            lambdas.addFirst(first);
            SweepProbe tied = sweep(calculation, lambdas, List.of()).tyingEveryOutcome();

            List<TestPlan> plans = tied.plan(origin, sweepCustomers());

            assertThat(plans).as("lambdas %s", lambdas)
                    .isEqualTo(sweep(calculation, List.of(first), List.of()).plan(origin, sweepCustomers()));
            assertThat(tied.best(origin, sweepCustomers()).lambdaBeta()).isEqualTo(new LambdaBeta(first));
        }
    }

    @Test
    void theGridIsEvaluatedLambdaMajorInConfigurationOrderWithDuplicatesOnce() {
        SweepProbe probe = sweep(sweepCalculation(), List.of(2.0, 1.0), List.of(0.5, 0.25, 0.5));

        List<PlanList<TestPlan>> outcomes = probe.outcomes(origin, sweepCustomers());

        assertThat(outcomes).extracting(PlanList::lambdaBeta).containsExactly(new LambdaBeta(2.0, 0.5),
                new LambdaBeta(2.0, 0.25), new LambdaBeta(1.0, 0.5), new LambdaBeta(1.0, 0.25));
        assertThat(probe.tyingEveryOutcome().best(origin, sweepCustomers()).lambdaBeta())
                .isEqualTo(new LambdaBeta(2.0, 0.5));
    }

    @Test
    void choosingFromNoOutcomeAtAllIsAnError() {
        SweepProbe probe = sweep(sweepCalculation(), List.of(1.0), List.of());

        assertThatThrownBy(() -> probe.getBestPlans(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("At least one list of plans");
    }

    @Test
    void aNonFiniteLambdaIsRejected() {
        MatrixCostCalculation calculation = sweepCalculation();

        assertThatThrownBy(() -> sweep(calculation, List.of(1.0, Double.POSITIVE_INFINITY), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Lambda must be a finite number");
    }

    @Test
    void theThreeArgumentConstructorPlansZoneByZone() {
        // The shortest constructor defaults to LOCATION_ZONE: A1/A2 (Z1) and B1/B2 (Z2) never share a saving, so the
        // two zones end up on separate routes although one vehicle could take all four customers.
        TestNode a1 = TestNode.customer("A1", "Z1", 0, 10, 1);
        TestNode a2 = TestNode.customer("A2", "Z1", 0, 20, 1);
        TestNode b1 = TestNode.customer("B1", "Z2", 10, 0, 1);
        TestNode b2 = TestNode.customer("B2", "Z2", 20, 0, 1);
        MatrixCostCalculation calculation = calculation(List.of(origin, a1, a2, b1, b2));
        SweepProbe zoned = new SweepProbe(calculation, new TestAssignment(List.of(new TestTrackable("T", 9, 2, false))),
                new NoOpLocalSearch(), null);

        assertThat(zoned.type()).isEqualTo(OptimizationType.LOCATION_ZONE);
        assertThat(routes(zoned.plan(origin, List.of(a1, a2, b1, b2)))).containsExactlyInAnyOrder("A1-A2", "B1-B2");
    }

    // ---------------------------------------------------------------- merge core

    @Test
    void nonPositiveSavingsAreStillMergedWhileTheAssignmentSucceeds() {
        // O(0,0), i(10,0), j(-10,0) on open routes: s(i,j) = c(O,j) - c(i,j) = 10 - 20 = -10, and the same for s(j,i).
        // Merging is still accepted because fewer routes are preferred: one route of 30 rather than two of 10 + 10.
        TestNode i = TestNode.customer("i", 10, 0, 1);
        TestNode j = TestNode.customer("j", -10, 0, 1);
        MatrixCostCalculation calculation = calculation(List.of(origin, i, j));
        TestClarkeWright cw = new TestClarkeWright(calculation,
                new TestAssignment(List.of(new TestTrackable("T", 2, 2, false))), new NoOpLocalSearch(), null,
                OptimizationType.NO_RESTRICTION);
        assertThat(cw.savingsForPlans(origin, List.of(i, j), cw.initialPlans(origin, List.of(i, j)), 1.0))
                .extracting(Saving::value).containsExactly(-10.0, -10.0);

        List<TestPlan> plans = cw.plan(origin, List.of(i, j));

        assertThat(plans).hasSize(1);
        assertThat(routes(plans)).containsAnyOf("i-j", "j-i");
        assertThat(PlanInvariants.referenceTotalCost(plans)).isEqualTo(30);
        PlanInvariants.assertValidSolution(plans, origin, null, List.of(i, j));
    }

    @ParameterizedTest
    @EnumSource(TestPlan.Equality.class)
    void theMergesDoNotDependOnHowPlansCompare(TestPlan.Equality equality) {
        // The merge core finds plans by their first/last customer and replaces them by identity, so a consumer whose
        // plans compare by code (or not at all) gets exactly the routes a value-equality consumer gets.
        Problems.Problem problem = Problems.random(7, 14, 4, 4, 200);
        MatrixCostCalculation calculation = problem.calculation();
        List<TestPlan> expected = new TestClarkeWright(calculation, problem.assignment(),
                new RecostingLocalSearch(calculation), null).plan(problem.origin(), problem.customers());

        List<TestPlan> plans = new TestClarkeWright(calculation, problem.assignment(),
                new RecostingLocalSearch(calculation), null).withPlanEquality(equality)
                .plan(problem.origin(), problem.customers());

        assertThat(plans).extracting(TestPlan::getNodes)
                .containsExactlyElementsOf(expected.stream().map(TestPlan::getNodes).toList());
        assertThat(plans).extracting(TestPlan::getTrackable)
                .containsExactlyElementsOf(expected.stream().map(TestPlan::getTrackable).toList());
        PlanInvariants.assertValidSolution(plans, problem.origin(), null, problem.customers());
        assertThat(plans).as("four vehicles of four for fourteen customers").hasSize(4);
    }

    @Test
    void aPlanIsNeverMergedWithItself() {
        // [A, B] both ends with the saving's origin B and begins with its destination A; merging it with itself
        // would have to remove the same plan twice. The saving is skipped and the plan stays as it is.
        Problems.Problem problem = Problems.collinearClosed();
        MatrixCostCalculation calculation = problem.calculation();
        TestClarkeWright cw = new TestClarkeWright(calculation,
                new TestAssignment(List.of(new TestTrackable("T", 9, 2, false))), new NoOpLocalSearch(),
                problem.destination(), OptimizationType.NO_RESTRICTION);
        TestPlan route = problem.plan("route", problem.customer("A"), problem.customer("B"));
        TestPlan single = problem.plan("single", problem.customer("C"));
        List<Saving> savings = new ArrayList<>(List.of(
                new Saving(problem.customer("B"), problem.customer("A"), 100),
                new Saving(problem.customer("C"), problem.customer("C"), 90)));

        List<TestPlan> merged = cw.merge(new ArrayList<>(List.of(route, single)), savings);

        assertThat(routes(merged)).containsExactlyInAnyOrder("A-B", "C");
        PlanInvariants.assertNoDuplicateCustomers(merged);
    }

    @Test
    void aPlanWithoutCustomersIsIgnoredByTheMergeWalk() {
        // The route-end index skips a plan without customers; it neither breaks the walk nor joins a merge.
        Problems.Problem problem = Problems.collinearClosed();
        MatrixCostCalculation calculation = problem.calculation();
        TestClarkeWright cw = new TestClarkeWright(calculation,
                new TestAssignment(List.of(new TestTrackable("T", 9, 2, false))), new NoOpLocalSearch(),
                problem.destination(), OptimizationType.NO_RESTRICTION);
        TestPlan empty = new TestPlan("empty", List.of(problem.origin(), problem.destination()));
        List<TestPlan> plans = new ArrayList<>(List.of(empty));
        plans.addAll(cw.initialPlans(problem.origin(), List.of(problem.customer("A"), problem.customer("B"))));

        List<TestPlan> merged = cw.merge(plans, new ArrayList<>(List.of(
                new Saving(problem.customer("A"), problem.customer("B"), 20))));

        assertThat(routes(merged)).containsExactlyInAnyOrder("A-B", "");
        PlanInvariants.assertTrackablesAreDistinct(merged);
    }

    @Test
    void routeStartsAndEndsSkipPlansWithoutCustomers() {
        // getRouteStarts/getRouteEnds read the first and last customer of every plan; an empty plan must not throw.
        // B's route ends at the destination Z(0,0) and A's starts at O: s(B,A) = c(B,Z) + c(O,A) - c(B,A) = 20 + 10
        // - 10 = 20; a customer no plan starts or ends is assumed to start and return at the origin.
        TestNode near = TestNode.customer("A", 0, 10, 1);
        TestNode far = TestNode.customer("B", 0, 20, 1);
        MatrixCostCalculation calculation = calculation(List.of(origin, near, far, returnDepot));
        TestClarkeWright cw = new TestClarkeWright(calculation,
                new TestAssignment(List.of(new TestTrackable("T", 9))), new NoOpLocalSearch(), returnDepot,
                OptimizationType.NO_RESTRICTION);
        List<TestPlan> plans = List.of(new TestPlan("empty", List.of(origin, returnDepot)),
                new TestPlan("pa", List.of(origin, near, returnDepot)),
                new TestPlan("pb", List.of(origin, far, returnDepot)));

        List<Saving> savings = cw.savingsForPlans(origin, List.of(near, far), plans, 1.0);

        assertThat(savings).extracting(s -> s.origin().getCode() + s.destination().getCode() + "=" + s.value())
                .containsExactly("AB=20.0", "BA=20.0");
        assertThat(cw.routeEnds(origin, plans).apply(far)).isEqualTo(returnDepot);
    }

    @Test
    void anAssignedPlanIsClosedExactlyWhenItsOwnResourceReturns() {
        // Before assignment the fleet decides; once a resource is assigned only that resource does.
        MatrixCostCalculation calculation = sweepCalculation();
        TestPlan plan = new TestPlan("p", List.of(origin, a));
        TestClarkeWright returningFleet = new TestClarkeWright(calculation,
                new TestAssignment(List.of(new TestTrackable("R", 5, 1, true))), new NoOpLocalSearch(), null,
                OptimizationType.NO_RESTRICTION);
        final TestClarkeWright openFleet = new TestClarkeWright(calculation,
                new TestAssignment(List.of(new TestTrackable("S", 5, 1, false))), new NoOpLocalSearch(), null,
                OptimizationType.NO_RESTRICTION);

        plan.setTrackable(new TestTrackable("S", 5, 1, false));
        assertThat(returningFleet.closedRoute(plan)).isFalse();
        plan.setTrackable(new TestTrackable("R", 5, 1, true));
        assertThat(openFleet.closedRoute(plan)).isTrue();
    }

    // ---------------------------------------------------------------- previous plans

    @Test
    void aNewCustomerSharingACodeWithAPreviousPlansDepotIsStillPlanned() {
        // Only the customers of previous plans count as planned. The previous plan below starts at depot "W" and ends
        // at depot "Y"; the new customers coded "W" and "Y" used to be silently removed as "already planned".
        TestNode customerA = TestNode.customer("A", 0, 10, 1);
        TestNode customerB = TestNode.customer("B", 0, 20, 1);
        TestNode customerW = TestNode.customer("W", 0, 30, 1);
        TestNode customerY = TestNode.customer("Y", 0, 40, 1);
        MatrixCostCalculation calculation = calculation(List.of(origin, customerA, customerB, customerW, customerY));
        TestClarkeWright cw = new TestClarkeWright(calculation,
                new TestAssignment(List.of(new TestTrackable("T", 9, 2, false))), new NoOpLocalSearch(), null,
                OptimizationType.NO_RESTRICTION);
        TestPlan previous = new TestPlan("previous", List.of(TestNode.origin("W", 0, 0), customerA,
                TestNode.destination("Y", 0, 0)));

        List<TestPlan> plans = cw.plan(origin, List.of(customerB, customerW, customerY), previous);

        PlanInvariants.assertAllCustomersVisitedExactlyOnce(plans, List.of(customerA, customerB, customerW, customerY));
        assertThat(plans).filteredOn(p -> p.getCode().equals("previous")).singleElement()
                .extracting(TestPlan::getNodes).isEqualTo(previous.getNodes());
        assertThat(routes(plans)).containsExactlyInAnyOrder("A", "B-W-Y");
        PlanInvariants.assertTrackablesAreDistinct(plans);
    }

    @Test
    void aPreviousPlanHoldingAResourceThatCannotBeReassignedNoLongerBlocksMerging() {
        // The previous plan serves three customers with a unit "OLD" that is not part of the fleet, while every fleet
        // unit takes at most two. It can never be re-assigned, so it is fixed: it takes no part in merging and keeps
        // OLD. Before, every merge re-assigned it too and was refused, leaving A and B on two single-stop routes.
        TestNode p1 = TestNode.customer("P1", 0, -10, 1);
        TestNode p2 = TestNode.customer("P2", 0, -20, 1);
        TestNode p3 = TestNode.customer("P3", 0, -30, 1);
        TestNode customerA = TestNode.customer("A", 0, 10, 1);
        TestNode customerB = TestNode.customer("B", 0, 20, 1);
        MatrixCostCalculation calculation = calculation(List.of(origin, p1, p2, p3, customerA, customerB));
        List<TestTrackable> fleet = List.of(new TestTrackable("T", 2, 2, false));
        TestPlan previous = new TestPlan("previous", List.of(origin, p1, p2, p3));
        previous.setTrackable(new TestTrackable("OLD", 3));

        List<TestPlan> alone = new TestClarkeWright(calculation, new TestAssignment(fleet), new NoOpLocalSearch(),
                null, OptimizationType.NO_RESTRICTION).plan(origin, List.of(customerA, customerB));
        List<TestPlan> withPrevious = new TestClarkeWright(calculation, new TestAssignment(fleet),
                new NoOpLocalSearch(), null, OptimizationType.NO_RESTRICTION)
                .plan(origin, List.of(customerA, customerB), previous);

        assertThat(routes(alone)).as("without the previous plan A and B share a route").containsExactly("A-B");
        assertThat(routes(withPrevious)).containsExactly("A-B", "P1-P2-P3");
        assertThat(withPrevious).filteredOn(p -> p.getCode().equals("previous")).singleElement()
                .extracting(TestPlan::getTrackable).isEqualTo(new TestTrackable("OLD", 3));
        PlanInvariants.assertTrackablesAreDistinct(withPrevious);
    }

    @Test
    void aDroppedPreviousPlanIsRestoredEvenWhenANewPlanCarriesItsCode() {
        // A previous plan is recognised in the result by its code AND its customers. Here the previous plan (code
        // "cw-B", customer C, cost 60) loses the only vehicle to the cheaper new seed "cw-B" (customer B, cost 40) in
        // the final assignment pass; a code-only check would mistake the seed for it and lose C.
        Problems.Problem problem = Problems.collinearClosed();
        MatrixCostCalculation calculation = problem.calculation();
        TestClarkeWright cw = new TestClarkeWright(calculation,
                new TestAssignment(List.of(new TestTrackable("T1", 9))), new NoOpLocalSearch(),
                problem.destination(), OptimizationType.NO_RESTRICTION);
        TestPlan previous = problem.plan("cw-B", problem.customer("C"));

        List<TestPlan> plans = cw.plan(problem.origin(), List.of(problem.customer("B")), previous);

        assertThat(plans).extracting(TestPlan::getCode).containsExactly("cw-B", "cw-B");
        assertThat(routes(plans)).containsExactly("B", "C");
        assertThat(plans.get(0).getTrackable()).isEqualTo(new TestTrackable("T1", 9));
        assertThat(plans.get(1).getTrackable()).as("the restored previous plan keeps having none").isNull();
    }

    @Test
    void aPreviousPlanWithoutCustomersWhoseUnitWasTakenGetsAFreeUnitOrNone() {
        // The empty previous plan holds T#0, but the merged route of A and B is assigned first and takes T#0. The
        // empty plan then gets a unit no plan uses (T#1), or none when the fleet has no other unit.
        Problems.Problem problem = Problems.collinearClosed();
        MatrixCostCalculation calculation = problem.calculation();
        for (int units : new int[] {2, 1}) {
            TestTrackable held = new TestTrackable("T", 9, units, false);
            held.setIndex(0);
            TestPlan empty = new TestPlan("empty", List.of(problem.origin(), problem.destination()));
            empty.setTrackable(held);
            TestClarkeWright cw = new TestClarkeWright(calculation,
                    new TestAssignment(List.of(new TestTrackable("T", 9, units, false))), new NoOpLocalSearch(),
                    problem.destination(), OptimizationType.NO_RESTRICTION);

            List<TestPlan> plans = cw.plan(problem.origin(), List.of(problem.customer("A"), problem.customer("B")),
                    empty);

            assertThat(routes(plans)).as("units=%s", units).containsExactly("A-B", "");
            assertThat(plans.get(0).getTrackable().getIndex()).as("units=%s", units).isZero();
            if (units == 2) {
                assertThat(plans.get(1).getTrackable().getIndex()).isEqualTo(1);
            } else {
                assertThat(plans.get(1).getTrackable()).isNull();
            }
        }
    }

    @Test
    void locationZoneConstructionKeepsPreviousPlansAndPlansEachZoneOnItsOwn() {
        // The previous plan (Z1) keeps its customer P and a unit; the new customers merge within their zones only.
        // Open routes: s(A1,A2) = c(O,A2) - c(A1,A2) = 20 - 10 = 10 beats s(A2,A1) = 0, so the route is A1-A2; likewise
        // B1-B2 (110 against 90).
        TestNode p = TestNode.customer("P", "Z1", 0, 30, 1);
        TestNode a1 = TestNode.customer("A1", "Z1", 0, 10, 1);
        TestNode a2 = TestNode.customer("A2", "Z1", 0, 20, 1);
        TestNode b1 = TestNode.customer("B1", "Z2", 100, 10, 1);
        TestNode b2 = TestNode.customer("B2", "Z2", 100, 20, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation(OptimizationType.LOCATION_ZONE,
                Map.of("Z1", List.of("Z1"), "Z2", List.of("Z2")));
        calculation.initializeCostMatrix(List.of(origin, p, a1, a2, b1, b2));
        TestClarkeWright cw = new TestClarkeWright(calculation,
                new TestAssignment(List.of(new TestTrackable("T", 2, 3, false))), new RecostingLocalSearch(calculation),
                null, OptimizationType.LOCATION_ZONE);
        TestTrackable unit = new TestTrackable("T", 2, 3, false);
        unit.setIndex(2);
        TestPlan previous = new TestPlan("previous", List.of(origin, p), "Z1");
        previous.setTrackable(unit);

        List<TestPlan> plans = cw.plan(origin, List.of(a1, a2, b1, b2), previous);

        assertThat(routes(plans)).containsExactlyInAnyOrder("P", "A1-A2", "B1-B2");
        assertThat(plans).filteredOn(plan -> plan.getCode().equals("previous")).singleElement()
                .extracting(TestPlan::getNodes).isEqualTo(previous.getNodes());
        PlanInvariants.assertValidSolution(plans, origin, null, List.of(p, a1, a2, b1, b2));
        PlanInvariants.assertEveryPlanHasTrackable(plans);
        assertThat(PlanInvariants.referenceTotalCost(plans)).isEqualTo(30 + 20 + 120);
    }

    /**
     * A Clarke-Wright that exposes the sweep hooks and can be told to treat every outcome as equally good.
     */
    private static final class SweepProbe extends AbstractClarkeWrightAlgorithm<TestPlan, TestNode> {

        private final TestNode destination;
        private boolean tie;

        SweepProbe(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
                TrackableAssignment<TestPlan, ?> trackableAssignment, LocalSearchAlgorithm<TestPlan> localSearch,
                TestNode destination) {
            super(costCalculation, trackableAssignment, localSearch);
            this.destination = destination;
        }

        SweepProbe(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
                TrackableAssignment<TestPlan, ?> trackableAssignment, LocalSearchAlgorithm<TestPlan> localSearch,
                TestNode destination, OptimizationType optimizationType, List<Double> lambdas, List<Double> betas) {
            super(costCalculation, trackableAssignment, localSearch, optimizationType, lambdas, betas);
            this.destination = destination;
        }

        SweepProbe tyingEveryOutcome() {
            this.tie = true;
            return this;
        }

        OptimizationType type() {
            return optimizationType;
        }

        List<PlanList<TestPlan>> outcomes(TestNode origin, List<TestNode> customers) {
            return getPlans(origin, customers, getInitialPlans(origin, customers));
        }

        PlanList<TestPlan> best(TestNode origin, List<TestNode> customers) {
            return getBestPlans(outcomes(origin, customers));
        }

        @Override
        protected Comparator<List<TestPlan>> plansComparator() {
            return tie ? (left, right) -> 0 : super.plansComparator();
        }

        @Override
        protected Comparator<TestPlan> planComparator() {
            return Comparator.comparingLong(TestPlan::getCost).thenComparing(TestPlan::getCode);
        }

        @Override
        protected List<TestPlan> getInitialPlans(TestNode node, List<TestNode> customers) {
            List<TestPlan> plans = new ArrayList<>();
            for (TestNode customer : customers) {
                List<TestNode> nodes = new ArrayList<>(List.of(node, customer));
                if (destination != null) {
                    nodes.add(destination);
                }
                plans.add(new TestPlan("cw-" + customer.getCode(), nodes, customer.getZone()));
            }
            return plans;
        }

        @Override
        protected TestPlan getNewPlan(TestPlan plan, List<TestNode> nodes) {
            return new TestPlan(plan.getCode(), nodes, plan.getZone());
        }

    }

}
