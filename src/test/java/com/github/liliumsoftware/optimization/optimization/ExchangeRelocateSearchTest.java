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

package com.github.liliumsoftware.optimization.optimization;

import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.calculation.CostCalculation;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.insertion.AbstractInsertionAlgorithm;
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;
import com.github.liliumsoftware.optimization.support.AlwaysFeasibleChecker;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.PlanInvariants;
import com.github.liliumsoftware.optimization.support.RecostingLocalSearch;
import com.github.liliumsoftware.optimization.support.TestAssignment;
import com.github.liliumsoftware.optimization.support.TestInsertion;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestRelocate;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Search-level guarantees of exchange and relocate: the cap on accepted moves that stops a non-strict
 * {@code compare}, the neighbour test asked once per start pair, and relocation into plans without customers and into
 * one-stop plans, all on the line {@code O(0,0) N1(10,0) N2(20,0) F1(1000,0) F2(1010,0)} (Manhattan cost) plus a few
 * depots.
 */
class ExchangeRelocateSearchTest {

    private static final int MAX_MOVES = 100_000;

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode near1 = TestNode.customer("N1", 10, 0, 1);
    private final TestNode near2 = TestNode.customer("N2", 20, 0, 1);
    private final TestNode far1 = TestNode.customer("F1", 1000, 0, 1);
    private final TestNode far2 = TestNode.customer("F2", 1010, 0, 1);
    private final TestNode homeDestination = TestNode.destination("D", 0, 0);
    private final TestNode farDestination = TestNode.destination("E", 1000, 0);

    private static List<List<String>> routes(List<TestPlan> plans) {
        return plans.stream().map(plan -> {
            List<String> route = new ArrayList<>(List.of(plan.getCode()));
            plan.getNodes().forEach(node -> route.add(node.getCode()));
            return route;
        }).toList();
    }

