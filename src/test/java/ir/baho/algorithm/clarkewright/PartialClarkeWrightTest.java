package ir.baho.algorithm.clarkewright;

import ir.baho.algorithm.OptimizationType;
import ir.baho.algorithm.exception.CancelException;
import ir.baho.algorithm.support.MatrixCostCalculation;
import ir.baho.algorithm.support.PlanInvariants;
import ir.baho.algorithm.support.RecostingLocalSearch;
import ir.baho.algorithm.support.TestAssignment;
import ir.baho.algorithm.support.TestNode;
import ir.baho.algorithm.support.TestPartialClarkeWright;
import ir.baho.algorithm.support.TestPlan;
import ir.baho.algorithm.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Partial Clarke-Wright consolidates work onto fewer resources: it frees a vehicle from a plan, hands that plan
 * to a spare, and re-plans the freed customers against the <em>remaining</em> pool only. Every assignment on
 * that path must therefore go through the {@code withTrackables} overloads — if it fell back to the full fleet
 * the "partial" restriction would be meaningless and the result could over-commit the fleet.
 *
 * <p>Its precondition is that the plans handed in are already resourced (it dereferences
 * {@code plan.getTrackable()}), so the fixtures assign before calling.
 */
class PartialClarkeWrightTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode a = TestNode.customer("A", 0, 10, 1);
    private final TestNode b = TestNode.customer("B", 0, 20, 1);
    private final TestNode c = TestNode.customer("C", 0, 30, 1);
    private final TestNode d = TestNode.customer("D", 0, 40, 1);

    private List<TestNode> customers() {
        return List.of(a, b, c, d);
    }

    private MatrixCostCalculation calculation() {
        List<TestNode> nodes = new ArrayList<>();
        nodes.add(origin);
        nodes.addAll(customers());
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(nodes);
        return calculation;
    }

    private TestPartialClarkeWright partial(MatrixCostCalculation calculation, TestAssignment assignment) {
        return new TestPartialClarkeWright(calculation, assignment, new RecostingLocalSearch(calculation),
                null, OptimizationType.NO_RESTRICTION);
    }

    @Test
    void rePlansFreedCustomersAgainstTheRemainingPool() {
        MatrixCostCalculation calculation = calculation();
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("T1", 4), new TestTrackable("T2", 4), new TestTrackable("T3", 4)));
        // One plan holds A and B; C and D are still unrouted.
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", List.of(origin, a, b))));
        assertThat(assignment.assign(plans)).isTrue();

        List<TestPlan> result = partial(calculation, assignment).apply(origin, customers(), plans);

        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, customers());
        PlanInvariants.assertEveryPlanHasTrackable(result);
        PlanInvariants.assertTrackablesAreDistinct(result);
        PlanInvariants.assertNodeLayout(result);
        PlanInvariants.assertCustomerCountWithinCapacity(result);
    }

    @Test
    void servedCustomersAreRecognisedByCodeWhenNodeCopiesAreNotEqualToTheirSource() {
        // The plans hold copies of the customers. A consumer whose node copy() is not equals to its source made every
        // served customer look unplanned, so the re-plan served them a second time.
        TestNode ia = a.withIdentityEquality();
        TestNode ib = b.withIdentityEquality();
        TestNode ic = c.withIdentityEquality();
        TestNode id = d.withIdentityEquality();
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, ia, ib, ic, id));
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("T1", 4), new TestTrackable("T2", 4), new TestTrackable("T3", 4)));
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", List.of(origin, ia, ib))));
        assertThat(assignment.assign(plans)).isTrue();

        List<TestPlan> result = partial(calculation, assignment).apply(origin, List.of(ia, ib, ic, id), plans);

        assertThat(result.stream().flatMap(p -> p.getCustomers().stream()).map(TestNode::getCode))
                .containsExactlyInAnyOrder("A", "B", "C", "D");
        PlanInvariants.assertTrackablesAreDistinct(result);
    }

    @Test
    void aPlanWithoutAResourceDoesNotBreakTheHandOver() {
        // plan(...) may return a previous plan that could not be re-assigned; handing that set to apply(...) used to
        // dereference the missing resource.
        MatrixCostCalculation calculation = calculation();
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 4), new TestTrackable("T2", 4)));
        TestPlan resourced = new TestPlan("p1", List.of(origin, a, b));
        TestPlan unresourced = new TestPlan("p2", List.of(origin, c));
        List<TestPlan> plans = new ArrayList<>(List.of(resourced, unresourced));
        assertThat(assignment.assign(List.of(resourced))).isTrue();

        List<TestPlan> result = partial(calculation, assignment).apply(origin, customers(), plans);

        assertThat(result.stream().flatMap(p -> p.getCustomers().stream()).map(TestNode::getCode))
                .containsExactlyInAnyOrder("A", "B", "C", "D");
    }

    @Test
    void withoutASpareResourceNothingIsRePlanned() {
        MatrixCostCalculation calculation = calculation();
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 4)));
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", List.of(origin, a, b))));
        assertThat(assignment.assign(plans)).isTrue();
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);

        // The only vehicle is already in use, so no plan can be handed over and the pool never shrinks.
        assertThat(partial(calculation, assignment).apply(origin, customers(), plans)).isEqualTo(snapshot);
    }

    @Test
    void theResultRespectsCapacityWhenTheRemainingPoolIsTight() {
        MatrixCostCalculation calculation = calculation();
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("T1", 2), new TestTrackable("T2", 2), new TestTrackable("T3", 2)));
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", List.of(origin, a, b))));
        assertThat(assignment.assign(plans)).isTrue();

        List<TestPlan> result = partial(calculation, assignment).apply(origin, customers(), plans);

        PlanInvariants.assertCustomerCountWithinCapacity(result);
        PlanInvariants.assertTrackablesAreDistinct(result);
        PlanInvariants.assertNoDuplicateCustomers(result);
    }

    @Test
    void cancelAbortsTheHandOversAndInterruptStopsThemKeepingThePlans() {
        MatrixCostCalculation calculation = calculation();
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("T1", 4), new TestTrackable("T2", 4), new TestTrackable("T3", 4)));
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", List.of(origin, a, b))));
        assertThat(assignment.assign(plans)).isTrue();
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);

        // The hand-over loop never polled either signal.
        TestPartialClarkeWright interrupted = partial(calculation, assignment);
        interrupted.interrupt();
        assertThat(interrupted.apply(origin, customers(), plans)).isEqualTo(snapshot);

        TestPartialClarkeWright cancelled = partial(calculation, assignment);
        cancelled.cancel();
        assertThatThrownBy(() -> cancelled.apply(origin, customers(), plans)).isInstanceOf(CancelException.class);
    }

    @Test
    void anUnassignedPlanAfterTheResourcedOnesDoesNotBreakTheHandOver() {
        // The hand-over compared every plan's trackable with equals on the plan side and threw NullPointerException
        // for a plan without one.
        MatrixCostCalculation calculation = calculation();
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("T1", 4), new TestTrackable("T2", 4), new TestTrackable("T3", 4)));
        TestPlan resourced = new TestPlan("p1", List.of(origin, a, b));
        List<TestPlan> plans = new ArrayList<>(List.of(resourced));
        assertThat(assignment.assign(plans)).isTrue();
        plans.add(new TestPlan("unassigned", List.of(origin, c)));

        List<TestPlan> result = partial(calculation, assignment).apply(origin, customers(), plans);

        PlanInvariants.assertNoDuplicateCustomers(result);
        assertThat(PlanInvariants.customersOf(result)).contains(a, b, c);
    }

    @Test
    void nothingToRePlanLeavesThePlanSetAlone() {
        MatrixCostCalculation calculation = calculation();
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("T1", 9), new TestTrackable("T2", 9)));
        // Every customer is already routed, so there is nothing left for the restricted pool to plan.
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", List.of(origin, a, b, c, d))));
        assertThat(assignment.assign(plans)).isTrue();
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);

        assertThat(partial(calculation, assignment).apply(origin, customers(), plans)).isEqualTo(snapshot);
    }

    @Test
    void mergesOpenPlansWithoutADestinationNode() {
        // Same regression as the base algorithm: the merge core must not append a null destination node.
        MatrixCostCalculation calculation = calculation();
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 9)));
        TestPartialClarkeWright partial = partial(calculation, assignment);

        List<TestPlan> plans = partial.plan(origin, customers());

        assertThat(plans).isNotEmpty();
        assertThat(plans).allSatisfy(plan -> assertThat(plan.getNodes()).doesNotContainNull());
        PlanInvariants.assertNodeLayout(plans);
    }

    @Test
    void terminatesRatherThanRestartingForever() {
        MatrixCostCalculation calculation = calculation();
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("T1", 4), new TestTrackable("T2", 4),
                new TestTrackable("T3", 4), new TestTrackable("T4", 4)));
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", List.of(origin, a))));
        assertThat(assignment.assign(plans)).isTrue();

        // The outer scan restarts from the top on every accepted hand-over (i = -1), so termination depends on
        // the pool shrinking; a generous ceiling catches a genuine runaway without timing sensitivity.
        List<TestPlan> result = assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> partial(calculation, assignment).apply(origin, customers(), plans));

        PlanInvariants.assertNoDuplicateCustomers(result);
    }

    @Test
    void isDeterministic() {
        MatrixCostCalculation calculation = calculation();
        List<TestTrackable> fleet = List.of(new TestTrackable("T1", 4),
                new TestTrackable("T2", 4), new TestTrackable("T3", 4));

        TestAssignment firstAssignment = new TestAssignment(fleet);
        List<TestPlan> firstPlans = new ArrayList<>(List.of(new TestPlan("p1", List.of(origin, a, b))));
        firstAssignment.assign(firstPlans);
        TestAssignment secondAssignment = new TestAssignment(fleet);
        List<TestPlan> secondPlans = new ArrayList<>(List.of(new TestPlan("p1", List.of(origin, a, b))));
        secondAssignment.assign(secondPlans);

        assertThat(partial(calculation, firstAssignment).apply(origin, customers(), firstPlans))
                .isEqualTo(partial(calculation, secondAssignment).apply(origin, customers(), secondPlans));
    }

}
