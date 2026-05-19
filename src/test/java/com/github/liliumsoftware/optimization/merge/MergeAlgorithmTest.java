package com.github.liliumsoftware.optimization.merge;

import com.github.liliumsoftware.optimization.OptimizationType;
import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.exception.CancelException;
import com.github.liliumsoftware.optimization.support.CancelSpyLocalSearch;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.PlanInvariants;
import com.github.liliumsoftware.optimization.support.RecostingLocalSearch;
import com.github.liliumsoftware.optimization.support.TestAssignment;
import com.github.liliumsoftware.optimization.support.TestInfiniteAssignment;
import com.github.liliumsoftware.optimization.support.TestMerge;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Merging fuses two plans into one, which is only ever worthwhile because a closed tour pays for its return
 * leg: two routes each returning to the depot cost more than one route serving both. The examples use the
 * collinear instance {@code O(0,0), A(0,10), B(0,20), D(0,0)} so the saving is arithmetic rather than
 * atmospheric — separate closed tours cost 20 + 40 = 60, the merged tour costs 10 + 10 + 20 = 40.
 *
 * <p>The structural part matters just as much: a merge must drop the first plan's destination and the second's
 * origin, so the result stays a single well-formed {@code ORIGIN, CUSTOMER…, DESTINATION} route.
 */
class MergeAlgorithmTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode destination = TestNode.destination("D", 0, 0);
    private final TestNode a = TestNode.customer("A", 0, 10, 1);
    private final TestNode b = TestNode.customer("B", 0, 20, 1);
    private final TestNode c = TestNode.customer("C", 0, 30, 1);

    private MatrixCostCalculation calculation() {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, a, b, c, destination));
        return calculation;
    }

    private TestPlan closedPlan(String code, TestTrackable trackable, TestNode... customers) {
        List<TestNode> nodes = new ArrayList<>();
        nodes.add(origin);
        nodes.addAll(List.of(customers));
        nodes.add(destination);
        TestPlan plan = new TestPlan(code, nodes);
        plan.setTrackable(trackable);
        return plan;
    }

    private TestMerge merge(MatrixCostCalculation calculation, TestAssignment assignment) {
        return new TestMerge(calculation, assignment, new RecostingLocalSearch(calculation));
    }

    // ---------------------------------------------------------------- known example

    @Test
    void fusesTwoClosedToursAndSavesTheSecondReturnLeg() {
        MatrixCostCalculation calculation = calculation();
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("T1", 5), new TestTrackable("T2", 5)));
        List<TestPlan> plans = new ArrayList<>(List.of(
                closedPlan("p1", new TestTrackable("T1", 5), a),
                closedPlan("p2", new TestTrackable("T2", 5), b)));
        assertThat(PlanInvariants.referenceTotalCost(plans)).isEqualTo(60);

        List<TestPlan> result = merge(calculation, assignment).apply(plans);

        assertThat(result).hasSize(1);
        assertThat(PlanInvariants.referenceTotalCost(result)).isEqualTo(40);
        assertThat(result.getFirst().getCustomers()).containsExactly(a, b);
        PlanInvariants.assertNodeLayout(result);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(a, b));
    }

    @Test
    void theMergedRouteKeepsExactlyOneOriginAndOneDestination() {
        MatrixCostCalculation calculation = calculation();
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("T1", 5), new TestTrackable("T2", 5)));
        List<TestPlan> plans = new ArrayList<>(List.of(
                closedPlan("p1", new TestTrackable("T1", 5), a),
                closedPlan("p2", new TestTrackable("T2", 5), b)));

        TestPlan merged = merge(calculation, assignment).apply(plans).getFirst();

        assertThat(merged.getNodes().stream().filter(n -> n.getType() == Nodeable.Type.ORIGIN)).hasSize(1);
        assertThat(merged.getNodes().stream().filter(n -> n.getType() == Nodeable.Type.DESTINATION)).hasSize(1);
        assertThat(merged.getNodes().getFirst()).isEqualTo(origin);
        assertThat(merged.getNodes().getLast()).isEqualTo(destination);
    }

    @Test
    void keepsMergingUntilNoImprovingPairIsLeft() {
        MatrixCostCalculation calculation = calculation();
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("T1", 5), new TestTrackable("T2", 5), new TestTrackable("T3", 5)));
        List<TestPlan> plans = new ArrayList<>(List.of(
                closedPlan("p1", new TestTrackable("T1", 5), a),
                closedPlan("p2", new TestTrackable("T2", 5), b),
                closedPlan("p3", new TestTrackable("T3", 5), c)));

        List<TestPlan> result = merge(calculation, assignment).apply(plans);

        // Three closed tours (20 + 40 + 60 = 120) collapse into one (10 + 10 + 10 + 30 = 60).
        assertThat(result).hasSize(1);
        assertThat(PlanInvariants.referenceTotalCost(result)).isEqualTo(60);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(a, b, c));
    }

    // ---------------------------------------------------------------- rejection

    @Test
    void aMergeNoVehicleCouldCarryIsRefused() {
        MatrixCostCalculation calculation = calculation();
        // Every vehicle tops out at one customer, so the two-customer merge cannot be resourced.
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("T1", 1), new TestTrackable("T2", 1)));
        List<TestPlan> plans = new ArrayList<>(List.of(
                closedPlan("p1", new TestTrackable("T1", 1), a),
                closedPlan("p2", new TestTrackable("T2", 1), b)));
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);

        assertThat(merge(calculation, assignment).apply(plans)).isEqualTo(snapshot);
    }

    @Test
    void plansInNonNeighbouringZonesAreNeverMerged() {
        TestNode zoneA = TestNode.customer("A", "Z1", 0, 10, 1);
        TestNode zoneB = TestNode.customer("B", "Z3", 0, 20, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation(OptimizationType.LOCATION_ZONE,
                Map.of("Z1", List.of("Z1"), "Z3", List.of("Z3")));
        calculation.initializeCostMatrix(List.of(origin, zoneA, zoneB, destination));
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("T1", 5), new TestTrackable("T2", 5)));

        TestPlan first = new TestPlan("p1", List.of(origin, zoneA, destination), "Z1");
        first.setTrackable(new TestTrackable("T1", 5));
        TestPlan second = new TestPlan("p2", List.of(origin, zoneB, destination), "Z3");
        second.setTrackable(new TestTrackable("T2", 5));
        List<TestPlan> plans = new ArrayList<>(List.of(first, second));
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);

        assertThat(merge(calculation, assignment).apply(plans)).isEqualTo(snapshot);
    }

    @Test
    void aSinglePlanOrAnEmptyListIsUnchanged() {
        MatrixCostCalculation calculation = calculation();
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 5)));

        assertThat(merge(calculation, assignment).apply(new ArrayList<>())).isEmpty();

        List<TestPlan> single = new ArrayList<>(List.of(closedPlan("p1", new TestTrackable("T1", 5), a)));
        List<TestPlan> snapshot = PlanInvariants.deepCopy(single);
        assertThat(merge(calculation, assignment).apply(single)).isEqualTo(snapshot);
    }

    @Test
    void aPlanIsNeverMergedWithItself() {
        MatrixCostCalculation calculation = calculation();
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 5)));
        TestPlan plan = closedPlan("p1", new TestTrackable("T1", 5), a);
        List<TestPlan> plans = new ArrayList<>(List.of(plan));

        // The self-merge guard sits in the pairwise entry point; without it a plan would absorb its own
        // customers and serve each of them twice.
        assertThat(merge(calculation, assignment).applyPair(plans, plan, plan)).isFalse();
        assertThat(plans).hasSize(1);
        assertThat(plans.getFirst().getCustomers()).containsExactly(a);
    }

    // ---------------------------------------------------------------- open plans and resources

    @Test
    void openPlansWithoutADestinationAlsoMerge() {
        MatrixCostCalculation calculation = calculation();
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("T1", 5), new TestTrackable("T2", 5)));
        TestPlan first = new TestPlan("p1", List.of(origin, a));
        first.setTrackable(new TestTrackable("T1", 5));
        TestPlan second = new TestPlan("p2", List.of(origin, b));
        second.setTrackable(new TestTrackable("T2", 5));
        List<TestPlan> plans = new ArrayList<>(List.of(first, second));

        List<TestPlan> result = merge(calculation, assignment).apply(plans);

        // 10 + 20 = 30 separately, 10 + 10 = 20 merged.
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getNodes()).containsExactly(origin, a, b);
        PlanInvariants.assertNodeLayout(result);
    }

    @Test
    void plansMergeWithAnUnlimitedFleet() {
        // Three open plans on the collinear line cost 10 + 20 + 30 = 60; one route O-A-B-C costs 30. An unlimited
        // fleet can always resource the merged set, so every improving merge must be accepted.
        MatrixCostCalculation calculation = calculation();
        TestInfiniteAssignment assignment = new TestInfiniteAssignment(List.of(new TestTrackable("T", 10)));
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", List.of(origin, a)),
                new TestPlan("p2", List.of(origin, b)), new TestPlan("p3", List.of(origin, c))));
        assertThat(assignment.assign(plans)).isTrue();

        List<TestPlan> result = new TestMerge(calculation, assignment, new RecostingLocalSearch(calculation))
                .apply(plans);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getNodes()).containsExactly(origin, a, b, c);
        PlanInvariants.assertEveryPlanHasTrackable(result);
    }

    @Test
    void plansWithoutAnOriginMergeIntoOneWellFormedRoute() {
        // [A, D] + [B, D] (no origin stops) cost 10 + 20; [B, A, D] costs 20.
        MatrixCostCalculation calculation = calculation();
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 5), new TestTrackable("T2", 5)));
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", List.of(a, destination)),
                new TestPlan("p2", List.of(b, destination))));
        assertThat(assignment.assign(plans)).isTrue();

        List<TestPlan> result = merge(calculation, assignment).apply(plans);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getNodes()).containsExactly(b, a, destination);
    }

    @Test
    void mergingPlansOfDifferentShapesKeepsEveryDepotStop() {
        // [A, D] absorbing [O, B]: the first plan's destination and the second plan's origin were both removed, so the
        // merged route [A, B] lost both depot stops and looked cheaper (10 < 30) than it is. Kept, O-A-B-D costs 40,
        // and the merge is rightly refused; the direct test of the merged layout follows.
        MatrixCostCalculation calculation = calculation();
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 5), new TestTrackable("T2", 5)));
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", List.of(a, destination)),
                new TestPlan("p2", List.of(origin, b))));
        assertThat(assignment.assign(plans)).isTrue();
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);

        assertThat(merge(calculation, assignment).apply(plans)).isEqualTo(snapshot);

        TestMerge merge = new TestMerge(calculation, assignment, new RecostingLocalSearch(calculation)) {
            @Override
            protected boolean compare(List<TestPlan> newPlans, List<TestPlan> oldPlans) {
                return true;   // accept the merge to inspect its layout
            }
        };
        List<TestPlan> forced = PlanInvariants.deepCopyMutable(snapshot);
        assertThat(merge.applyPair(forced, forced.get(0), forced.get(1))).isTrue();
        assertThat(forced).extracting(TestPlan::getNodes).containsExactly(List.of(origin, a, b, destination));
    }

    @Test
    void theSurvivingPlanKeepsAResourceAndNoUnitIsDoubleBooked() {
        MatrixCostCalculation calculation = calculation();
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("T1", 5), new TestTrackable("T2", 5)));
        List<TestPlan> plans = new ArrayList<>(List.of(
                closedPlan("p1", new TestTrackable("T1", 5), a),
                closedPlan("p2", new TestTrackable("T2", 5), b)));

        List<TestPlan> result = merge(calculation, assignment).apply(plans);

        PlanInvariants.assertEveryPlanHasTrackable(result);
        PlanInvariants.assertTrackablesAreDistinct(result);
        PlanInvariants.assertCustomerCountWithinCapacity(result);
    }

    @Test
    void isDeterministic() {
        MatrixCostCalculation calculation = calculation();
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("T1", 5), new TestTrackable("T2", 5), new TestTrackable("T3", 5)));

        List<TestPlan> first = merge(calculation, assignment).apply(new ArrayList<>(List.of(
                closedPlan("p1", new TestTrackable("T1", 5), a),
                closedPlan("p2", new TestTrackable("T2", 5), b),
                closedPlan("p3", new TestTrackable("T3", 5), c))));
        List<TestPlan> second = merge(calculation, assignment).apply(new ArrayList<>(List.of(
                closedPlan("p1", new TestTrackable("T1", 5), a),
                closedPlan("p2", new TestTrackable("T2", 5), b),
                closedPlan("p3", new TestTrackable("T3", 5), c))));

        assertThat(first).isEqualTo(second);
    }

    @Test
    void aCancelledMergeAbortsInsteadOfMerging() {
        // The merge scan never checked the cancel flag, so a cancelled merge still ran to completion.
        MatrixCostCalculation calculation = calculation();
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("T1", 5), new TestTrackable("T2", 5)));
        List<TestPlan> plans = new ArrayList<>(List.of(
                closedPlan("p1", new TestTrackable("T1", 5), a), closedPlan("p2", new TestTrackable("T2", 5), b)));
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);
        TestMerge merge = merge(calculation, assignment);

        merge.cancel();

        assertThatThrownBy(() -> merge.apply(plans)).isInstanceOf(CancelException.class);
        assertThat(plans).isEqualTo(snapshot);
    }

    @Test
    void interruptPropagatesToTheNestedLocalSearch() {
        MatrixCostCalculation calculation = calculation();
        CancelSpyLocalSearch localSearch = new CancelSpyLocalSearch();
        TestMerge merge = new TestMerge(calculation, new TestAssignment(List.of(new TestTrackable("T1", 5))),
                localSearch);

        merge.interrupt();

        assertThat(localSearch.isInterrupted()).isTrue();
    }

    @Test
    void cancelPropagatesToTheNestedLocalSearch() {
        MatrixCostCalculation calculation = calculation();
        CancelSpyLocalSearch localSearch = new CancelSpyLocalSearch();
        TestMerge merge = new TestMerge(calculation, new TestAssignment(List.of(new TestTrackable("T1", 5))),
                localSearch);

        merge.cancel();

        assertThat(localSearch.isCancelled()).isTrue();
    }

}
