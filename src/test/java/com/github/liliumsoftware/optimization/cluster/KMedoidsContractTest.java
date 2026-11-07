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

import com.github.liliumsoftware.optimization.exception.CancelException;
import com.github.liliumsoftware.optimization.feasibility.FeasibilityChecker;
import com.github.liliumsoftware.optimization.support.AlwaysFeasibleChecker;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.MaxCustomersFeasibilityChecker;
import com.github.liliumsoftware.optimization.support.PlanInvariants;
import com.github.liliumsoftware.optimization.support.TestAssignment;
import com.github.liliumsoftware.optimization.support.TestInsertion;
import com.github.liliumsoftware.optimization.support.TestKMedoids;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins what {@link AbstractKMedoidsAlgorithm} guarantees beyond termination: a customer refused by its nearest
 * cluster falls back to the next nearest one, an interrupt during the first round returns that round's plans, a cancel
 * reaches the nested insertion, and on any input no customer is duplicated or invented.
 */
class KMedoidsContractTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode west1 = TestNode.customer("W1", -100, 0, 1);
    private final TestNode west2 = TestNode.customer("W2", -110, 5, 1);
    private final TestNode west3 = TestNode.customer("W3", -105, -5, 1);
    private final TestNode east1 = TestNode.customer("E1", 100, 0, 1);

    private static MatrixCostCalculation calculation(List<TestNode> nodes) {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(nodes);
        return calculation;
    }

    private static List<String> clusters(List<TestPlan> plans) {
        return plans.stream().map(p -> p.getCode() + ":" + p.getCustomers().stream().map(TestNode::getCode)
                .collect(Collectors.joining("-"))).toList();
    }

    private static TestAssignment fleet(int units, int capacity) {
        return new TestAssignment(List.of(new TestTrackable("T", capacity, units, false)));
    }

    private TestPlan plan(String code, TestNode... customers) {
        List<TestNode> nodes = new ArrayList<>();
        nodes.add(origin);
        nodes.addAll(List.of(customers));
        return new TestPlan(code, nodes);
    }

    @Test
    void aCustomerRefusedByItsNearestClusterJoinsTheNextNearestOne() {
        // Medoids W1 (sum 15 + 10 = 25, ahead of W3's equal 25) and E1. W3 (10 from W1) joins W1 first; the insertion
        // refuses a third customer on a plan, so W2 (15 from W1) falls back to E1 (215 away) instead of being lost.
        // The next round finds the same medoids and stops.
        MatrixCostCalculation calculation = calculation(List.of(origin, west1, west2, west3, east1));
        TestKMedoids kMedoids = new TestKMedoids(calculation, fleet(2, 9),
                new TestInsertion(calculation, new MaxCustomersFeasibilityChecker(2), false));

        List<TestPlan> result = kMedoids.apply(origin, List.of(),
                new ArrayList<>(List.of(plan("west", west1, west2, west3), plan("east", east1))));

        assertThat(clusters(result)).containsExactly("km-W1:W1-W3", "km-E1:E1-W2");
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(west1, west2, west3, east1));
        PlanInvariants.assertTrackablesAreDistinct(result);
    }

    @Test
    void anInterruptDuringARoundReturnsThePlansOfThatRound() {
        // One vehicle for two seeds (see KMedoidsAlgorithmTest#stopsOnlyOnceTheMedoidSetNoLongerChanges): round one
        // seeds C0 only (the C1 seed finds no unit) and inserts C3 (117 from C0), C1 (140) and C2 (206), each at its
        // cheapest slot, giving O-C1-C2-C3-C0. Its medoid C1 is new, so an uninterrupted run goes on and re-seeds
        // around C1 (three more insertions); an interrupt raised during round one returns round one's plan.
        TestNode c0 = TestNode.customer("C0", 99, 21, 1);
        TestNode c1 = TestNode.customer("C1", -10, -10, 1);
        TestNode c2 = TestNode.customer("C2", 3, -89, 1);
        TestNode c3 = TestNode.customer("C3", 23, 62, 1);
        MatrixCostCalculation calculation = calculation(List.of(origin, c0, c1, c2, c3));
        TestKMedoids[] holder = new TestKMedoids[1];
        AtomicInteger insertions = new AtomicInteger();
        TestInsertion interrupting = new TestInsertion(calculation, new AlwaysFeasibleChecker(), false) {
            @Override
            public boolean insert(List<TestPlan> plans, TestNode customer, int... index) {
                if (insertions.getAndIncrement() == 0) {
                    holder[0].interrupt();
                }
                return super.insert(plans, customer, index);
            }
        };
        holder[0] = new TestKMedoids(calculation, fleet(1, 4), interrupting);

        List<TestPlan> interrupted = holder[0].apply(origin, List.of(),
                new ArrayList<>(List.of(plan("p0", c0, c2), plan("p1", c1, c3))));
        AtomicInteger completeInsertions = new AtomicInteger();
        new TestKMedoids(calculation, fleet(1, 4), new TestInsertion(calculation, new AlwaysFeasibleChecker(), false) {
            @Override
            public boolean insert(List<TestPlan> plans, TestNode customer, int... index) {
                completeInsertions.incrementAndGet();
                return super.insert(plans, customer, index);
            }
        }).apply(origin, List.of(), new ArrayList<>(List.of(plan("p0", c0, c2), plan("p1", c1, c3))));

        assertThat(insertions).as("round one still inserted all three customers").hasValue(3);
        assertThat(clusters(interrupted)).containsExactly("km-C0:C1-C2-C3-C0");
        assertThat(completeInsertions).as("an uninterrupted run goes on to round two").hasValue(6);
    }

    @Test
    void cancelReachesTheInsertionAndAbortsTheRun() {
        MatrixCostCalculation calculation = calculation(List.of(origin, west1, west2, west3, east1));
        AtomicBoolean insertionCancelled = new AtomicBoolean();
        TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), false) {
            @Override
            public void cancel() {
                insertionCancelled.set(true);
                super.cancel();
            }
        };
        TestKMedoids kMedoids = new TestKMedoids(calculation, fleet(2, 9), insertion);

        kMedoids.cancel();

        assertThat(insertionCancelled).isTrue();
        assertThatThrownBy(() -> kMedoids.apply(origin, List.of(west3),
                new ArrayList<>(List.of(plan("west", west1, west2), plan("east", east1)))))
                .isInstanceOf(CancelException.class);
    }

    @Test
    void neverDuplicatesOrInventsACustomerAndKeepsEveryoneWhenThereIsRoom() {
        for (long seed = 1; seed <= 40; seed++) {
            Random random = new Random(seed);
            int count = 4 + random.nextInt(9);
            int planCount = 1 + random.nextInt(3);
            List<TestNode> customers = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                customers.add(TestNode.customer("C" + i, random.nextInt(201) - 100, random.nextInt(201) - 100, 1));
            }
            List<TestNode> all = new ArrayList<>(List.of(origin));
            all.addAll(customers);
            final MatrixCostCalculation calculation = calculation(all);
            List<List<TestNode>> groups = new ArrayList<>();
            for (int i = 0; i < planCount; i++) {
                groups.add(new ArrayList<>(List.of(customers.get(i))));
            }
            List<TestNode> unplanned = new ArrayList<>();
            for (int i = planCount; i < count; i++) {
                int target = random.nextInt(planCount + 1);
                (target == planCount ? unplanned : groups.get(target)).add(customers.get(i));
            }
            boolean roomy = random.nextBoolean();
            int units = roomy ? planCount : 1 + random.nextInt(planCount);
            int capacity = roomy ? count : 1 + random.nextInt(count);
            FeasibilityChecker<TestPlan> checker = roomy ? new AlwaysFeasibleChecker()
                    : new MaxCustomersFeasibilityChecker(1 + random.nextInt(count));
            List<TestPlan> plans = new ArrayList<>();
            for (int i = 0; i < planCount; i++) {
                plans.add(plan("p" + i, groups.get(i).toArray(TestNode[]::new)));
            }

            List<TestPlan> result = new TestKMedoids(calculation, fleet(units, capacity),
                    new TestInsertion(calculation, checker, false)).apply(origin, unplanned, plans);

            String context = "seed " + seed;
            PlanInvariants.assertNoDuplicateCustomers(result);
            assertThat(PlanInvariants.customersOf(result)).as(context).isSubsetOf(customers);
            assertThat(result).as(context).hasSizeLessThanOrEqualTo(planCount);
            PlanInvariants.assertNodeLayout(result);
            PlanInvariants.assertTrackablesAreDistinct(result);
            PlanInvariants.assertCustomerCountWithinCapacity(result);
            if (roomy) {
                PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, customers);
            }
        }
    }

}
