package com.github.liliumsoftware.optimization.split;

import com.github.liliumsoftware.optimization.OptimizationType;
import com.github.liliumsoftware.optimization.optimization.ExchangeRelocateType;
import com.github.liliumsoftware.optimization.support.AlwaysFeasibleChecker;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.PlanInvariants;
import com.github.liliumsoftware.optimization.support.RecostingLocalSearch;
import com.github.liliumsoftware.optimization.support.TestAssignment;
import com.github.liliumsoftware.optimization.support.TestInfiniteAssignment;
import com.github.liliumsoftware.optimization.support.TestInsertion;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestRelocate;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the two phases that let zone groups run in parallel against one fleet: a parallel phase in which every group is
 * limited to the units its own plans hold, then an ordered phase in which the groups may take the idle units in input
 * order.
 */
class ZoneGroupsTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode z1a = TestNode.customer("A", "Z1", 10, 0, 1);
    private final TestNode z1b = TestNode.customer("B", "Z1", 20, 0, 1);
    private final TestNode z2c = TestNode.customer("C", "Z2", 0, 10, 1);
    private final TestNode z2d = TestNode.customer("D", "Z2", 0, 20, 1);

    private static TestPlan plan(String code, String zone, TestNode origin, TestNode... customers) {
        List<TestNode> nodes = new ArrayList<>(List.of(origin));
        nodes.addAll(List.of(customers));
        return new TestPlan(code, nodes, zone);
    }

    private MatrixCostCalculation calculation() {
        MatrixCostCalculation calculation = new MatrixCostCalculation(OptimizationType.LOCATION_ZONE,
                Map.of("Z1", List.of("Z1"), "Z2", List.of("Z2")));
        calculation.initializeCostMatrix(List.of(origin, z1a, z1b, z2c, z2d));
        return calculation;
    }

    @Test
    void inTheParallelPhaseAGroupReachesOnlyTheUnitsItsOwnPlansHold() {
        // X is held by Z1, Y by Z2, Z is idle. Each group asks for a second unit: in phase 1 neither may have one (Y
        // belongs to the other group, Z is idle), so both wanted the idle unit. In phase 2 the first group in input
        // order gets it; then no unit is idle and the second group is not run again.
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("X", 5), new TestTrackable("Y", 5),
                new TestTrackable("Z", 5)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p1", "Z1", origin, z1a), plan("p2", "Z2", origin, z2c)));
        assertThat(assignment.assign(plans)).isTrue();
        List<String> calls = Collections.synchronizedList(new ArrayList<>());

        List<TestPlan> result = ZoneGroups.apply(calculation(), assignment, 2, plans, (others, group) -> {
            String zone = group.getFirst().getZone();
            List<TestPlan> candidate = new ArrayList<>(group.stream().map(TestPlan::copy).toList());
            candidate.add(plan(zone + "-extra", zone, origin, zone.equals("Z1") ? z1b : z2d));
            boolean assigned = assignment.assign(others, candidate);
            calls.add(zone + "=" + assigned);
            return assigned ? candidate : group;
        });

        assertThat(calls.subList(0, 2)).containsExactlyInAnyOrder("Z1=false", "Z2=false");
        assertThat(calls.subList(2, calls.size())).containsExactly("Z1=true");
        assertThat(result).extracting(TestPlan::getCode).containsExactly("p1", "Z1-extra", "p2");
        PlanInvariants.assertEveryPlanHasTrackable(result);
        PlanInvariants.assertTrackablesAreDistinct(result);
    }

    @Test
    void onlyAGroupThatWantedAnIdleUnitIsRunAgain() {
        // Z1 asks for a second unit and is refused in phase 1; Z2 is content with the unit it holds. Phase 2 runs Z1
        // alone: running Z2 again would only repeat its phase-1 result.
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("X", 5), new TestTrackable("Y", 5),
                new TestTrackable("Z", 5)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p1", "Z1", origin, z1a), plan("p2", "Z2", origin, z2c)));
        assertThat(assignment.assign(plans)).isTrue();
        List<String> calls = Collections.synchronizedList(new ArrayList<>());

        List<TestPlan> result = ZoneGroups.apply(calculation(), assignment, 2, plans, (others, group) -> {
            String zone = group.getFirst().getZone();
            List<TestPlan> candidate = new ArrayList<>(group.stream().map(TestPlan::copy).toList());
            if (zone.equals("Z1")) {
                candidate.add(plan("Z1-extra", zone, origin, z1b));
            }
            boolean assigned = assignment.assign(others, candidate);
            calls.add(zone + "=" + assigned);
            return assigned ? candidate : group;
        });

        assertThat(calls.subList(0, 2)).containsExactlyInAnyOrder("Z1=false", "Z2=true");
        assertThat(calls.subList(2, calls.size())).containsExactly("Z1=true");
        assertThat(result).extracting(TestPlan::getCode).containsExactly("p1", "Z1-extra", "p2");
        PlanInvariants.assertTrackablesAreDistinct(result);
    }

    @Test
    void theParallelPhaseSeesTheOtherGroupsAsAReadOnlySnapshot() {
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("X", 5), new TestTrackable("Y", 5)));
        TestPlan p1 = plan("p1", "Z1", origin, z1a);
        TestPlan p2 = plan("p2", "Z2", origin, z2c);
        List<TestPlan> plans = new ArrayList<>(List.of(p1, p2));
        assertThat(assignment.assign(plans)).isTrue();

        ZoneGroups.apply(calculation(), assignment, 2, plans, (others, group) -> {
            assertThat(others).containsExactly(group.getFirst() == p1 ? p2 : p1);
            assertThatThrownBy(() -> others.add(p1)).isInstanceOf(UnsupportedOperationException.class);
            return group;
        });
    }

    @Test
    void theOrderedPhaseIsSkippedWhenNoUnitIsIdleOrUnitsAreShared() {
        AtomicInteger calls = new AtomicInteger();
        TestAssignment busy = new TestAssignment(List.of(new TestTrackable("X", 5), new TestTrackable("Y", 5)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p1", "Z1", origin, z1a), plan("p2", "Z2", origin, z2c)));
        assertThat(busy.assign(plans)).isTrue();

        ZoneGroups.apply(calculation(), busy, 2, plans, (others, group) -> {
            calls.incrementAndGet();
            return group;
        });
        assertThat(calls).as("every unit is held").hasValue(2);

        TestInfiniteAssignment unlimited = new TestInfiniteAssignment(List.of(new TestTrackable("X", 5),
                new TestTrackable("Y", 9)));
        calls.set(0);
        List<TestPlan> unassigned = new ArrayList<>(List.of(plan("q1", "Z1", origin, z1a),
                plan("q2", "Z2", origin, z2c)));
        List<TestPlan> result = ZoneGroups.apply(calculation(), unlimited, 2, unassigned, (others, group) -> {
            calls.incrementAndGet();
            assertThat(unlimited.assign(others, group)).isTrue();
            return group;
        });
        assertThat(calls).as("one unit may back every plan, an idle Y changes nothing").hasValue(2);
        assertThat(result).extracting(p -> p.getTrackable().toString()).containsExactly("X#0", "X#0");
    }

    @Test
    void groupsComeBackInInputOrderAndASingleGroupRunsDirectly() {
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("X", 5)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("c", "Z2", origin, z2c), plan("a", "Z1", origin, z1a),
                plan("d", "Z2", origin, z2d), plan("b", "Z1", origin, z1b)));

        assertThat(ZoneGroups.apply(calculation(), assignment, 4, plans, (others, group) -> group))
                .extracting(TestPlan::getCode).containsExactly("c", "d", "a", "b");
        assertThat(ZoneGroups.apply(calculation(), assignment, 1, plans, (others, group) -> {
            assertThat(others).isEmpty();
            return group;
        })).extracting(TestPlan::getCode).containsExactly("c", "a", "d", "b");
        assertThat(ZoneGroups.apply(calculation(), assignment, 3, new ArrayList<>(), (others, group) -> group))
                .isEmpty();
    }

    @Test
    void zoneGroupsOptimizeToTheSameResultOnEveryRun() {
        // Four isolated zones, plans chunked badly in input order, a fleet with idle units every group would like.
        // Whatever the thread timing, the routes and the units behind them must be identical run after run.
        Random random = new Random(42);
        List<TestNode> nodes = new ArrayList<>(List.of(origin));
        Map<String, List<String>> neighbours = new HashMap<>();
        List<TestPlan> plans = new ArrayList<>();
        for (int zone = 0; zone < 4; zone++) {
            String code = "Z" + zone;
            neighbours.put(code, List.of(code));
            List<TestNode> customers = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                customers.add(TestNode.customer(code + "-" + i, code, random.nextInt(200) - 100,
                        random.nextInt(200) - 100, 1 + random.nextInt(2)));
            }
            nodes.addAll(customers);
            for (int chunk = 0; chunk < 3; chunk++) {
                List<TestNode> route = new ArrayList<>(List.of(origin));
                route.addAll(customers.subList(chunk * 3, chunk * 3 + 3));
                plans.add(new TestPlan(code + "-p" + chunk, route, code));
            }
        }
        MatrixCostCalculation calculation = new MatrixCostCalculation(OptimizationType.LOCATION_ZONE, neighbours);
        calculation.initializeCostMatrix(nodes);
        List<TestTrackable> fleet = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            fleet.add(new TestTrackable("T" + i, 4 + i % 5));
        }

        List<String> first = null;
        for (int run = 0; run < 12; run++) {
            TestAssignment assignment = new TestAssignment(fleet);
            List<TestPlan> input = PlanInvariants.deepCopyMutable(plans);
            assertThat(assignment.assign(input)).isTrue();

            List<TestPlan> result = new TestRelocate(calculation, assignment, new RecostingLocalSearch(calculation),
                    new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), ExchangeRelocateType.SIMPLE, 6,
                    1).apply(input, 4);

            PlanInvariants.assertTrackablesAreDistinct(result);
            PlanInvariants.assertEveryPlanHasTrackable(result);
            PlanInvariants.assertCustomerCountWithinCapacity(result);
            PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, nodes.subList(1, nodes.size()));
            List<String> routes = result.stream().map(p -> p.getCode() + p.getNodes() + p.getTrackable()).toList();
            if (first == null) {
                first = routes;
            } else {
                assertThat(routes).as("run %d", run).isEqualTo(first);
            }
        }
    }

}