    private MatrixCostCalculation calculation() {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, near1, near2, far1, far2, homeDestination, farDestination));
        return calculation;
    }

    private TestAssignment fleet() {
        return new TestAssignment(List.of(new TestTrackable("T1", 5), new TestTrackable("T2", 5)));
    }

    private TestInsertion insertion(MatrixCostCalculation calculation) {
        return new TestInsertion(calculation, new AlwaysFeasibleChecker(), false);
    }

    private TestRelocate relocate(MatrixCostCalculation calculation, ExchangeRelocateType type, int chain) {
        return new TestRelocate(calculation, fleet(), new RecostingLocalSearch(calculation), insertion(calculation),
                type, 4, chain);
    }

    private List<TestPlan> assigned(TestPlan... plans) {
        List<TestPlan> list = new ArrayList<>(List.of(plans));
        assertThat(fleet().assign(list)).isTrue();
        return list;
    }

    // ---------------------------------------------------------------- move cap

    @Test
    void aCompareThatAcceptsEveryMoveStopsAtTheMoveCap() {
        // Swapping the single customers of [O, N1] and [O, F1] back and forth never ends when compare accepts ties;
        // the only supported start pair yields one candidate, so every compare call is one accepted move.
        MatrixCostCalculation calculation = calculation();
        AcceptingExchange exchange = new AcceptingExchange(calculation, fleet(), new RecostingLocalSearch(calculation),
                insertion(calculation));
        List<TestPlan> plans = assigned(new TestPlan("p1", List.of(origin, near1)),
                new TestPlan("p2", List.of(origin, far1)));

        List<TestPlan> result = assertTimeoutPreemptively(Duration.ofSeconds(60), () -> exchange.apply(plans));

        assertThat(result).isSameAs(plans);
        assertThat(exchange.compares).isEqualTo(MAX_MOVES);
        // an even number of swaps brings both customers back
        assertThat(routes(result)).containsExactly(List.of("p1", "O", "N1"), List.of("p2", "O", "F1"));
        PlanInvariants.assertValidSolution(result, origin, null, List.of(near1, far1));
    }

    // ---------------------------------------------------------------- neighbour test

    @Test
    void theNeighbourTestIsAskedOncePerStartPairAndOrientation() {
        // Chain 3 with cross gives up to nine length combinations per start pair; the test depends on the start pair
        // only, so it is asked at most once for each (first plan, second plan, i, j).
        MatrixCostCalculation calculation = calculation();
        CountingExchange exchange = new CountingExchange(calculation, fleet(), new RecostingLocalSearch(calculation),
                insertion(calculation));
        List<TestPlan> plans = assigned(new TestPlan("p1", List.of(origin, near1, far1, near2)),
                new TestPlan("p2", List.of(origin, far2)));

        List<TestPlan> result = exchange.apply(plans);

        assertThat(exchange.asked).isNotEmpty();
        assertThat(exchange.duplicates).isEmpty();
        PlanInvariants.assertValidSolution(result, origin, null, List.of(near1, near2, far1, far2));
    }

    // ---------------------------------------------------------------- relocation into empty and one-stop plans

    @ParameterizedTest
    @EnumSource(ExchangeRelocateType.class)
    void relocateMovesCustomersIntoAPlanWithoutCustomers(ExchangeRelocateType type) {
        // [O, N1, F1, D] (10 + 990 + 1000 = 2000) + the empty [O, E] (1000) = 3000. The empty plan's only slot is in
        // front of E. Moving N1 there gains nothing ([O, F1, D] (2000) + [O, N1, E] (1000)); moving F1 gives
        // [O, N1, D] (20) + [O, F1, E] (1000) = 1020; then N1 follows in front of F1, emptying and dropping p1:
        // [O, N1, F1, E] = 10 + 990 = 1000.
        MatrixCostCalculation calculation = calculation();
        List<TestPlan> plans = assigned(new TestPlan("p1", List.of(origin, near1, far1, homeDestination)),
                new TestPlan("p2", List.of(origin, farDestination)));

        List<TestPlan> result = relocate(calculation, type, 1).apply(plans);

        assertThat(routes(result)).containsExactly(List.of("p2", "O", "N1", "F1", "E"));
        assertThat(PlanInvariants.referenceTotalCost(result)).isEqualTo(1000);
    }

    @ParameterizedTest
    @EnumSource(ExchangeRelocateType.class)
    void relocateMovesCustomersIntoAPlanMadeOfItsOriginAlone(ExchangeRelocateType type) {
        // p1 costs ten times its length: 10 * [O, N1, F1] (10 * 1000) + [O] (0) = 10000. The origin-only plan shares
        // the depot code with p1, so it is no neighbour of anything; it is still eligible. With chain 2 the longest
        // segment moves first and empties p1: [O, N1, F1] = 1000.
        MatrixCostCalculation calculation = calculation();
        TestPlan expensive = new TestPlan("p1", List.of(origin, near1, far1));
        expensive.setCostMultiplier(10);
        List<TestPlan> plans = assigned(expensive, new TestPlan("p2", List.of(origin)));

        List<TestPlan> result = relocate(calculation, type, 2).apply(plans);

        assertThat(routes(result)).containsExactly(List.of("p2", "O", "N1", "F1"));
        assertThat(CostCalculation.totalCost(result)).isEqualTo(1000);
    }

    @ParameterizedTest
    @EnumSource(ExchangeRelocateType.class)
    void relocateMovesCustomersIntoAOneStopPlanWithoutOrigin(ExchangeRelocateType type) {
        // [O, N1, F1] (1000) + [F2] (0): moving F1 in front of F2 gives [O, N1] (10) + [F1, F2] (10) = 20 (behind F2
        // costs the same; the first slot wins). Moving N1 as well would cost [N1, F1, F2] = 1000.
        MatrixCostCalculation calculation = calculation();
        List<TestPlan> plans = assigned(new TestPlan("p1", List.of(origin, near1, far1)),
                new TestPlan("p2", List.of(far2)));

        List<TestPlan> result = relocate(calculation, type, 1).apply(plans);

        assertThat(routes(result)).containsExactly(List.of("p1", "O", "N1"), List.of("p2", "F1", "F2"));
        assertThat(PlanInvariants.referenceTotalCost(result)).isEqualTo(20);
    }

    /**
     * An exchange whose {@code compare} accepts every candidate, ties included, and counts its calls.
     */
    private static final class AcceptingExchange extends AbstractExchangeAlgorithm<TestPlan, TestNode> {

        private int compares;

        private AcceptingExchange(PathCostCalculation<TestPlan, TestNode, ?> calculation,
                TrackableAssignment<TestPlan, ?> assignment, LocalSearchAlgorithm<TestPlan> localSearch,
                AbstractInsertionAlgorithm<TestPlan, TestNode, ?> insertion) {
            super(calculation, assignment, localSearch, insertion, ExchangeRelocateType.SIMPLE, 4, 1, false);
        }

        @Override
        protected boolean compare(List<TestPlan> newPlans, List<TestPlan> oldPlans) {
            compares++;
            return true;
        }

    }

    /**
     * An exchange that records every start pair its neighbour test is asked for.
     */
    private static final class CountingExchange extends AbstractExchangeAlgorithm<TestPlan, TestNode> {

        private final Set<String> asked = new HashSet<>();
        private final List<String> duplicates = new ArrayList<>();

        private CountingExchange(PathCostCalculation<TestPlan, TestNode, ?> calculation,
                TrackableAssignment<TestPlan, ?> assignment, LocalSearchAlgorithm<TestPlan> localSearch,
                AbstractInsertionAlgorithm<TestPlan, TestNode, ?> insertion) {
            super(calculation, assignment, localSearch, insertion, ExchangeRelocateType.SIMPLE, 4, 3, true);
        }

        @Override
        protected boolean apply(List<TestPlan> otherPlans, List<TestPlan> plans, TestPlan firstPlan,
                TestPlan secondPlan) {
            // a new scan of a pair starts afresh: only repeats within one pair visit count
            asked.clear();
            return super.apply(otherPlans, plans, firstPlan, secondPlan);
        }

        @Override
        protected boolean supportsNeighbor(TestPlan firstPlan, TestPlan secondPlan, int firstPlanStart,
                int secondPlanStart) {
            String key = firstPlan.getCode() + firstPlan.getNodes() + firstPlanStart + "/" + secondPlan.getCode()
                    + secondPlan.getNodes() + secondPlanStart;
            if (!asked.add(key)) {
                duplicates.add(key);
            }
            return super.supportsNeighbor(firstPlan, secondPlan, firstPlanStart, secondPlanStart);
        }

    }

}
