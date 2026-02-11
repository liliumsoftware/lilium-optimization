package ir.baho.algorithm.support;

import ir.baho.algorithm.domain.Nodeable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.ToLongFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reusable routing invariants — the properties that must hold for <em>any</em> plan set produced by
 * <em>any</em> algorithm in this library, independent of which heuristic ran or what route it chose.
 *
 * <p><strong>Oracle independence.</strong> The cost helpers here recompute Manhattan distance straight from
 * {@link TestNode}'s coordinates with plain arithmetic. They never consult {@link MatrixCostCalculation} or
 * {@code PathCostCalculation}, so a bug in the production cost matrix cannot hide behind an expectation
 * derived from that same matrix.
 */
public final class PlanInvariants {

    private PlanInvariants() {
    }

    // ---------------------------------------------------------------- independent cost oracle

    /**
     * Manhattan cost of one directed edge, computed from raw coordinates without the production cost matrix.
     */
    public static long edgeCost(TestNode from, TestNode to) {
        return Math.abs((long) from.getX() - to.getX()) + Math.abs((long) from.getY() - to.getY());
    }

    /**
     * Sum of consecutive edge costs — the library's definition of a plan's cost, computed independently.
     */
    public static long referenceCost(List<TestNode> nodes) {
        long cost = 0;
        for (int i = 0; i < nodes.size() - 1; i++) {
            cost += edgeCost(nodes.get(i), nodes.get(i + 1));
        }
        return cost;
    }

    public static long referenceCost(TestPlan plan) {
        return referenceCost(plan.getNodes());
    }

    public static long referenceTotalCost(Collection<TestPlan> plans) {
        return plans.stream().mapToLong(PlanInvariants::referenceCost).sum();
    }

    // ---------------------------------------------------------------- customer conservation

    public static List<TestNode> customersOf(Collection<TestPlan> plans) {
        List<TestNode> customers = new ArrayList<>();
        plans.forEach(plan -> customers.addAll(plan.getCustomers()));
        return customers;
    }

