package ir.baho.algorithm.balance;

import ir.baho.algorithm.OptimizationType;
import ir.baho.algorithm.exception.CancelException;
import ir.baho.algorithm.support.AlwaysFeasibleChecker;
import ir.baho.algorithm.support.DemandFeasibilityChecker;
import ir.baho.algorithm.support.MatrixCostCalculation;
import ir.baho.algorithm.support.PlanInvariants;
import ir.baho.algorithm.support.TestAssignment;
import ir.baho.algorithm.support.TestCapacityBalance;
import ir.baho.algorithm.support.TestInsertion;
import ir.baho.algorithm.support.TestNode;
import ir.baho.algorithm.support.TestNodeBalance;
import ir.baho.algorithm.support.TestPlan;
import ir.baho.algorithm.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Balancing moves work from over-loaded plans onto under-loaded ones. Unlike the optimization algorithms it is
 * <em>not</em> a cost-improvement search — it deliberately accepts a more expensive plan set in exchange for a
 * flatter load — so the contract worth pinning is: the imbalance shrinks (or the threshold is already met),
 * every customer survives exactly once, and the loop reaches a fixed point instead of shuffling forever.
 */
class BalanceAlgorithmTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);

    private List<TestNode> customers(int count) {
        List<TestNode> customers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            customers.add(TestNode.customer("C" + i, 10 * (i + 1), 0, 2));
        }
        return customers;
    }

    private MatrixCostCalculation calculation(List<TestNode> customers) {
        List<TestNode> nodes = new ArrayList<>();
        nodes.add(origin);
        nodes.addAll(customers);
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(nodes);
        return calculation;
    }

    private TestPlan plan(String code, List<TestNode> planCustomers, int capacity) {
        List<TestNode> nodes = new ArrayList<>();
        nodes.add(origin);
        nodes.addAll(planCustomers);
        TestPlan plan = new TestPlan(code, nodes);
        plan.setTrackable(new TestTrackable(code + "-t", capacity));
        return plan;
    }

    private int spread(List<TestPlan> plans) {
        return plans.stream().mapToInt(TestPlan::getCustomerCount).max().orElseThrow()
                - plans.stream().mapToInt(TestPlan::getCustomerCount).min().orElseThrow();
    }

    // ---------------------------------------------------------------- customer-count balancing

    @Test
    void movesCustomersOffTheOverloadedPlan() {
        List<TestNode> customers = customers(5);
        MatrixCostCalculation calculation = calculation(customers);
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("heavy-t", 9), new TestTrackable("light-t", 9)));
        List<TestPlan> plans = new ArrayList<>(List.of(
                plan("heavy", customers.subList(0, 4), 9),
                plan("light", customers.subList(4, 5), 9)));
        assertThat(spread(plans)).isEqualTo(3);

        List<TestPlan> result = new TestNodeBalance(calculation, assignment,
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), 2).apply(plans);

        assertThat(spread(result)).as("the 4-vs-1 imbalance must shrink below the threshold of 2")
                .isLessThan(3);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, customers);
        PlanInvariants.assertNodeLayout(result);
        PlanInvariants.assertTrackablesAreDistinct(result);
    }

    @Test
    void anAlreadyBalancedPlanSetIsLeftAlone() {
        List<TestNode> customers = customers(4);
        MatrixCostCalculation calculation = calculation(customers);
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("p1-t", 9), new TestTrackable("p2-t", 9)));
        List<TestPlan> plans = new ArrayList<>(List.of(
                plan("p1", customers.subList(0, 2), 9),
                plan("p2", customers.subList(2, 4), 9)));
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);

        List<TestPlan> result = new TestNodeBalance(calculation, assignment,
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), 2).apply(plans);

        assertThat(result).containsExactlyInAnyOrderElementsOf(snapshot);
    }

    @Test
    void aThresholdWiderThanTheImbalanceDisablesBalancing() {
        List<TestNode> customers = customers(5);
        MatrixCostCalculation calculation = calculation(customers);
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("heavy-t", 9), new TestTrackable("light-t", 9)));
        List<TestPlan> plans = new ArrayList<>(List.of(
                plan("heavy", customers.subList(0, 4), 9),
                plan("light", customers.subList(4, 5), 9)));
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);

        List<TestPlan> result = new TestNodeBalance(calculation, assignment,
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), 99).apply(plans);

        assertThat(result).containsExactlyInAnyOrderElementsOf(snapshot);
    }

    @Test
    void balancingRespectsVehicleCapacity() {
        List<TestNode> customers = customers(4);
        MatrixCostCalculation calculation = calculation(customers);
        // The light plan's vehicle can carry two customers at most, so it can absorb exactly one more.
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("heavy-t", 3), new TestTrackable("light-t", 2)));
        List<TestPlan> plans = new ArrayList<>(List.of(
                plan("heavy", customers.subList(0, 3), 3),
                plan("light", customers.subList(3, 4), 2)));

        List<TestPlan> result = new TestNodeBalance(calculation, assignment,
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), 1).apply(plans);

        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, customers);
        PlanInvariants.assertCustomerCountWithinCapacity(result);
    }

    @Test
    void terminatesAtAFixedPoint() {
        List<TestNode> customers = customers(6);
        MatrixCostCalculation calculation = calculation(customers);
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("heavy-t", 9), new TestTrackable("light-t", 9)));
        List<TestPlan> plans = new ArrayList<>(List.of(
                plan("heavy", customers.subList(0, 5), 9),
                plan("light", customers.subList(5, 6), 9)));

        List<TestPlan> result = assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> new TestNodeBalance(calculation, assignment,
                        new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), 1).apply(plans));

        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, customers);
    }

    @Test
    void aSinglePlanCannotBeBalancedAgainstAnything() {
        List<TestNode> customers = customers(3);
        MatrixCostCalculation calculation = calculation(customers);
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("p-t", 9)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p", customers, 9)));
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);

        assertThat(new TestNodeBalance(calculation, assignment,
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), 1).apply(plans))
                .isEqualTo(snapshot);
    }

    // ---------------------------------------------------------------- capacity balancing

    @Test
    void pullsWorkOntoPlansBelowTheCapacityFloor() {
        List<TestNode> customers = customers(5);   // each demands 2
        MatrixCostCalculation calculation = calculation(customers);
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("full-t", 9), new TestTrackable("empty-t", 9)));
        List<TestPlan> plans = new ArrayList<>(List.of(
                plan("full", customers.subList(0, 4), 9),      // load 8
                plan("empty", customers.subList(4, 5), 9)));   // load 2, below the floor of 4

        List<TestPlan> result = new TestCapacityBalance(calculation, assignment,
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), 4).apply(plans);

        assertThat(result).allSatisfy(plan -> assertThat(DemandFeasibilityChecker.load(plan))
                .as("plan %s reaches the capacity floor", plan.getCode()).isGreaterThanOrEqualTo(4));
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, customers);
    }

    @Test
    void plansAlreadyMeetingTheFloorAreLeftAlone() {
        List<TestNode> customers = customers(4);
        MatrixCostCalculation calculation = calculation(customers);
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("p1-t", 9), new TestTrackable("p2-t", 9)));
        List<TestPlan> plans = new ArrayList<>(List.of(
                plan("p1", customers.subList(0, 2), 9),
                plan("p2", customers.subList(2, 4), 9)));
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);

        List<TestPlan> result = new TestCapacityBalance(calculation, assignment,
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), 4).apply(plans);

        assertThat(result).containsExactlyInAnyOrderElementsOf(snapshot);
    }

    @Test
    void anUnreachableFloorStillTerminatesWithoutLosingCustomers() {
        List<TestNode> customers = customers(3);
        MatrixCostCalculation calculation = calculation(customers);
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("p1-t", 9), new TestTrackable("p2-t", 9)));
        List<TestPlan> plans = new ArrayList<>(List.of(
                plan("p1", customers.subList(0, 2), 9),
                plan("p2", customers.subList(2, 3), 9)));

        // A floor of 1000 can never be met; the algorithm must give up rather than loop.
        List<TestPlan> result = assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> new TestCapacityBalance(calculation, assignment,
                        new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), 1000).apply(plans));

        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, customers);
    }

    @Test
    void balancingAnEmptyPlanListReturnsAnEmptyList() {
        // Regression: the zone splitter was handed plans.size() as its cap, which is 0 for an empty list and is now
        // rejected; balancing must clamp it instead of failing.
        MatrixCostCalculation calculation = calculation(customers(0));
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 5)));
        TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), false);

        assertThat(new TestNodeBalance(calculation, assignment, insertion, 2).apply(new ArrayList<>())).isEmpty();
        assertThat(new TestCapacityBalance(calculation, assignment, insertion, 2).apply(new ArrayList<>())).isEmpty();
    }

    /**
     * Two small plans next to the depot ([A] and [B, C]) and one far plan of five: the fixture of the bouncing defect.
     */
    private List<TestPlan> lopsidedPlans(List<TestNode> near, List<TestNode> far) {
        List<TestNode> farNodes = new ArrayList<>();
        farNodes.add(origin);
        farNodes.addAll(far);
        return new ArrayList<>(List.of(new TestPlan("P1", List.of(origin, near.get(0))),
                new TestPlan("P2", List.of(origin, near.get(1), near.get(2))), new TestPlan("P3", farNodes)));
    }

    @Test
    void onlyADonorThatShrinksTheImbalanceGivesUpACustomer() {
        // [1, 2, 5] with threshold 2: the cheap move pulls B from P2 into P1, which only swaps the two small plans
        // ([2, 1, 5]) and repeats until a state recurs, leaving the spread at 4 and the cost higher. Only P3 (at least
        // two customers more than the receiver) may donate.
        List<TestNode> near = List.of(TestNode.customer("A", 10, 0, 1), TestNode.customer("B", 12, 0, 1),
                TestNode.customer("C", 14, 0, 1));
        List<TestNode> far = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            far.add(TestNode.customer("F" + i, 0, 100 + i, 1));
        }
        List<TestNode> all = new ArrayList<>(near);
        all.addAll(far);
        MatrixCostCalculation calculation = calculation(all);
        List<TestPlan> plans = lopsidedPlans(near, far);
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 10),
                new TestTrackable("T2", 10), new TestTrackable("T3", 10)));
        assignment.assign(plans);

        List<TestPlan> result = new TestNodeBalance(calculation, assignment,
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), 2).apply(plans);

        assertThat(spread(result)).as("below the threshold of 2").isLessThan(2);
        assertThat(result).anySatisfy(plan -> assertThat(plan.getCustomers()).contains(near.get(1), near.get(2)));
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, all);

        // Capacity balancing with a floor of 2 (demand 1 each): P2 would drop below the floor if it donated.
        List<TestPlan> capacityResult = new TestCapacityBalance(calculation, assignment,
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), 2).apply(lopsidedPlans(near, far));

        assertThat(capacityResult).allSatisfy(plan -> assertThat(DemandFeasibilityChecker.load(plan))
                .as("plan %s meets the floor", plan.getCode()).isGreaterThanOrEqualTo(2));
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(capacityResult, all);
    }

    @Test
    void balancingDoesNotDependOnThePlansEquality() {
        // Copies of plans without value equality are never equal to the plans they were copied from; locating plans
        // and donors by equals used to fail with IndexOutOfBoundsException.
        List<TestNode> customers = customers(6);
        MatrixCostCalculation calculation = calculation(customers);
        for (TestPlan.Equality equality : List.of(TestPlan.Equality.IDENTITY, TestPlan.Equality.CODE)) {
            TestAssignment assignment = new TestAssignment(List.of(
                    new TestTrackable("heavy-t", 9), new TestTrackable("light-t", 9)));
            List<TestPlan> plans = new ArrayList<>(List.of(
                    plan("heavy", customers.subList(0, 5), 9), plan("light", customers.subList(5, 6), 9)));
            plans.forEach(plan -> plan.setEquality(equality));

            List<TestPlan> nodeResult = assertTimeoutPreemptively(Duration.ofSeconds(30),
                    () -> new TestNodeBalance(calculation, assignment,
                            new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), 2).apply(plans));
            List<TestPlan> capacityResult = assertTimeoutPreemptively(Duration.ofSeconds(30),
                    () -> new TestCapacityBalance(calculation, assignment,
                            new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), 6).apply(plans));

            assertThat(spread(nodeResult)).as("%s", equality).isLessThan(2);
            PlanInvariants.assertAllCustomersVisitedExactlyOnce(nodeResult, customers);
            assertThat(capacityResult).as("%s", equality).allSatisfy(plan ->
                    assertThat(DemandFeasibilityChecker.load(plan)).isGreaterThanOrEqualTo(6));
            PlanInvariants.assertAllCustomersVisitedExactlyOnce(capacityResult, customers);
        }
    }

    @Test
    void cancelAbortsAndInterruptStopsBalancingAndBothReachTheInsertion() {
        List<TestNode> customers = customers(6);
        MatrixCostCalculation calculation = calculation(customers);
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("heavy-t", 9), new TestTrackable("light-t", 9)));
        AtomicBoolean insertionCancelled = new AtomicBoolean();
        AtomicBoolean insertionInterrupted = new AtomicBoolean();
        TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), false) {
            @Override
            public void cancel() {
                insertionCancelled.set(true);
                super.cancel();
            }

            @Override
            public void interrupt() {
                insertionInterrupted.set(true);
                super.interrupt();
            }
        };
        List<TestPlan> plans = new ArrayList<>(List.of(
                plan("heavy", customers.subList(0, 5), 9), plan("light", customers.subList(5, 6), 9)));
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);

        TestNodeBalance interrupted = new TestNodeBalance(calculation, assignment, insertion, 1);
        interrupted.interrupt();
        assertThat(interrupted.apply(plans)).as("an interrupted balance keeps its best state so far")
                .isEqualTo(snapshot);
        assertThat(insertionInterrupted).isTrue();

        TestCapacityBalance cancelled = new TestCapacityBalance(calculation, assignment,
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), 6);
        cancelled.cancel();
        assertThatThrownBy(() -> cancelled.apply(plans)).isInstanceOf(CancelException.class);
        TestNodeBalance cancelledNode = new TestNodeBalance(calculation, assignment, insertion, 1);
        cancelledNode.cancel();
        assertThatThrownBy(() -> cancelledNode.apply(plans)).isInstanceOf(CancelException.class);
        assertThat(insertionCancelled).isTrue();
    }

    @Test
    void balancingZoneGroupsNeverBooksAUnitHeldByAnotherGroup() {
        // Two zones that do not neighbour each other, each with a lopsided pair of plans. Each group re-assigns its
        // plans when it accepts a transfer; smallest-code-first the pool offers the other group's units first, so a
        // group that assigned from the whole pool (instead of the pool minus the other groups' plans) booked them
        // twice.
        TestNode a1 = TestNode.customer("A1", "Z1", 10, 0, 2);
        TestNode a2 = TestNode.customer("A2", "Z1", 20, 0, 2);
        TestNode a3 = TestNode.customer("A3", "Z1", 30, 0, 2);
        TestNode a4 = TestNode.customer("A4", "Z1", 40, 0, 2);
        TestNode b1 = TestNode.customer("B1", "Z2", 0, 10, 2);
        TestNode b2 = TestNode.customer("B2", "Z2", 0, 20, 2);
        TestNode b3 = TestNode.customer("B3", "Z2", 0, 30, 2);
        TestNode b4 = TestNode.customer("B4", "Z2", 0, 40, 2);
        MatrixCostCalculation calculation = new MatrixCostCalculation(OptimizationType.LOCATION_ZONE,
                Map.of("Z1", List.of("Z1"), "Z2", List.of("Z2")));
        calculation.initializeCostMatrix(List.of(origin, a1, a2, a3, a4, b1, b2, b3, b4));
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 9), new TestTrackable("T2", 9),
                new TestTrackable("T3", 9), new TestTrackable("T4", 9)));
        List<TestPlan> plans = new ArrayList<>(List.of(
                new TestPlan("b-big", List.of(origin, b1, b2, b3), "Z2"), new TestPlan("b-small", List.of(origin, b4),
                        "Z2"),
                new TestPlan("a-big", List.of(origin, a1, a2, a3), "Z1"), new TestPlan("a-small", List.of(origin, a4),
                        "Z1")));
        assertThat(assignment.assign(plans)).isTrue();   // b-big=T1, b-small=T2, a-big=T3, a-small=T4

        List<TestPlan> result = new TestNodeBalance(calculation, assignment,
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), 2).apply(plans);

        assertThat(result).extracting(TestPlan::getCustomerCount).as("both groups balanced").containsOnly(2);
        PlanInvariants.assertEveryPlanHasTrackable(result);
        PlanInvariants.assertTrackablesAreDistinct(result);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(a1, a2, a3, a4, b1, b2, b3, b4));
    }

    @Test
    void zoneGroupsAreBalancedWithoutSharingVehiclesAndComeBackInInputOrder() {
        // Zone Z1: A holds four customers on T1, B one on T2 (capacity 2); zone Z2: C on T3. Each group re-assigned its
        // plans from the whole fleet, so Z1 handed T3 to A while Z2's C kept it: one vehicle on two routes. The groups
        // also came back in hash order.
        TestNode depot = TestNode.origin("O", 0, 0);
        List<TestNode> zone1 = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            zone1.add(TestNode.customer("A" + i, "Z1", 10 + i, 0, 1));
        }
        TestNode zone2 = TestNode.customer("C0", "Z2", 0, 500, 1);
        List<TestNode> all = new ArrayList<>(List.of(depot));
        all.addAll(zone1);
        all.add(zone2);
        MatrixCostCalculation calculation = new MatrixCostCalculation(OptimizationType.LOCATION_ZONE,
                Map.of("Z1", List.of("Z1"), "Z2", List.of("Z2")));
        calculation.initializeCostMatrix(all);
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 10), new TestTrackable("T2", 2),
                new TestTrackable("T3", 3)));
        TestPlan c = new TestPlan("C", List.of(depot, zone2), "Z2");
        TestPlan a = new TestPlan("A", List.of(depot, zone1.get(0), zone1.get(1), zone1.get(2), zone1.get(3)), "Z1");
        TestPlan b = new TestPlan("B", List.of(depot, zone1.get(4)), "Z1");
        List<TestPlan> plans = new ArrayList<>(List.of(c, a, b));
        assertThat(assignment.assign(List.of(a, b, c))).isTrue();

        List<TestPlan> result = new TestNodeBalance(calculation, assignment,
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), 2).apply(plans);

        PlanInvariants.assertTrackablesAreDistinct(result);
        PlanInvariants.assertEveryPlanHasTrackable(result);
        PlanInvariants.assertCustomerCountWithinCapacity(result);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, all.subList(1, all.size()));
        assertThat(result).extracting(TestPlan::getCode).containsExactly("C", "A", "B");
    }

    @Test
    void aTransferThatLowersTheTotalShortfallIsAdmittedEvenWhenTheDonorDropsBelowTheFloor() {
        // Floor 6: the donor holds demands 5 and 2, the receiver nothing. Moving either customer lowers the total
        // shortfall from 6 to 5 (donor 2 -> 4 short, receiver 5 -> 1 short), yet a rule that required the donor to
        // stay at the floor refused both and returned the input unchanged.
        TestNode heavy = TestNode.customer("H", 10, 0, 5);
        TestNode light = TestNode.customer("L", 20, 0, 2);
        MatrixCostCalculation calculation = calculation(List.of(heavy, light));
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("donor-t", 9),
                new TestTrackable("receiver-t", 9)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("donor", List.of(heavy, light), 9), plan("receiver",
                List.of(), 9)));

        List<TestPlan> result = new TestCapacityBalance(calculation, assignment,
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), 6).apply(plans);

        assertThat(result).extracting(DemandFeasibilityChecker::load).containsExactlyInAnyOrder(2L, 5L);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(heavy, light));
        PlanInvariants.assertTrackablesAreDistinct(result);
    }

    @Test
    void amongTheCandidateTransfersTheCheapestResultingPlanSetIsKept() {
        // Regression: the balancer used to keep the MOST expensive candidate set. Big plan O-A-B-C (C far out on
        // the x axis), small plan O-D. Moving A gives 170, B gives 160, C gives 140 in total; C must be chosen.
        TestNode a = TestNode.customer("A", 0, 10, 1);
        TestNode b = TestNode.customer("B", 0, 20, 1);
        TestNode c = TestNode.customer("C", 100, 0, 1);
        TestNode d = TestNode.customer("D", 0, -10, 1);
        MatrixCostCalculation calculation = calculation(List.of(a, b, c, d));
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("big-t", 5),
                new TestTrackable("small-t", 5)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("big", List.of(a, b, c), 5), plan("small", List.of(d), 5)));
        assertThat(PlanInvariants.referenceTotalCost(plans)).isEqualTo(10 + 10 + 120 + 10);

        List<TestPlan> result = new TestNodeBalance(calculation, assignment,
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), 2).apply(plans);

        assertThat(PlanInvariants.referenceTotalCost(result)).isEqualTo(140);
        assertThat(result).anySatisfy(plan -> assertThat(plan.getCustomers()).containsExactlyInAnyOrder(c, d));
        assertThat(result).anySatisfy(plan -> assertThat(plan.getCustomers()).containsExactlyInAnyOrder(a, b));
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(a, b, c, d));
    }

}
