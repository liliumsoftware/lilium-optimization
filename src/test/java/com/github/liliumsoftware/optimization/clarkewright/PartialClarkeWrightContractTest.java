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
import com.github.liliumsoftware.optimization.exception.CancelException;
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;
import com.github.liliumsoftware.optimization.support.CancelSpyLocalSearch;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.NoOpLocalSearch;
import com.github.liliumsoftware.optimization.support.PlanInvariants;
import com.github.liliumsoftware.optimization.support.TestAssignment;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins {@link AbstractPartialClarkeWrightAlgorithm#apply} with exact expectations: which spare unit a plan is handed to
 * when the free units plan nothing (cold customers only a refrigerated unit may serve), which units the re-planned
 * customers may use, the zone-restricted path of the shortest constructor, and how cancel/interrupt behave when they
 * arrive in the middle of the planning.
 *
 * <p>All routes are open paths from {@code O(0,0)} on the y axis ({@code A(0,10)}, {@code B(0,20)},
 * {@code C(0,30)}, {@code D(0,40)}) served by non-returning units, so the saving of chaining i into j is
 * {@code c(O,j) − c(i,j)}: s(C,D) = 40 − 10 = 30 beats s(D,C) = 20, and C-D is the merged route.
 */
class PartialClarkeWrightContractTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode a = TestNode.customer("A", 0, 10, 1);
    private final TestNode b = TestNode.customer("B", 0, 20, 1);
    private final TestNode c = TestNode.customer("C", 0, 30, 1);
    private final TestNode d = TestNode.customer("D", 0, 40, 1);

    private static List<String> routes(List<TestPlan> plans) {
        return plans.stream().map(p -> p.getCustomers().stream().map(TestNode::getCode)
                .collect(Collectors.joining("-"))).toList();
    }

    private List<TestNode> customers() {
        return List.of(a, b, c, d);
    }

    private MatrixCostCalculation calculation() {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, a, b, c, d));
        return calculation;
    }

    private PoolProbe probe(MatrixCostCalculation calculation, TrackableAssignment<TestPlan, TestTrackable> assignment,
            LocalSearchAlgorithm<TestPlan> localSearch) {
        return new PoolProbe(calculation, assignment, localSearch, OptimizationType.NO_RESTRICTION, List.of(1.0));
    }

    @Test
    void thePlanGoesToTheFirstSpareUnitThatCanTakeItAndTheRestUseTheOthers() {
        // C and D are cold and only T1 is refrigerated, so the free units T2 and T3 plan nothing and the hand-over
        // search runs. p1 (A, B) holds T1. T2 is spare but holds a single customer, so the hand-over skips it and
        // moves p1 to T3; C and D are then planned against the units p1 no longer uses (T1 and T2): the merged C-D
        // needs two places and refrigeration, so it takes T1.
        MatrixCostCalculation calculation = calculation();
        RuleAssignment assignment = RuleAssignment.coldChain(List.of(new TestTrackable("T1", 4),
                new TestTrackable("T2", 1), new TestTrackable("T3", 4)), Set.of("T1"), Set.of("C", "D"));
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", List.of(origin, a, b))));
        assertThat(assignment.assign(plans)).isTrue();
        assertThat(plans.getFirst().getTrackable().getCode()).isEqualTo("T1");

        List<TestPlan> result = probe(calculation, assignment, new NoOpLocalSearch()).apply(origin, customers(), plans);

        assertThat(result).isSameAs(plans);
        assertThat(routes(result)).containsExactly("A-B", "C-D");
        assertThat(result).extracting(p -> p.getTrackable().getCode()).containsExactly("T3", "T1");
        PlanInvariants.assertValidSolution(result, origin, null, customers());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void withoutAPlanHoldingAResourceTheInputIsReturnedUnchanged(int units) {
        // C and D are cold and no unit is refrigerated, so the free units plan nothing; the walk only hands over plans
        // that hold a unit, and with none C and D stay unplanned whatever the fleet.
        MatrixCostCalculation calculation = calculation();
        RuleAssignment assignment = RuleAssignment.coldChain(List.of(new TestTrackable("T", 4, units, false)),
                Set.of(), Set.of("C", "D"));
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", List.of(origin, a, b))));
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);

        List<TestPlan> result = probe(calculation, assignment, new NoOpLocalSearch()).apply(origin, customers(), plans);

        assertThat(result).isSameAs(plans).isEqualTo(snapshot);
    }

    @Test
    void theShortestConstructorReplansZoneByZoneAgainstTheRestrictedPool() {
        // Under LOCATION_ZONE the uncovered customers are planned zone by zone on the units p1 leaves free (T2, T3):
        // C1, C2 (Z1) merge into one route, E (Z2) stays alone, and p1 keeps T1.
        TestNode a1 = TestNode.customer("A", "Z1", 0, 10, 1);
        TestNode b1 = TestNode.customer("B", "Z1", 0, 20, 1);
        TestNode c1 = TestNode.customer("C1", "Z1", 0, 30, 1);
        TestNode c2 = TestNode.customer("C2", "Z1", 0, 40, 1);
        TestNode e = TestNode.customer("E", "Z2", 50, 0, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation(OptimizationType.LOCATION_ZONE,
                Map.of("Z1", List.of("Z1"), "Z2", List.of("Z2")));
        calculation.initializeCostMatrix(List.of(origin, a1, b1, c1, c2, e));
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 4), new TestTrackable("T2", 4),
                new TestTrackable("T3", 4)));
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", List.of(origin, a1, b1), "Z1")));
        assertThat(assignment.assign(plans)).isTrue();
        PoolProbe zoned = new PoolProbe(calculation, assignment, new NoOpLocalSearch());

        List<TestPlan> result = zoned.apply(origin, List.of(a1, b1, c1, c2, e), plans);

        assertThat(routes(result)).containsExactly("A-B", "C1-C2", "E");
        assertThat(result).extracting(p -> p.getTrackable().getCode()).containsExactly("T1", "T2", "T3");
        PlanInvariants.assertValidSolution(result, origin, null, List.of(a1, b1, c1, c2, e));
    }

    @Test
    void anInterruptDuringTheReplanningStillPlansEveryCustomer() {
        // The interrupt arrives while the uncovered customers are being planned on the free units. Planning is
        // construction and completes, so its plans are returned in full.
        MatrixCostCalculation calculation = calculation();
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 4), new TestTrackable("T2", 4),
                new TestTrackable("T3", 4)));
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", List.of(origin, a, b))));
        assertThat(assignment.assign(plans)).isTrue();
        PoolProbe[] holder = new PoolProbe[1];
        AtomicInteger localSearches = new AtomicInteger();
        LocalSearchAlgorithm<TestPlan> interrupting = new LocalSearchAlgorithm<>() {
            @Override
            public TestPlan apply(TestPlan plan) {
                localSearches.incrementAndGet();
                holder[0].interrupt();
                return plan;
            }
        };
        holder[0] = probe(calculation, assignment, interrupting);

        List<TestPlan> result = holder[0].apply(origin, customers(), plans);

        assertThat(localSearches).as("the interrupt was raised inside the re-planning").hasPositiveValue();
        assertThat(routes(result)).containsExactly("A-B", "C-D");
        PlanInvariants.assertValidSolution(result, origin, null, customers());
        PlanInvariants.assertEveryPlanHasTrackable(result);
    }

    @Test
    void cancelDuringThePlanningAbortsTheCall() {
        MatrixCostCalculation calculation = calculation();
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 4), new TestTrackable("T2", 4)));
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", List.of(origin, a, b))));
        assertThat(assignment.assign(plans)).isTrue();
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);
        PoolProbe[] holder = new PoolProbe[1];
        LocalSearchAlgorithm<TestPlan> cancelling = new LocalSearchAlgorithm<>() {
            @Override
            public TestPlan apply(TestPlan plan) {
                holder[0].cancel();
                return plan;
            }
        };
        holder[0] = probe(calculation, assignment, cancelling);

        assertThatThrownBy(() -> holder[0].apply(origin, customers(), plans)).isInstanceOf(CancelException.class);
        assertThat(plans).as("nothing was committed").isEqualTo(snapshot);
    }

    @Test
    void cancelAndInterruptReachTheNestedLocalSearch() {
        MatrixCostCalculation calculation = calculation();
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 4)));
        CancelSpyLocalSearch cancelled = new CancelSpyLocalSearch();
        CancelSpyLocalSearch interrupted = new CancelSpyLocalSearch();

        probe(calculation, assignment, cancelled).cancel();
        probe(calculation, assignment, interrupted).interrupt();

        assertThat(cancelled.isCancelled()).isTrue();
        assertThat(cancelled.isInterrupted()).isFalse();
        assertThat(interrupted.isInterrupted()).isTrue();
        assertThat(interrupted.isCancelled()).isFalse();
    }

    @Test
    void aStandalonePlanUsesTheWholeFleet() {
        // Outside apply(...) there is no restricted pool, so plan(...) falls back to the current pool, here every unit
        // of the fleet.
        MatrixCostCalculation calculation = calculation();
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T", 2, 2, false)));

        List<TestPlan> plans = probe(calculation, assignment, new NoOpLocalSearch()).plan(origin, customers());

        assertThat(routes(plans)).containsExactlyInAnyOrder("A-B", "C-D");
        assertThat(plans).extracting(p -> p.getTrackable().getIndex()).containsExactlyInAnyOrder(0, 1);
    }

    @Test
    void customersThatAreAlreadyServedAreRecognisedByCode() {
        PoolProbe probe = probe(calculation(), new TestAssignment(List.of(new TestTrackable("T", 4))),
                new NoOpLocalSearch());
        // the served copies are equal to nothing but themselves, as for a consumer without value equality
        List<TestPlan> plans = List.of(new TestPlan("p1",
                List.of(origin, a.withIdentityEquality(), c.withIdentityEquality())));

        assertThat(probe.getUnplannedNodes(plans, customers())).containsExactly(b, d);
    }

    /**
     * A partial Clarke-Wright built through the constructors {@code TestPartialClarkeWright} does not use.
     */
    private static final class PoolProbe
            extends AbstractPartialClarkeWrightAlgorithm<TestPlan, TestNode, TestTrackable> {

        PoolProbe(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
                TrackableAssignment<TestPlan, TestTrackable> trackableAssignment,
                LocalSearchAlgorithm<TestPlan> localSearch) {
            super(costCalculation, trackableAssignment, localSearch);
        }

        PoolProbe(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
                TrackableAssignment<TestPlan, TestTrackable> trackableAssignment,
                LocalSearchAlgorithm<TestPlan> localSearch, OptimizationType optimizationType, List<Double> lambdas) {
            super(costCalculation, trackableAssignment, localSearch, optimizationType, lambdas);
        }

        @Override
        protected Comparator<TestPlan> planComparator() {
            return Comparator.comparingLong(TestPlan::getCost).thenComparing(TestPlan::getCode);
        }

        @Override
        protected List<TestPlan> getInitialPlans(TestNode node, List<TestNode> customers) {
            List<TestPlan> plans = new ArrayList<>();
            for (TestNode customer : customers) {
                plans.add(new TestPlan("pcw-" + customer.getCode(), List.of(node, customer), customer.getZone()));
            }
            return plans;
        }

        @Override
        protected TestPlan getNewPlan(TestPlan plan, List<TestNode> nodes) {
            return new TestPlan(plan.getCode(), nodes, plan.getZone());
        }

    }

}