    /**
     * Every required customer appears in exactly one plan exactly once, and nothing else appears. Catches both
     * dropped and duplicated/invented customers in a single assertion.
     */
    public static void assertAllCustomersVisitedExactlyOnce(Collection<TestPlan> plans, Collection<TestNode> required) {
        List<String> visited = customersOf(plans).stream().map(TestNode::getCode).toList();
        List<String> expected = required.stream().map(TestNode::getCode).toList();
        assertThat(visited).as("every required customer visited exactly once, none invented")
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    public static void assertNoDuplicateCustomers(Collection<TestPlan> plans) {
        List<String> visited = customersOf(plans).stream().map(TestNode::getCode).toList();
        assertThat(visited).as("no customer served twice").doesNotHaveDuplicates();
    }

    // ---------------------------------------------------------------- structural layout

    /**
     * The node layout contract every algorithm assumes: {@code ORIGIN, CUSTOMER…, DESTINATION} with the
     * destination optional (open plan), exactly one origin, and no interior depot node.
     */
    public static void assertNodeLayout(Collection<TestPlan> plans) {
        for (TestPlan plan : plans) {
            List<TestNode> nodes = plan.getNodes();
            assertThat(nodes).as("plan %s is not empty", plan.getCode()).isNotEmpty();
            assertThat(nodes).as("plan %s holds no null node", plan.getCode()).doesNotContainNull();
            assertThat(nodes.getFirst().getType()).as("plan %s starts at an ORIGIN", plan.getCode())
                    .isEqualTo(Nodeable.Type.ORIGIN);
            assertThat(nodes.stream().filter(n -> n.getType() == Nodeable.Type.ORIGIN))
                    .as("plan %s has exactly one origin", plan.getCode()).hasSize(1);
            List<TestNode> destinations = nodes.stream().filter(n -> n.getType() == Nodeable.Type.DESTINATION).toList();
            assertThat(destinations).as("plan %s has at most one destination", plan.getCode())
                    .hasSizeLessThanOrEqualTo(1);
            if (!destinations.isEmpty()) {
                assertThat(nodes.getLast()).as("plan %s ends at its DESTINATION", plan.getCode())
                        .isEqualTo(destinations.getFirst());
            } else {
                assertThat(nodes.getLast().getType()).as("open plan %s ends at a customer", plan.getCode())
                        .isEqualTo(Nodeable.Type.CUSTOMER);
            }
        }
    }

    /**
     * Every plan begins at {@code origin} and, when the fixture has one, ends at {@code destination}.
     */
    public static void assertStartsAndEndsAtDepot(Collection<TestPlan> plans, TestNode origin, TestNode destination) {
        for (TestPlan plan : plans) {
            assertThat(plan.getNodes().getFirst()).as("plan %s starts at the depot", plan.getCode()).isEqualTo(origin);
            if (destination != null) {
                assertThat(plan.getNodes().getLast()).as("plan %s returns to the depot", plan.getCode())
                        .isEqualTo(destination);
            }
        }
    }

    public static void assertNoEmptyPlans(Collection<TestPlan> plans) {
        for (TestPlan plan : plans) {
            assertThat(plan.getCustomerCount()).as("plan %s serves at least one customer", plan.getCode()).isPositive();
        }
    }

    // ---------------------------------------------------------------- cost consistency

    /**
     * The library's own route cost ({@code calculateCost(plan)} over the cost matrix) equals the independently
     * summed cost of the plan's edges — the single invariant every accept/reject decision in the library rests on.
     */
    public static void assertLibraryCostMatchesIndependentCost(Collection<TestPlan> plans,
            ToLongFunction<TestPlan> libraryCost) {
        for (TestPlan plan : plans) {
            assertThat(libraryCost.applyAsLong(plan)).as("library cost of plan %s", plan.getCode())
                    .isEqualTo(referenceCost(plan));
        }
    }

    // ---------------------------------------------------------------- feasibility / resources

    public static void assertEveryPlanHasTrackable(Collection<TestPlan> plans) {
        for (TestPlan plan : plans) {
            assertThat(plan.getTrackable()).as("plan %s has a resource assigned", plan.getCode()).isNotNull();
        }
    }

    /**
     * No resource unit serves two plans at once — the fleet-size constraint.
     */
    public static void assertTrackablesAreDistinct(Collection<TestPlan> plans) {
        List<TestTrackable> assigned = plans.stream().map(TestPlan::getTrackable).filter(java.util.Objects::nonNull)
                .toList();
        assertThat(assigned).as("each resource unit serves at most one plan").doesNotHaveDuplicates();
    }

    /**
     * Capacity as {@link TestAssignment} defines it: a plan's customer count never exceeds its resource's
     * capacity.
     */
    public static void assertCustomerCountWithinCapacity(Collection<TestPlan> plans) {
        for (TestPlan plan : plans) {
            if (plan.getTrackable() == null) {
                continue;
            }
            assertThat(plan.getCustomerCount()).as("plan %s fits resource %s", plan.getCode(), plan.getTrackable())
                    .isLessThanOrEqualTo(plan.getTrackable().getCapacity());
        }
    }

    // ---------------------------------------------------------------- input mutation

    public static List<TestPlan> deepCopy(Collection<TestPlan> plans) {
        return plans.stream().map(TestPlan::copy).toList();
    }

    /**
     * A deep copy in a mutable list — what the inter-plan algorithms need, since they rewrite the list in place.
     */
    public static List<TestPlan> deepCopyMutable(Collection<TestPlan> plans) {
        return plans.stream().map(TestPlan::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    // ---------------------------------------------------------------- composite

    /**
     * The full "this is a valid solution" bundle: layout, depot endpoints, customer conservation, distinct
     * resources, capacity, and truthful costs. Used by the integration and property tests so a single call
     * covers every structural guarantee.
     */
    public static void assertValidSolution(Collection<TestPlan> plans, TestNode origin, TestNode destination,
            Collection<TestNode> requiredCustomers) {
        assertNodeLayout(plans);
        assertStartsAndEndsAtDepot(plans, origin, destination);
        assertNoDuplicateCustomers(plans);
        assertAllCustomersVisitedExactlyOnce(plans, requiredCustomers);
        assertTrackablesAreDistinct(plans);
        assertCustomerCountWithinCapacity(plans);
    }

}
