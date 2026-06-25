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

package com.github.liliumsoftware.optimization.cluster;

import com.github.liliumsoftware.optimization.OptimizationType;
import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.domain.TrackableCategory;
import com.github.liliumsoftware.optimization.support.AlwaysFeasibleChecker;
import com.github.liliumsoftware.optimization.support.CapacityRules;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.PlanInvariants;
import com.github.liliumsoftware.optimization.support.TestAssignment;
import com.github.liliumsoftware.optimization.support.TestInsertion;
import com.github.liliumsoftware.optimization.support.TestKMedoids;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * K-medoids re-seeds plans around their most central customer. The cluster count is not chosen by the
 * algorithm — it is fixed by the number of input plans — and the loop stops when the medoid set stops moving,
 * so the properties worth pinning are: at most one plan per input plan, geographically coherent grouping when
 * the clusters are obvious, and termination.
 */
class KMedoidsAlgorithmTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);
    // Two clearly separated clusters, so the "right" grouping is not a matter of taste.
    private final TestNode west1 = TestNode.customer("W1", -100, 0, 1);
    private final TestNode west2 = TestNode.customer("W2", -110, 5, 1);
    private final TestNode west3 = TestNode.customer("W3", -105, -5, 1);
    private final TestNode east1 = TestNode.customer("E1", 100, 0, 1);
    private final TestNode east2 = TestNode.customer("E2", 110, 5, 1);
    private final TestNode east3 = TestNode.customer("E3", 105, -5, 1);

    private List<TestNode> allCustomers() {
        return List.of(west1, west2, west3, east1, east2, east3);
    }

    private MatrixCostCalculation calculation() {
        List<TestNode> nodes = new ArrayList<>();
        nodes.add(origin);
        nodes.addAll(allCustomers());
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(nodes);
        return calculation;
    }

    private TestKMedoids kMedoids(MatrixCostCalculation calculation, int fleetSize, int capacity) {
        List<TestTrackable> fleet = new ArrayList<>();
        for (int i = 0; i < fleetSize; i++) {
            fleet.add(new TestTrackable("T" + i, capacity));
        }
        return new TestKMedoids(calculation, new TestAssignment(fleet),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false));
    }

    private TestPlan plan(String code, TestNode... customers) {
        List<TestNode> nodes = new ArrayList<>();
        nodes.add(origin);
        nodes.addAll(List.of(customers));
        return new TestPlan(code, nodes);
    }

    @Test
    void regroupsMixedPlansIntoGeographicClusters() {
        MatrixCostCalculation calculation = calculation();
        // Both input plans straddle the two clusters — the arrangement clustering should undo.
        List<TestPlan> plans = new ArrayList<>(List.of(
                plan("mixed1", west1, west2, east1),
                plan("mixed2", east2, east3, west3)));

        List<TestPlan> result = kMedoids(calculation, 2, 9).apply(origin, List.of(), plans);

        assertThat(result).hasSizeLessThanOrEqualTo(2);
        PlanInvariants.assertNoDuplicateCustomers(result);
        PlanInvariants.assertNodeLayout(result);
        assertThat(result).allSatisfy(plan -> assertThat(plan.getCustomers().stream()
                .map(customer -> customer.getX() < 0).distinct())
                .as("plan %s should not straddle both clusters", plan.getCode()).hasSize(1));
    }

    @Test
    void theClusterCountIsFixedByTheNumberOfInputPlans() {
        MatrixCostCalculation calculation = calculation();
        List<TestPlan> threePlans = new ArrayList<>(List.of(
                plan("p1", west1, west2), plan("p2", east1, east2), plan("p3", west3, east3)));

        List<TestPlan> result = kMedoids(calculation, 3, 9).apply(origin, List.of(), threePlans);

        assertThat(result).as("never more clusters than seeds").hasSizeLessThanOrEqualTo(3);
        PlanInvariants.assertNoDuplicateCustomers(result);
    }

    @Test
    void unplannedCustomersAreOfferedToTheClusters() {
        MatrixCostCalculation calculation = calculation();
        List<TestPlan> plans = new ArrayList<>(List.of(
                plan("west", west1, west2), plan("east", east1, east2)));

        List<TestPlan> result = kMedoids(calculation, 2, 9).apply(origin, List.of(west3, east3), plans);

        PlanInvariants.assertNoDuplicateCustomers(result);
        assertThat(PlanInvariants.customersOf(result))
                .as("the previously unrouted customers were considered for insertion")
                .contains(west3, east3);
    }

    @Test
    void aFleetTooSmallLimitsTheNumberOfClusters() {
        MatrixCostCalculation calculation = calculation();
        List<TestPlan> plans = new ArrayList<>(List.of(
                plan("west", west1, west2), plan("east", east1, east2)));

        // Only one vehicle exists, so only one seed plan can be resourced.
        List<TestPlan> result = kMedoids(calculation, 1, 9).apply(origin, List.of(), plans);

        assertThat(result).hasSize(1);
        PlanInvariants.assertEveryPlanHasTrackable(result);
        PlanInvariants.assertNoDuplicateCustomers(result);
    }

    @Test
    void interruptStopsReseedingAndReachesTheInsertion() {
        MatrixCostCalculation calculation = calculation();
        List<TestPlan> plans = new ArrayList<>(List.of(plan("mixed1", west1, east1), plan("mixed2", west2, east2)));
        AtomicBoolean insertionInterrupted = new AtomicBoolean();
        TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), false) {
            @Override
            public void interrupt() {
                insertionInterrupted.set(true);
                super.interrupt();
            }
        };
        TestKMedoids kMedoids = new TestKMedoids(calculation, new TestAssignment(List.of(new TestTrackable("T0", 9),
                new TestTrackable("T1", 9))), insertion);

        kMedoids.interrupt();

        assertThat(kMedoids.apply(origin, List.of(), plans)).as("the input is returned untouched").isSameAs(plans);
        assertThat(insertionInterrupted).isTrue();
    }

    @Test
    void aSeedNoResourceAcceptsDoesNotDropTheSeedsAfterIt() {
        // The west medoid is refused by every vehicle. The east seed after it must still be planned, and the other
        // west customers (no longer tied to a seed) are absorbed by the east cluster instead of being lost.
        MatrixCostCalculation calculation = calculation();
        TestPlan west = plan("west", west1, west2, west3);
        TestPlan east = plan("east", east1, east2, east3);
        TestNode westMedoid = calculation.getMedoid(west);
        TestKMedoids kMedoids = new TestKMedoids(calculation, new RefusingAssignment(westMedoid,
                List.of(new TestTrackable("T0", 9), new TestTrackable("T1", 9))),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false));

        List<TestPlan> result = kMedoids.apply(origin, List.of(), new ArrayList<>(List.of(west, east)));

        assertThat(result).hasSize(1);
        PlanInvariants.assertEveryPlanHasTrackable(result);
        PlanInvariants.assertNoDuplicateCustomers(result);
        assertThat(result.getFirst().getCustomers()).contains(east1, east2, east3).doesNotContain(westMedoid)
                .hasSize(5);
    }

    @Test
    void capacityLimitsHowMuchAClusterAbsorbs() {
        MatrixCostCalculation calculation = calculation();
        List<TestPlan> plans = new ArrayList<>(List.of(
                plan("west", west1, west2, west3), plan("east", east1, east2, east3)));

        List<TestPlan> result = kMedoids(calculation, 2, 2).apply(origin, List.of(), plans);

        PlanInvariants.assertCustomerCountWithinCapacity(result);
        PlanInvariants.assertNoDuplicateCustomers(result);
    }

    @Test
    void terminatesWhenTheMedoidSetStopsMoving() {
        MatrixCostCalculation calculation = calculation();
        List<TestPlan> plans = new ArrayList<>(List.of(
                plan("mixed1", west1, east1, west2), plan("mixed2", east2, west3, east3)));

        List<TestPlan> result = assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> kMedoids(calculation, 2, 9).apply(origin, List.of(), plans));

        PlanInvariants.assertNoDuplicateCustomers(result);
    }

    @Test
    void stopsOnlyOnceTheMedoidSetNoLongerChanges() {
        // One vehicle for two seeds: the second seed is refused and every customer joins the first plan, whose new
        // medoid C1 differs from both seeds. The stop check only asked whether the new medoids were a subset of an
        // earlier set, so it stopped there; re-seeding around C1 gives a different plan, so that was no fixed point.
        TestNode c0 = TestNode.customer("C0", 99, 21, 1);
        TestNode c1 = TestNode.customer("C1", -10, -10, 1);
        TestNode c2 = TestNode.customer("C2", 3, -89, 1);
        TestNode c3 = TestNode.customer("C3", 23, 62, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, c0, c1, c2, c3));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p0", c0, c2), plan("p1", c1, c3)));

        List<TestPlan> result = kMedoids(calculation, 1, 4).apply(origin, List.of(), plans);

        assertThat(result).hasSize(1);
        assertThat(kMedoids(calculation, 1, 4).apply(origin, List.of(),
                new ArrayList<>(PlanInvariants.deepCopy(result))))
                .as("one more round changes nothing").isEqualTo(result);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(c0, c1, c2, c3));
    }

    @Test
    void aCustomerEquallyCloseToTwoMedoidsJoinsTheEarlierSeed() {
        // The per-node medoid costs were collected in a HashMap, so between equally close medoids the hash order
        // decided, not the seed order the surrounding code documents.
        TestNode west = TestNode.customer("W", -10, 0, 1);
        TestNode east = TestNode.customer("E", 10, 0, 1);
        TestNode middle = TestNode.customer("X", 0, 5, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, west, east, middle));

        List<TestPlan> westFirst = kMedoids(calculation, 2, 9).apply(origin, List.of(middle),
                new ArrayList<>(List.of(plan("w", west), plan("e", east))));
        List<TestPlan> eastFirst = kMedoids(calculation, 2, 9).apply(origin, List.of(middle),
                new ArrayList<>(List.of(plan("e", east), plan("w", west))));

        assertThat(westFirst.getFirst().getCustomers()).containsExactlyInAnyOrder(west, middle);
        assertThat(eastFirst.getFirst().getCustomers()).containsExactlyInAnyOrder(east, middle);
    }

    @Test
    void customersSharingACodeAreRejected() {
        // An unplanned customer that is already on a plan (or listed twice) used to be inserted a second time.
        MatrixCostCalculation calculation = calculation();
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p1", west1, west2), plan("p2", east1)));

        assertThatThrownBy(() -> kMedoids(calculation, 2, 9).apply(origin, List.of(west1.copy()), plans))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate customer code: W1");
        assertThatThrownBy(() -> kMedoids(calculation, 2, 9).apply(origin, List.of(east2, east2.copy()), plans))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("E2");
    }

    @Test
    void isDeterministic() {
        MatrixCostCalculation calculation = calculation();

        List<TestPlan> first = kMedoids(calculation, 2, 9).apply(origin, List.of(),
                new ArrayList<>(List.of(plan("p1", west1, west2, east1), plan("p2", east2, east3, west3))));
        List<TestPlan> second = kMedoids(calculation, 2, 9).apply(origin, List.of(),
                new ArrayList<>(List.of(plan("p1", west1, west2, east1), plan("p2", east2, east3, west3))));

        assertThat(first).isEqualTo(second);
    }

    @Test
    void aSingleSeedCollapsesEverythingIntoOneCluster() {
        MatrixCostCalculation calculation = calculation();
        List<TestPlan> plans = new ArrayList<>(List.of(plan("only", west1, west2, east1)));

        List<TestPlan> result = kMedoids(calculation, 1, 9).apply(origin, List.of(), plans);

        assertThat(result).hasSize(1);
        PlanInvariants.assertNoDuplicateCustomers(result);
    }

    @Test
    void aCustomerWithNoReachableMedoidIsLeftUnplannedNotDuplicated() {
        // Documented contract: under LOCATION_ZONE a customer whose zone neighbours no medoid's zone cannot join any
        // plan, so it is absent from the result; the caller recovers it via getUnplannedNodes.
        TestNode a = TestNode.customer("A", "Z1", -100, 0, 1);
        TestNode b = TestNode.customer("B", "Z1", -110, 0, 1);
        TestNode isolated = TestNode.customer("X", "Z9", 100, 0, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation(OptimizationType.LOCATION_ZONE, Map.of("Z1",
                List.of("Z1")));
        calculation.initializeCostMatrix(List.of(origin, a, b, isolated));
        TestKMedoids kMedoids = new TestKMedoids(calculation, new TestAssignment(List.of(new TestTrackable("T", 9))),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p", a, b)));

        List<TestPlan> result = kMedoids.apply(origin, List.of(isolated), plans);

        PlanInvariants.assertNoDuplicateCustomers(result);
        assertThat(PlanInvariants.customersOf(result)).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void aPlanWithoutCustomersIsRejectedBecauseItHasNoMedoid() {
        MatrixCostCalculation calculation = calculation();
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("empty", List.of(origin))));

        assertThatThrownBy(() -> kMedoids(calculation, 1, 9).apply(origin, List.of(), plans))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no customers");
    }

    /**
     * Capacity assignment that refuses any plan containing one particular customer.
     */
    private static final class RefusingAssignment extends TrackableAssignment<TestPlan, TestTrackable> {

        private final TestNode refused;

        RefusingAssignment(TestNode refused, List<TestTrackable> fleet) {
            super(fleet);
            this.refused = refused;
        }

        @Override
        protected boolean assign(TestPlan plan, TestTrackable trackable) {
            if (plan.getCustomers().contains(refused)) {
                plan.setTrackable(null);
                return false;
            }
            return CapacityRules.assign(plan, trackable);
        }

        @Override
        protected boolean assign(TestPlan plan, List<TestTrackable> trackables) {
            if (plan.getCustomers().contains(refused)) {
                plan.setTrackable(null);
                return false;
            }
            return CapacityRules.assign(plan, trackables);
        }

        @Override
        protected Comparator<TestTrackable> comparator(TestPlan plan) {
            return CapacityRules.comparator();
        }

        @Override
        protected TrackableCategory getCategory(TestTrackable trackable) {
            return CapacityRules.category(trackable);
        }

    }

}
