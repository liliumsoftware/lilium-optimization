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

package com.github.liliumsoftware.optimization.balance;

import com.github.liliumsoftware.optimization.OptimizationType;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.support.AlwaysFeasibleChecker;
import com.github.liliumsoftware.optimization.support.DemandFeasibilityChecker;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.MaxCustomersFeasibilityChecker;
import com.github.liliumsoftware.optimization.support.PlanInvariants;
import com.github.liliumsoftware.optimization.support.TestAssignment;
import com.github.liliumsoftware.optimization.support.TestCapacityBalance;
import com.github.liliumsoftware.optimization.support.TestInsertion;
import com.github.liliumsoftware.optimization.support.TestInsertionCandidate;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestNodeBalance;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the balancing contracts the broader {@link BalanceAlgorithmTest} leaves open: shortfall arithmetic that would
 * overflow throws instead of wrapping, a capacity measure that depends on the assigned resource is judged after the
 * resources are re-assigned, a zone that is not listed as its own neighbour receives nothing under
 * {@link OptimizationType#LOCATION_ZONE}, transfers the insertion or the fleet cannot support are skipped, and the
 * package helpers locate plans by identity and customers by code.
 */
class BalanceContractTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);

    private static List<List<String>> routes(List<TestPlan> plans) {
        return plans.stream().map(plan -> {
            List<String> route = new ArrayList<>(List.of(plan.getCode()));
            plan.getNodes().forEach(node -> route.add(node.getCode()));
            return route;
        }).toList();
    }

    private static Map<TestNode, TestPlan> toCopies(Map<TestNode, TestPlan> candidates) {
        Map<TestNode, TestPlan> copies = new LinkedHashMap<>();
        candidates.forEach((node, donor) -> copies.put(node, donor.copy()));
        assertThat(copies).as("the unmodified mapping offers candidates").isNotEmpty();
        return copies;
    }

    private List<TestNode> customers(int count, String zone, int demand) {
        List<TestNode> customers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            customers.add(TestNode.customer("C" + i, zone, 10 * (i + 1), 0, demand));
        }
        return customers;
    }

    private MatrixCostCalculation calculation(List<TestNode> customers) {
        return calculation(customers, OptimizationType.NO_RESTRICTION, Map.of());
    }

    private MatrixCostCalculation calculation(List<TestNode> customers, OptimizationType type,
            Map<String, List<String>> neighbors) {
        List<TestNode> nodes = new ArrayList<>(List.of(origin));
        nodes.addAll(customers);
        MatrixCostCalculation calculation = new MatrixCostCalculation(type, neighbors);
        calculation.initializeCostMatrix(nodes);
        return calculation;
    }

    private TestPlan plan(String code, String zone, List<TestNode> planCustomers) {
        List<TestNode> nodes = new ArrayList<>(List.of(origin));
        nodes.addAll(planCustomers);
        return new TestPlan(code, nodes, zone);
    }

    // ---------------------------------------------------------------- overflow

    private TestInsertion insertion(MatrixCostCalculation calculation) {
        return new TestInsertion(calculation, new AlwaysFeasibleChecker(), false);
    }

    @ParameterizedTest
    @CsvSource({
            // floor near Long.MAX_VALUE with a negative capacity: MAX - (-1) does not fit a long
            "9223372036854775807, -1, 0",
            // capacity Long.MIN_VALUE: 0 - MIN does not fit a long
            "0, -9223372036854775808, 0",
            // each plan alone is short by Long.MAX_VALUE; their sum does not fit a long
            "9223372036854775807, 0, 0"})
    void aShortfallThatDoesNotFitALongThrowsInsteadOfWrapping(long minCapacity, long firstCapacity,
            long secondCapacity) {
        List<TestNode> customers = customers(3, "NoZone", 1);
        MatrixCostCalculation calculation = calculation(customers);
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 9), new TestTrackable("T2", 9)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p1", "NoZone", customers.subList(0, 2)),
                plan("p2", "NoZone", customers.subList(2, 3))));
        assertThat(assignment.assign(plans)).isTrue();
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);
        FixedCapacityBalance balance = new FixedCapacityBalance(calculation, assignment, insertion(calculation),
                minCapacity, plan -> plan.getCode().equals("p1") ? firstCapacity : secondCapacity);

        assertThatThrownBy(() -> balance.apply(plans)).isInstanceOf(ArithmeticException.class);
        assertThat(plans).isEqualTo(snapshot);
    }

    // ---------------------------------------------------------------- resource-dependent capacity

    @Test
    void theLargestRepresentableShortfallIsStillComputed() {
        // The control for the overflow cases: one plan short by exactly Long.MAX_VALUE, the other at the floor, sums to
        // Long.MAX_VALUE without overflow. Moving a customer cannot change these constant measures, so no transfer is
        // admissible and the input comes back.
        List<TestNode> customers = customers(3, "NoZone", 1);
        MatrixCostCalculation calculation = calculation(customers);
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 9), new TestTrackable("T2", 9)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p1", "NoZone", customers.subList(0, 2)),
                plan("p2", "NoZone", customers.subList(2, 3))));
        assertThat(assignment.assign(plans)).isTrue();
        List<TestPlan> originals = List.copyOf(plans);
        FixedCapacityBalance balance = new FixedCapacityBalance(calculation, assignment, insertion(calculation),
                Long.MAX_VALUE, plan -> plan.getCode().equals("p1") ? Long.MAX_VALUE : 0);

        assertThat(balance.apply(plans)).containsExactlyElementsOf(originals);
    }

    @Test
    void aResourceDependentCapacityIsJudgedWithTheResourcesReassigned() {
        // Utilisation (customers per seat, in percent) with a floor of 60%; a four-seat and a two-seat vehicle. p1 = 4
        // customers on the four-seat (100%), p2 = 1 on the two-seat (50%, short by 10). Moving one customer gives
        // 3 + 2: re-assigned smallest-fitting-first, p1 keeps the four-seat (75%) and p2 the two-seat (100%), so no
        // shortfall remains.
        List<TestNode> customers = customers(5, "NoZone", 1);
        MatrixCostCalculation calculation = calculation(customers);
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("big", 4),
                new TestTrackable("small", 2)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p1", "NoZone", customers.subList(0, 4)),
                plan("p2", "NoZone", customers.subList(4, 5))));
        assertThat(assignment.assign(plans)).isTrue();
        UtilisationBalance balance = new UtilisationBalance(calculation, assignment, insertion(calculation), 60);

        List<TestPlan> result = balance.apply(plans);

        assertThat(result).extracting(TestPlan::getCustomerCount).containsExactly(3, 2);
        assertThat(result).extracting(plan -> plan.getTrackable().getCode()).containsExactly("big", "small");
        assertThat(result).extracting(balance::getCapacity).containsExactly(75L, 100L);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, customers);
        PlanInvariants.assertTrackablesAreDistinct(result);
    }

    // ---------------------------------------------------------------- literal neighbour map

    @Test
    void aTransferIsRefusedWhenTheReassignedResourcesLeaveTheShortfallAsItWas() {
        // Same measure and fleet, p1 = 3 customers (75% of four seats), p2 = 1 (50% of two, short by 10). Moving one
        // customer gives 2 + 2, but smallest-fitting-first then puts p1 on the two-seat (100%) and p2 on the four-seat
        // (50%): still short by 10, so the transfer is not admissible and the input list comes back.
        List<TestNode> customers = customers(4, "NoZone", 1);
        MatrixCostCalculation calculation = calculation(customers);
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("big", 4),
                new TestTrackable("small", 2)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p1", "NoZone", customers.subList(0, 3)),
                plan("p2", "NoZone", customers.subList(3, 4))));
        assertThat(assignment.assign(plans)).isTrue();
        List<TestPlan> originals = List.copyOf(plans);
        UtilisationBalance balance = new UtilisationBalance(calculation, assignment, insertion(calculation), 60);

        assertThat(balance.doOperation(List.of(), plans)).isSameAs(plans).containsExactlyElementsOf(originals);
        assertThat(plans).extracting(plan -> plan.getTrackable().getCode()).containsExactly("big", "small");
    }

    // ---------------------------------------------------------------- inadmissible transfers

    @Test
    void aZoneNotListedAsItsOwnNeighbourReceivesNoTransfers() {
        // Under LOCATION_ZONE the neighbour map is literal. With Z1 -> [Z2] only, a Z1 plan may not take Z1 customers,
        // so neither balancer moves anything; listing Z1 as its own neighbour (the control) lets both balance.
        List<TestNode> customers = customers(6, "Z1", 2);
        for (boolean selfNeighbour : new boolean[] {false, true}) {
            Map<String, List<String>> neighbors = selfNeighbour
                    ? Map.of("Z1", List.of("Z1", "Z2"), "Z2", List.of("Z2", "Z1"))
                    : Map.of("Z1", List.of("Z2"), "Z2", List.of("Z1"));
            MatrixCostCalculation calculation = calculation(customers, OptimizationType.LOCATION_ZONE, neighbors);
            TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 9),
                    new TestTrackable("T2", 9)));
            List<TestPlan> plans = new ArrayList<>(List.of(plan("heavy", "Z1", customers.subList(0, 5)),
                    plan("light", "Z1", customers.subList(5, 6))));
            assertThat(assignment.assign(plans)).isTrue();
            List<List<String>> before = routes(plans);

            List<TestPlan> nodeResult = new TestNodeBalance(calculation, assignment, insertion(calculation), 2)
                    .apply(plans);
            List<TestPlan> capacityResult = new TestCapacityBalance(calculation, assignment, insertion(calculation), 6)
                    .apply(plans);

            if (selfNeighbour) {
                assertThat(nodeResult).extracting(TestPlan::getCustomerCount).containsExactlyInAnyOrder(3, 3);
                assertThat(capacityResult).allSatisfy(plan -> assertThat(DemandFeasibilityChecker.load(plan))
                        .isGreaterThanOrEqualTo(6));
            } else {
                assertThat(routes(nodeResult)).isEqualTo(before);
                assertThat(routes(capacityResult)).isEqualTo(before);
            }
            PlanInvariants.assertAllCustomersVisitedExactlyOnce(nodeResult, customers);
            PlanInvariants.assertAllCustomersVisitedExactlyOnce(capacityResult, customers);
        }
    }

    @Test
    void aReceiverTheInsertionCannotExtendGetsNothing() {
        // The insertion refuses any plan above one customer, so the light plan cannot take a customer at all.
        List<TestNode> customers = customers(5, "NoZone", 2);
        MatrixCostCalculation calculation = calculation(customers);
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 9), new TestTrackable("T2", 9)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("heavy", "NoZone", customers.subList(0, 4)),
                plan("light", "NoZone", customers.subList(4, 5))));
        assertThat(assignment.assign(plans)).isTrue();
        List<TestPlan> originals = List.copyOf(plans);
        TestInsertion refusing = new TestInsertion(calculation, new MaxCustomersFeasibilityChecker(1), false);

        assertThat(new TestNodeBalance(calculation, assignment, refusing, 2).apply(plans))
                .containsExactlyElementsOf(originals);
        assertThat(new TestCapacityBalance(calculation, assignment, refusing, 6).apply(plans))
                .containsExactlyElementsOf(originals);
    }

    @Test
    void aTransferTheFleetCannotResourceIsSkipped() {
        // A three-seat and a one-seat vehicle: 3 + 1 customers balance to 2 + 2, which no assignment can resource.
        List<TestNode> customers = customers(4, "NoZone", 2);
        MatrixCostCalculation calculation = calculation(customers);
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("big", 3),
                new TestTrackable("single", 1)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("heavy", "NoZone", customers.subList(0, 3)),
                plan("light", "NoZone", customers.subList(3, 4))));
        assertThat(assignment.assign(plans)).isTrue();
        List<TestPlan> originals = List.copyOf(plans);

        assertThat(new TestNodeBalance(calculation, assignment, insertion(calculation), 2).apply(plans))
                .containsExactlyElementsOf(originals);
        assertThat(new TestCapacityBalance(calculation, assignment, insertion(calculation), 4).apply(plans))
                .containsExactlyElementsOf(originals);
    }

    @Test
    void aThresholdOfZeroOnAnEvenSetTriesEveryReceiverAndStops() {
        // With threshold 0 every plan is a candidate receiver, but no plan has two customers more than another, so
        // no donor exists for any of them and the loop ends without a transfer.
        List<TestNode> customers = customers(4, "NoZone", 1);
        MatrixCostCalculation calculation = calculation(customers);
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 9), new TestTrackable("T2", 9)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p1", "NoZone", customers.subList(0, 2)),
                plan("p2", "NoZone", customers.subList(2, 4))));
        assertThat(assignment.assign(plans)).isTrue();
        List<TestPlan> originals = List.copyOf(plans);

        assertThat(new TestNodeBalance(calculation, assignment, insertion(calculation), 0).apply(plans))
                .containsExactlyElementsOf(originals);
    }

    @Test
    void stepsThatKeepTheSpreadAreKeptOnlyUntilALaterStepNarrowsIt() {
        // Customer counts 2, 2, 4, 4 (spread 2): the first transfer gives 3, 2, 3, 4 (spread still 2), the second
        // 3, 3, 3, 3 (spread 0), which is the result.
        List<TestNode> customers = customers(12, "NoZone", 1);
        MatrixCostCalculation calculation = calculation(customers);
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 9), new TestTrackable("T2", 9),
                new TestTrackable("T3", 9), new TestTrackable("T4", 9)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p1", "NoZone", customers.subList(0, 2)),
                plan("p2", "NoZone", customers.subList(2, 4)), plan("p3", "NoZone", customers.subList(4, 8)),
                plan("p4", "NoZone", customers.subList(8, 12))));
        assertThat(assignment.assign(plans)).isTrue();

        List<TestPlan> result = new TestNodeBalance(calculation, assignment, insertion(calculation), 1).apply(plans);

        assertThat(result).extracting(TestPlan::getCustomerCount).containsExactly(3, 3, 3, 3);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, customers);
        PlanInvariants.assertTrackablesAreDistinct(result);
    }

    @Test
    void aCandidateMappedToAPlanThatIsNotADonorIsIgnored() {
        // getOtherPlanNodes must map every candidate to one of the donor copies it is given; an override that maps
        // to an equal copy instead names no donor, and the balancers skip such candidates rather than removing the
        // customer from the wrong plan.
        List<TestNode> customers = customers(5, "NoZone", 2);
        MatrixCostCalculation calculation = calculation(customers);
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 9), new TestTrackable("T2", 9)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("heavy", "NoZone", customers.subList(0, 4)),
                plan("light", "NoZone", customers.subList(4, 5))));
        assertThat(assignment.assign(plans)).isTrue();
        List<TestPlan> originals = List.copyOf(plans);
        TestInsertion insertion = insertion(calculation);
        AbstractNodeBalanceAlgorithm<TestPlan, TestNode, TestInsertionCandidate> nodeBalance =
                new AbstractNodeBalanceAlgorithm<>(calculation, assignment, insertion, 2) {
                    @Override
                    protected Map<TestNode, TestPlan> getOtherPlanNodes(List<TestPlan> donors, TestPlan minPlan) {
                        return toCopies(super.getOtherPlanNodes(donors, minPlan));
                    }
                };
        AbstractCapacityBalanceAlgorithm<TestPlan, TestNode, TestInsertionCandidate> capacityBalance =
                new AbstractCapacityBalanceAlgorithm<>(calculation, assignment, insertion, 6) {
                    @Override
                    protected Map<TestNode, TestPlan> getOtherPlanNodes(List<TestPlan> donors, TestPlan minPlan) {
                        return toCopies(super.getOtherPlanNodes(donors, minPlan));
                    }

                    @Override
                    protected long getCapacity(TestPlan plan) {
                        return DemandFeasibilityChecker.load(plan);
                    }
                };

        assertThat(nodeBalance.apply(plans)).containsExactlyElementsOf(originals);
        assertThat(capacityBalance.apply(plans)).containsExactlyElementsOf(originals);
    }

    // ---------------------------------------------------------------- signals

    @Test
    void anInterruptedCapacityBalanceReturnsItsInputAndInterruptsTheInsertion() {
        List<TestNode> customers = customers(5, "NoZone", 2);
        MatrixCostCalculation calculation = calculation(customers);
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 9), new TestTrackable("T2", 9)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("full", "NoZone", customers.subList(0, 4)),
                plan("empty", "NoZone", customers.subList(4, 5))));
        assertThat(assignment.assign(plans)).isTrue();
        List<TestPlan> originals = List.copyOf(plans);
        boolean[] insertionInterrupted = new boolean[1];
        TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), false) {
            @Override
            public void interrupt() {
                insertionInterrupted[0] = true;
                super.interrupt();
            }
        };
        TestCapacityBalance balance = new TestCapacityBalance(calculation, assignment, insertion, 4);

        balance.interrupt();

        assertThat(insertionInterrupted[0]).isTrue();
        assertThat(balance.apply(plans)).containsExactlyElementsOf(originals);
    }

    // ---------------------------------------------------------------- helpers

    @Test
    void plansAreLocatedByIdentityAndCustomersByCode() {
        TestPlan plan = plan("p", "NoZone", customers(2, "NoZone", 1));
        TestPlan equalCopy = plan.copy();
        List<TestPlan> plans = List.of(plan);

        assertThat(equalCopy).isEqualTo(plan);
        assertThat(AbstractBalanceAlgorithm.indexOfInstance(plans, plan)).isZero();
        assertThat(AbstractBalanceAlgorithm.indexOfInstance(plans, equalCopy)).as("an equal copy is another plan")
                .isEqualTo(-1);
        List<TestNode> nodes = plan.getNodes();
        assertThat(AbstractBalanceAlgorithm.indexOfCode(nodes, "C1")).isEqualTo(2);
        assertThat(AbstractBalanceAlgorithm.indexOfCode(nodes, "missing")).isEqualTo(-1);
        assertThat(AbstractBalanceAlgorithm.ascendingPositions(List.of(3L, 1L, 3L, 1L), Long::longValue))
                .as("stable: ties keep list order").containsExactly(1, 3, 0, 2);
    }

    @Test
    void capacityBalancingNeverTakesTheLastCustomerOfADonor() {
        // Floor 3 on the load, with a plan without customers counted as meeting it. R = [O, r] (load 1) is short by 2.
        // Taking S's only customer s (demand 3, right next to r) would leave S without customers and was the cheapest
        // admissible transfer (shortfall 2 -> 0). A donor keeps at least one customer, as in node balancing, so R takes
        // t1 from T = [O, t1, t2, t3] instead: [O, r, t1] costs 10 + 60 = 70 against 80 for t2 and 90 for t3, all three
        // leaving a shortfall of 1. No further transfer shrinks it (t2 leaves it at 1, t3 raises it to 2).
        TestNode r = TestNode.customer("r", 10, 0, 1);
        TestNode s = TestNode.customer("s", 12, 0, 3);
        TestNode t1 = TestNode.customer("t1", 0, 50, 1);
        TestNode t2 = TestNode.customer("t2", 0, 60, 1);
        TestNode t3 = TestNode.customer("t3", 0, 70, 2);
        List<TestNode> customers = List.of(r, s, t1, t2, t3);
        MatrixCostCalculation calculation = calculation(customers);
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 10),
                new TestTrackable("T2", 10), new TestTrackable("T3", 10)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("R", "NoZone", List.of(r)), plan("S", "NoZone", List.of(s)),
                plan("T", "NoZone", List.of(t1, t2, t3))));
        assertThat(assignment.assign(plans)).isTrue();
        FixedCapacityBalance balance = new FixedCapacityBalance(calculation, assignment, insertion(calculation), 3,
                plan -> plan.getCustomerCount() == 0 ? 3 : DemandFeasibilityChecker.load(plan));

        List<TestPlan> result = balance.apply(plans);

        assertThat(routes(result)).containsExactly(List.of("R", "O", "r", "t1"), List.of("S", "O", "s"),
                List.of("T", "O", "t2", "t3"));
        PlanInvariants.assertNoEmptyPlans(result);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, customers);
        PlanInvariants.assertTrackablesAreDistinct(result);
    }

    /**
     * Capacity balance with a measure supplied per plan.
     */
    private static final class FixedCapacityBalance
            extends AbstractCapacityBalanceAlgorithm<TestPlan, TestNode, TestInsertionCandidate> {

        private final ToLongFunction<TestPlan> capacity;

        private FixedCapacityBalance(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
                TestAssignment assignment, TestInsertion insertion, long minCapacity,
                ToLongFunction<TestPlan> capacity) {
            super(costCalculation, assignment, insertion, minCapacity);
            this.capacity = capacity;
        }

        @Override
        protected long getCapacity(TestPlan plan) {
            return capacity.applyAsLong(plan);
        }

    }

    /**
     * Capacity balance on utilisation: the plan's customers per seat of its assigned vehicle, in percent.
     */
    private static final class UtilisationBalance
            extends AbstractCapacityBalanceAlgorithm<TestPlan, TestNode, TestInsertionCandidate> {

        private UtilisationBalance(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
                TestAssignment assignment, TestInsertion insertion, long minCapacity) {
            super(costCalculation, assignment, insertion, minCapacity);
        }

        @Override
        protected long getCapacity(TestPlan plan) {
            return 100L * plan.getCustomerCount() / plan.getTrackable().getCapacity();
        }

    }

}
