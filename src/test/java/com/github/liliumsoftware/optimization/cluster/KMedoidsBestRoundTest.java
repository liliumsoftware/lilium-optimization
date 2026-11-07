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

import com.github.liliumsoftware.optimization.support.AlwaysFeasibleChecker;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.PlanInvariants;
import com.github.liliumsoftware.optimization.support.TestAssignment;
import com.github.liliumsoftware.optimization.support.TestInsertion;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins which plans {@link AbstractKMedoidsAlgorithm} returns: customers a round could not place are offered again in
 * later rounds, and the result is the best round (most customers served, then lowest total cost), not the last one,
 * also when an interrupt ends the run early.
 */
class KMedoidsBestRoundTest {

    /**
     * A k-medoids run on one capacity-{@code capacity} unit that records its seeds and lets customers coded {@code X…}
     * join only the cluster of medoid {@code M}.
     */
    private static AbstractKMedoidsAlgorithm<TestPlan, TestNode> kMedoids(MatrixCostCalculation calculation,
            int capacity, TestInsertion insertion, List<String> seeds) {
        return new AbstractKMedoidsAlgorithm<>(calculation,
                new TestAssignment(List.of(new TestTrackable("T", capacity))), insertion) {
            @Override
            protected TestPlan getNewPlan(TestNode depot, TestNode customer) {
                seeds.add(customer.getCode());
                return new TestPlan("km-" + customer.getCode(), List.of(depot, customer));
            }

            @Override
            protected List<TestNode> getMedoids(TestNode node, List<TestNode> medoids) {
                if (node.getCode().startsWith("X")) {
                    return medoids.stream().filter(m -> m.getCode().equals("M")).toList();
                }
                return medoids;
            }
        };
    }

    @Test
    void aCustomerARoundCouldNotPlaceIsOfferedAgainInLaterRounds() {
        // Customers on the x axis: Y(0), M(10), A(20), X1(100), X2(101); the depot is at (0,-50). The input plan's
        // medoid is A (191 against M's 201), so round one seeds A and X1, X2 find no admissible medoid: round one
        // serves Y, M, A for 70. Its medoid is M (20), so round two seeds M. Before, X1 and X2 belonged to neither the
        // current plans nor unplannedNodes and were never offered again; now round two places them, serving all five
        // customers for 151 — more customers beat the lower cost of round one. Its medoid A repeats round one's set.
        TestNode origin = TestNode.origin("O", 0, -50);
        TestNode y = TestNode.customer("Y", 0, 0, 1);
        TestNode m = TestNode.customer("M", 10, 0, 1);
        TestNode a = TestNode.customer("A", 20, 0, 1);
        TestNode x1 = TestNode.customer("X1", 100, 0, 1);
        TestNode x2 = TestNode.customer("X2", 101, 0, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, y, m, a, x1, x2));
        List<String> seeds = new ArrayList<>();
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p", List.of(origin, y, m, a, x1, x2))));

        TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), false);

        List<TestPlan> result = kMedoids(calculation, 9, insertion, seeds).apply(origin, List.of(), plans);

        assertThat(seeds).containsExactly("A", "M");
        assertThat(result).singleElement().extracting(TestPlan::getCode).isEqualTo("km-M");
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(y, m, a, x1, x2));
        assertThat(PlanInvariants.referenceTotalCost(result)).isEqualTo(151);
    }

    @Test
    void anInterruptReturnsTheBestRoundSoFarNotTheRoundInProgress() {
        // One unit for two seeds (see KMedoidsAlgorithmTest#stopsOnlyOnceTheMedoidSetNoLongerChanges): round one gives
        // O-C1-C2-C3-C0 (400), round two, seeded around C1, gives O-C0-C3-C1-C2 (434). The interrupt is raised during
        // round two's first insertion; round two completes, but the run returns round one, the cheaper of the two.
        // Before, the round in progress was returned.
        TestNode origin = TestNode.origin("O", 0, 0);
        TestNode c0 = TestNode.customer("C0", 99, 21, 1);
        TestNode c1 = TestNode.customer("C1", -10, -10, 1);
        TestNode c2 = TestNode.customer("C2", 3, -89, 1);
        TestNode c3 = TestNode.customer("C3", 23, 62, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, c0, c1, c2, c3));
        List<String> seeds = new ArrayList<>();
        AtomicInteger insertions = new AtomicInteger();
        List<AbstractKMedoidsAlgorithm<TestPlan, TestNode>> holder = new ArrayList<>();
        TestInsertion interrupting = new TestInsertion(calculation, new AlwaysFeasibleChecker(), false) {
            @Override
            public boolean insert(List<TestPlan> plans, TestNode customer, int... index) {
                if (insertions.incrementAndGet() == 4) {
                    holder.getFirst().interrupt();
                }
                return super.insert(plans, customer, index);
            }
        };
        holder.add(kMedoids(calculation, 4, interrupting, seeds));
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p0", List.of(origin, c0, c2)),
                new TestPlan("p1", List.of(origin, c1, c3))));

        List<TestPlan> result = holder.getFirst().apply(origin, List.of(), plans);

        assertThat(seeds).as("round two ran").containsExactly("C0", "C1", "C1");
        assertThat(insertions).hasValue(6);
        assertThat(result).singleElement().extracting(TestPlan::getCode).isEqualTo("km-C0");
        assertThat(result.getFirst().getCustomers()).containsExactly(c1, c2, c3, c0);
        assertThat(PlanInvariants.referenceTotalCost(result)).isEqualTo(400);
    }

}
