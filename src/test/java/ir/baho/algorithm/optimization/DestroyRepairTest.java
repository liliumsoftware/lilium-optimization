package ir.baho.algorithm.optimization;

import ir.baho.algorithm.OptimizationType;
import ir.baho.algorithm.exception.CancelException;
import ir.baho.algorithm.support.AlwaysFeasibleChecker;
import ir.baho.algorithm.support.CancelSpyLocalSearch;
import ir.baho.algorithm.support.MatrixCostCalculation;
import ir.baho.algorithm.support.PlanInvariants;
import ir.baho.algorithm.support.Problems;
import ir.baho.algorithm.support.RecostingLocalSearch;
import ir.baho.algorithm.support.TestAssignment;
import ir.baho.algorithm.support.TestDestroyRepair;
import ir.baho.algorithm.support.TestInsertion;
import ir.baho.algorithm.support.TestNode;
import ir.baho.algorithm.support.TestPlan;
import ir.baho.algorithm.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Destroy-and-repair is the escape hatch from the local optima that single-move exchange/relocate settle into:
 * it rips the worst-placed stops out of a plan and re-inserts them cheapest-first, keeping the result only if
 * it is feasible and cheaper.
 *
 * <p>Two things make it risky and therefore worth pinning: it removes customers from the plan set temporarily
 * (so a failed repair must never leave one dropped), and its outer loop runs to a fixed point with no iteration
 * cap (so it must be shown to terminate rather than oscillate).
 */
class DestroyRepairTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode near1 = TestNode.customer("N1", 10, 0, 1);
    private final TestNode near2 = TestNode.customer("N2", 20, 0, 1);
    private final TestNode far1 = TestNode.customer("F1", 1000, 0, 1);
    private final TestNode far2 = TestNode.customer("F2", 1010, 0, 1);

    private MatrixCostCalculation calculation() {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, near1, near2, far1, far2));
        return calculation;
    }

    private TestAssignment fleet() {
        return new TestAssignment(List.of(new TestTrackable("T1", 5), new TestTrackable("T2", 5)));
    }

    private TestDestroyRepair destroyRepair(MatrixCostCalculation calculation, int chain, int threshold) {
        return new TestDestroyRepair(calculation, fleet(), new RecostingLocalSearch(calculation),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), chain, threshold);
    }

    /**
     * Two plans that each straddle both clusters — the arrangement destroy-and-repair should be able to undo.
     */
    private List<TestPlan> crossedPlans() {
        TestPlan first = new TestPlan("p1", List.of(origin, near1, far1));
        TestPlan second = new TestPlan("p2", List.of(origin, near2, far2));
        first.setTrackable(new TestTrackable("T1", 5));
        second.setTrackable(new TestTrackable("T2", 5));
        return new ArrayList<>(List.of(first, second));
    }

    // ---------------------------------------------------------------- behaviour

    @Test
    void neverWorsensAndKeepsEveryCustomer() {
        MatrixCostCalculation calculation = calculation();
        List<TestPlan> plans = crossedPlans();
        long before = PlanInvariants.referenceTotalCost(plans);

        List<TestPlan> result = destroyRepair(calculation, 2, 0).apply(plans);

        assertThat(PlanInvariants.referenceTotalCost(result)).isLessThanOrEqualTo(before);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(near1, near2, far1, far2));
        PlanInvariants.assertNodeLayout(result);
        PlanInvariants.assertTrackablesAreDistinct(result);
        PlanInvariants.assertNoEmptyPlans(result);
    }

    @Test
    void repairsAMisplacedStopIntoTheCheaperPlan() {
        MatrixCostCalculation calculation = calculation();
        // p1 carries both near customers plus a stray far one; p2 carries the other far customer. Pulling the
        // stray out of p1 and re-inserting it next to its neighbour saves p1's two 990-unit legs.
        TestPlan first = new TestPlan("p1", List.of(origin, near1, near2, far1));
        TestPlan second = new TestPlan("p2", List.of(origin, far2));
        first.setTrackable(new TestTrackable("T1", 5));
        second.setTrackable(new TestTrackable("T2", 5));
        List<TestPlan> plans = new ArrayList<>(List.of(first, second));
        long before = PlanInvariants.referenceTotalCost(plans);

        List<TestPlan> result = destroyRepair(calculation, 1, 0).apply(plans);

        assertThat(PlanInvariants.referenceTotalCost(result)).isLessThan(before);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(near1, near2, far1, far2));
    }

    @Test
    void anAlreadyGroupedCapacityBoundSolutionIsLeftAlone() {
        MatrixCostCalculation calculation = calculation();
        // Capacity is what makes the grouped solution optimal: without a capacity bound a single open route
        // through everything is genuinely cheaper (no return leg to pay for), and the algorithm rightly finds
        // it. With two-customer vehicles no re-insertion can be resourced, so nothing may change.
        TestAssignment tightFleet = new TestAssignment(List.of(
                new TestTrackable("T1", 2), new TestTrackable("T2", 2)));
        TestPlan nearPlan = new TestPlan("near", List.of(origin, near1, near2));
        TestPlan farPlan = new TestPlan("far", List.of(origin, far1, far2));
        nearPlan.setTrackable(new TestTrackable("T1", 2));
        farPlan.setTrackable(new TestTrackable("T2", 2));
        List<TestPlan> plans = new ArrayList<>(List.of(nearPlan, farPlan));
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);

        List<TestPlan> result = new TestDestroyRepair(calculation, tightFleet, new RecostingLocalSearch(calculation),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), 2, 0).apply(plans);

        assertThat(result).isEqualTo(snapshot);
    }

    @Test
    void mergesEverythingIntoOneOpenRouteWhenNothingConstrainsIt() {
        // The counterpart of the test above: an open route pays no return leg, so serving all four customers
        // with one vehicle (1010) beats two separate routes (1030). The repair must find that and drop the
        // emptied plan rather than leaving a customer-less route behind.
        TestPlan nearPlan = new TestPlan("near", List.of(origin, near1, near2));
        TestPlan farPlan = new TestPlan("far", List.of(origin, far1, far2));
        nearPlan.setTrackable(new TestTrackable("T1", 5));
        farPlan.setTrackable(new TestTrackable("T2", 5));
        List<TestPlan> plans = new ArrayList<>(List.of(nearPlan, farPlan));
        assertThat(PlanInvariants.referenceTotalCost(plans)).isEqualTo(1030);

        MatrixCostCalculation calculation = calculation();
        List<TestPlan> result = destroyRepair(calculation, 2, 0).apply(plans);

        assertThat(result).hasSize(1);
        assertThat(PlanInvariants.referenceTotalCost(result)).isEqualTo(1010);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(near1, near2, far1, far2));
        PlanInvariants.assertNoEmptyPlans(result);
    }

    @Test
    void terminatesRatherThanOscillating() {
        MatrixCostCalculation calculation = calculation();
        List<TestPlan> plans = crossedPlans();

        // No iteration cap exists in the implementation: convergence relies on cost strictly decreasing. A
        // generous wall-clock ceiling catches a genuine infinite loop without depending on machine speed.
        List<TestPlan> result = assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> destroyRepair(calculation, 3, 0).apply(plans));

        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(near1, near2, far1, far2));
    }

    @Test
    void theCallersPlanObjectsAreNeverModified() {
        // Only the small-plan pass can act here (the threshold blocks the ranked pass). It used to re-run its best
        // move on the live list, emptying the caller's own p1 object while the result held copies.
        TestNode a = TestNode.customer("A", 0, 10, 1);
        TestNode b = TestNode.customer("B", 0, 20, 1);
        TestNode c = TestNode.customer("C", 0, 21, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, a, b, c));
        TestAssignment assignment = fleet();
        TestPlan first = new TestPlan("p1", List.of(origin, a, b));
        TestPlan second = new TestPlan("p2", List.of(origin, c));
        List<TestPlan> plans = new ArrayList<>(List.of(first, second));
        assignment.assign(plans);
        TestPlan firstBefore = first.copy();
        long before = PlanInvariants.referenceTotalCost(plans);

        List<TestPlan> result = new TestDestroyRepair(calculation, assignment, new RecostingLocalSearch(calculation),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), 2, 1000).apply(plans);

        assertThat(PlanInvariants.referenceTotalCost(result)).isLessThan(before);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(a, b, c));
        assertThat(first).as("the caller's p1").isEqualTo(firstBefore);
        TestPlan secondBefore = second.copy();
        assertThat(second).as("the caller's p2").isEqualTo(secondBefore);
    }

    @Test
    void terminatesAndStillImprovesForPlansWithoutValueEquality() {
        // A consumer whose plans are equal only to themselves (or only by code): the fixed-point checks compared plan
        // lists by equals and never saw two copies as equal (identity) or saw every copy as equal (code), so the
        // search looped forever or never committed an improvement.
        for (TestPlan.Equality equality : List.of(TestPlan.Equality.IDENTITY, TestPlan.Equality.CODE)) {
            MatrixCostCalculation calculation = calculation();
            List<TestPlan> plans = crossedPlans();
            plans.forEach(plan -> plan.setEquality(equality));
            long before = PlanInvariants.referenceTotalCost(plans);

            List<TestPlan> result = assertTimeoutPreemptively(Duration.ofSeconds(30),
                    () -> destroyRepair(calculation, 2, 0).apply(plans));

            assertThat(PlanInvariants.referenceTotalCost(result)).as("%s", equality).isLessThan(before);
            PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(near1, near2, far1, far2));
            PlanInvariants.assertTrackablesAreDistinct(result);
        }
    }

    @Test
    void plansEqualByCodeAreRepairedAsThoroughlyAsPlansEqualByValue() {
        // The pass loop used to stop as soon as the plan list equalled the previous pass's snapshot; under code
        // equality an improving pass leaves the list equal to its snapshot, so the search ended after one pass and
        // finished far worse than the same instance with value equality. Poor starting routes (customers chunked in
        // input order) need several passes to repair.
        for (long seed : new long[] {3L, 7L, 11L}) {
            long[] totals = new long[2];
            int i = 0;
            for (TestPlan.Equality equality : List.of(TestPlan.Equality.VALUE, TestPlan.Equality.CODE)) {
                Problems.Problem problem = Problems.random(seed, 16, 4, 6, 1_000);
                MatrixCostCalculation calculation = problem.calculation();
                TestAssignment assignment = problem.assignment();
                List<TestPlan> plans = new ArrayList<>();
                for (int chunk = 0; chunk < 4; chunk++) {
                    List<TestNode> nodes = new ArrayList<>();
                    nodes.add(problem.origin());
                    nodes.addAll(problem.customers().subList(chunk * 4, chunk * 4 + 4));
                    TestPlan plan = new TestPlan("p" + chunk, nodes);
                    plan.setEquality(equality);
                    plans.add(plan);
                }
                assertThat(assignment.assign(plans)).isTrue();

                List<TestPlan> result = new TestDestroyRepair(calculation, assignment,
                        new RecostingLocalSearch(calculation),
                        new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), 3, 0).apply(plans);

                PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, problem.customers());
                totals[i++] = PlanInvariants.referenceTotalCost(result);
            }
            assertThat(totals[1]).as("seed %d: code equality reaches the value-equality result", seed)
                    .isEqualTo(totals[0]);
        }
    }

    @Test
    void theResultDoesNotDependOnThePlansEqualityContract() {
        // Plans equal by code made the end-of-pass check "plan set equals the previous pass's" true after the very
        // first pass, so CODE stopped far above VALUE; identity equality must not change the outcome either.
        for (long seed = 1; seed <= 3; seed++) {
            Problems.Problem problem = Problems.random(seed, 24, 6, 8, 200);
            MatrixCostCalculation calculation = problem.calculation();
            List<List<String>> routes = new ArrayList<>();
            for (TestPlan.Equality equality : TestPlan.Equality.values()) {
                List<TestPlan> plans = new ArrayList<>();
                for (int i = 0, k = 0; i < problem.customers().size(); i += 4, k++) {
                    TestPlan plan = problem.plan("P" + k, problem.customers().subList(i, i + 4));
                    plan.setEquality(equality);
                    plans.add(plan);
                }
                TestAssignment assignment = problem.assignment();
                assignment.assign(plans);

                List<TestPlan> result = new TestDestroyRepair(calculation, assignment,
                        new RecostingLocalSearch(calculation),
                        new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), 2, 0).apply(plans);

                routes.add(result.stream().map(plan -> plan.getCode() + plan.getNodes()).collect(Collectors.toList()));
            }
            assertThat(routes).as("seed %d", seed).allSatisfy(route -> assertThat(route).isEqualTo(routes.get(0)));
        }
    }

    @Test
    void zoneGroupsShareTheIdleFleetWithoutStarvingEachOther() {
        // Zone Z1 can only drop to one route if it gets the single four-seat vehicle. Dealing the idle units out
        // round-robin before the groups ran gave that vehicle to the Z2 group, which had no use for it.
        TestNode a1 = TestNode.customer("A1", "Z1", 100, 0, 1);
        TestNode a2 = TestNode.customer("A2", "Z1", 100, 2, 1);
        TestNode a3 = TestNode.customer("A3", "Z1", 100, 4, 1);
        TestNode a4 = TestNode.customer("A4", "Z1", 100, 6, 1);
        TestNode b1 = TestNode.customer("B1", "Z2", -100, 0, 1);
        TestNode b2 = TestNode.customer("B2", "Z2", -100, 2, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation(OptimizationType.LOCATION_ZONE,
                Map.of("Z1", List.of("Z1"), "Z2", List.of("Z2")));
        calculation.initializeCostMatrix(List.of(origin, a1, a2, a3, a4, b1, b2));
        List<TestNode> customers = List.of(a1, a2, a3, a4, b1, b2);

        List<List<TestPlan>> results = new ArrayList<>();
        for (boolean split : new boolean[] {false, true}) {
            TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 2), new TestTrackable("T2",
                            2),
                    new TestTrackable("T3", 2), new TestTrackable("BIG", 4), new TestTrackable("S", 1)));
            List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("a1", List.of(origin, a1, a2), "Z1"),
                    new TestPlan("a2", List.of(origin, a3, a4), "Z1"), new TestPlan("b1", List.of(origin, b1, b2),
                            "Z2")));
            assertThat(assignment.assign(plans)).isTrue();
            TestDestroyRepair destroyRepair = new TestDestroyRepair(calculation, assignment,
                    new RecostingLocalSearch(calculation), new TestInsertion(calculation, new AlwaysFeasibleChecker(),
                    false), 2, 0);

            List<TestPlan> result = split ? destroyRepair.apply(plans, 2) : destroyRepair.apply(plans);

            PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, customers);
            PlanInvariants.assertEveryPlanHasTrackable(result);
            PlanInvariants.assertTrackablesAreDistinct(result);
            PlanInvariants.assertCustomerCountWithinCapacity(result);
            results.add(result);
        }
        assertThat(results.get(0)).as("unsplit run consolidates Z1").hasSize(2);
        assertThat(PlanInvariants.referenceTotalCost(results.get(1)))
                .as("the zone-split run reaches the unsplit result")
                .isEqualTo(PlanInvariants.referenceTotalCost(results.get(0)));
    }

    @Test
    void theFirstCustomerOfAPlanWithoutAnOriginIsRankedAndRepaired() {
        // Ranking started at index 1, assuming an origin there, so the first customer of a plan without one was never
        // ranked or destroyed. Here that customer (F2) is the misplaced one: [F2, N1, D] + [F1, D].
        TestNode destination = TestNode.destination("D", 0, 0);
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(near1, near2, far1, far2, destination));
        TestPlan first = new TestPlan("p1", List.of(far2, near1, destination));
        TestPlan second = new TestPlan("p2", List.of(far1, destination));
        first.setTrackable(new TestTrackable("T1", 5));
        second.setTrackable(new TestTrackable("T2", 5));
        List<TestPlan> plans = new ArrayList<>(List.of(first, second));
        long before = PlanInvariants.referenceTotalCost(plans);
        TestDestroyRepair destroyRepair = destroyRepair(calculation, 1, 0);

        assertThat(destroyRepair.highestRankNodes(plans)).extracting(Map.Entry::getKey).contains(far2, near1, far1);

        List<TestPlan> result = destroyRepair.apply(plans);

        // 2010 before; one route F2-F1-N1-D (1010) is the best layout.
        assertThat(PlanInvariants.referenceTotalCost(result)).isLessThan(before).isLessThanOrEqualTo(1010);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(near1, far1, far2));
        assertThat(result).allSatisfy(plan -> assertThat(plan.getNodes()
                .get(plan.getNodes().size() - 1)).isEqualTo(destination));
    }

    @Test
    void chainsOfAnOpenPlanWithoutAnOriginStayWithinThePlan() {
        // [N1, N2] has no origin and no destination; a chain bound derived from the customer count assumed an origin
        // at index 0 and probed one position past the end.
        MatrixCostCalculation calculation = calculation();
        TestPlan first = new TestPlan("p1", List.of(near1, near2));
        TestPlan second = new TestPlan("p2", List.of(far1, far2));
        first.setTrackable(new TestTrackable("T1", 5));
        second.setTrackable(new TestTrackable("T2", 5));
        List<TestPlan> plans = new ArrayList<>(List.of(first, second));

        List<TestPlan> result = destroyRepair(calculation, 2, 0).apply(plans);

        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(near1, near2, far1, far2));
    }

    @Test
    void aThresholdNoNodeReachesDisablesTheDestroyPass() {
        MatrixCostCalculation calculation = calculation();
        List<TestPlan> plans = crossedPlans();
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);

        // Candidate stops must rank at or above the threshold; with an unreachable threshold none qualify, so
        // only the trailing small-plan sweep runs and it cannot improve this arrangement.
        assertThat(destroyRepair(calculation, 1, 999).apply(plans)).isEqualTo(snapshot);
    }

    @Test
    void isDeterministic() {
        MatrixCostCalculation calculation = calculation();

        assertThat(destroyRepair(calculation, 2, 0).apply(crossedPlans()))
                .isEqualTo(destroyRepair(calculation, 2, 0).apply(crossedPlans()));
    }

    @Test
    void emptyAndSinglePlanInputsAreHandled() {
        MatrixCostCalculation calculation = calculation();

        assertThat(destroyRepair(calculation, 2, 0).apply(new ArrayList<>())).isEmpty();

        TestPlan only = new TestPlan("only", List.of(origin, near1, far1));
        only.setTrackable(new TestTrackable("T1", 5));
        List<TestPlan> single = new ArrayList<>(List.of(only));
        List<TestPlan> snapshot = PlanInvariants.deepCopy(single);

        // With nowhere else to re-insert, a lone plan's stops can only come back where they were.
        assertThat(destroyRepair(calculation, 1, 0).apply(single)).isEqualTo(snapshot);
    }

    // ---------------------------------------------------------------- ranking

    @Test
    void ranksBadlyPlacedStopsAboveWellPlacedOnesAndSkipsThoseBelowTheThreshold() {
        MatrixCostCalculation calculation = calculation();
        TestPlan plan = new TestPlan("p", List.of(origin, near1, far1, near2));
        plan.setTrackable(new TestTrackable("T1", 5));

        List<Map.Entry<TestNode, Integer>> ranked = destroyRepair(calculation, 2, 0)
                .highestRankNodes(new ArrayList<>(List.of(plan)));

        assertThat(ranked).isNotEmpty();
        assertThat(ranked).as("ranked worst-placed first")
                .isSortedAccordingTo((left, right) -> Integer.compare(right.getValue(), left.getValue()));
        assertThat(ranked.get(0).getValue()).as("the stop wedged between two far-away neighbours ranks top")
                .isGreaterThan(0);

        List<Map.Entry<TestNode, Integer>> filtered = destroyRepair(calculation, 2, 3)
                .highestRankNodes(new ArrayList<>(List.of(plan)));
        assertThat(filtered).allSatisfy(entry -> assertThat(entry.getValue()).isGreaterThanOrEqualTo(3));
    }

    @Test
    void onlyCustomerSegmentsMayBeDestroyed() {
        TestNode destination = TestNode.destination("D", 0, 0);
        MatrixCostCalculation withDepot = new MatrixCostCalculation();
        withDepot.initializeCostMatrix(List.of(origin, near1, near2, destination));
        TestPlan plan = new TestPlan("p", List.of(origin, near1, near2, destination));
        TestDestroyRepair destroyRepair = destroyRepair(withDepot, 2, 0);

        assertThat(destroyRepair.supportsSegment(plan, 1, 3)).as("two customers").isTrue();
        assertThat(destroyRepair.supportsSegment(plan, 0, 2)).as("starts at the origin").isFalse();
        assertThat(destroyRepair.supportsSegment(plan, 1, 4)).as("ends at the destination").isFalse();
    }

    // ---------------------------------------------------------------- cancellation

    @Test
    void cancelAbortsTheSearch() {
        MatrixCostCalculation calculation = calculation();
        TestDestroyRepair destroyRepair = destroyRepair(calculation, 2, 0);
        destroyRepair.cancel();

        assertThatThrownBy(() -> destroyRepair.apply(crossedPlans()))
                .isInstanceOf(CancelException.class);
    }

    @Test
    void cancelPropagatesToTheNestedInsertionAlgorithm() {
        MatrixCostCalculation calculation = calculation();
        CancelSpyLocalSearch localSearch = new CancelSpyLocalSearch();
        TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), false);
        TestDestroyRepair destroyRepair = new TestDestroyRepair(calculation, fleet(), localSearch, insertion, 2, 0);

        destroyRepair.cancel();

        // The insertion algorithm is the nested BaseAlgorithm this composite owns; cancelling must reach it,
        // otherwise a cancelled run keeps working inside the repair step.
        assertThatThrownBy(() -> insertion.insert(new ArrayList<>(List.of(new TestPlan("p", List.of(origin, near1)))),
                List.of(near2))).isInstanceOf(CancelException.class);
    }

}
