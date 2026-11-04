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

import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;
import com.github.liliumsoftware.optimization.support.AlwaysFeasibleChecker;
import com.github.liliumsoftware.optimization.support.CancelSpyLocalSearch;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.PlanInvariants;
import com.github.liliumsoftware.optimization.support.Problems;
import com.github.liliumsoftware.optimization.support.RecostingLocalSearch;
import com.github.liliumsoftware.optimization.support.TestAssignment;
import com.github.liliumsoftware.optimization.support.TestDestroyRepair;
import com.github.liliumsoftware.optimization.support.TestInsertion;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import com.github.liliumsoftware.optimization.support.TestTwoOpt;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins destroy-and-repair behaviour that the broader {@link DestroyRepairTest} leaves open: equal-rank customers are
 * tried in plan order (so runs over nodes with identity hash codes are reproducible), the small-plan pass empties and
 * drops a plan, closed plans keep their depots, a local search that lowers a cost buys another pass,
 * {@code interrupt()} keeps the best plans so far, the caller's plan objects are never modified, and the protected
 * hooks guard their indexes.
 */
class DestroyRepairContractTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);

    private static List<List<String>> routes(List<TestPlan> plans) {
        return plans.stream().map(plan -> {
            List<String> route = new ArrayList<>(List.of(plan.getCode()));
            plan.getNodes().forEach(node -> route.add(node.getCode()));
            return route;
        }).toList();
    }

    private TestDestroyRepair destroyRepair(MatrixCostCalculation calculation, TestAssignment assignment,
            LocalSearchAlgorithm<TestPlan> localSearch, int chain, int threshold) {
        return new TestDestroyRepair(calculation, assignment, localSearch,
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), chain, threshold);
    }

    // ---------------------------------------------------------------- tie order

    /**
     * A mirror-symmetric instance: {@code p1 = [O, A(-10,0), X(-5,100)]}, {@code p2 = [O, B(10,0), Y(5,100)]} and
     * {@code p3 = [O, Z(0,100)]}, two-seat vehicles. X and Y (and A and B) are mirror images, so they tie on rank and
     * on cost rank; whichever of X and Y is tried first moves behind Z and fills the third vehicle.
     */
    private List<TestPlan> mirrored(TestNode a, TestNode b, TestNode x, TestNode y, TestNode z, TestNode depot) {
        return new ArrayList<>(List.of(new TestPlan("p1", List.of(depot, a, x)), new TestPlan("p2", List.of(depot, b,
                y)), new TestPlan("p3", List.of(depot, z))));
    }

    private TestAssignment twoSeatFleet() {
        return new TestAssignment(List.of(new TestTrackable("T1", 2), new TestTrackable("T2", 2),
                new TestTrackable("T3", 2)));
    }

    @Test
    void equalRankCustomersAreTriedInPlanOrderWhateverTheirHashCodes() {
        TestNode a = TestNode.customer("A", -10, 0, 1);
        TestNode b = TestNode.customer("B", 10, 0, 1);
        TestNode x = TestNode.customer("X", -5, 100, 1);
        TestNode y = TestNode.customer("Y", 5, 100, 1);
        TestNode z = TestNode.customer("Z", 0, 100, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, a, b, x, y, z));

        // Identity-equal nodes hash by identity, so every run sees different hash codes for the same customers; only
        // plan order may decide among equal ranks. Before: (10 + 105) + (10 + 105) + 100 = 330. Plan order tries X
        // first: [O, Z, X] costs 105, saving 100, and leaves Y without a free seat near Z: 10 + 115 + 105 = 230.
        List<List<List<String>>> results = new ArrayList<>();
        for (int run = 0; run < 20; run++) {
            List<TestPlan> plans = mirrored(a.withIdentityEquality(), b.withIdentityEquality(),
                    x.withIdentityEquality(), y.withIdentityEquality(), z.withIdentityEquality(),
                    origin.withIdentityEquality());
            TestAssignment assignment = twoSeatFleet();
            assertThat(assignment.assign(plans)).isTrue();
            TestDestroyRepair destroyRepair = destroyRepair(calculation, assignment,
                    new RecostingLocalSearch(calculation), 1, 0);

            assertThat(destroyRepair.highestRankNodes(plans)).extracting(entry -> entry.getKey().getCode())
                    .as("run %d", run).containsExactly("X", "Y", "A", "B", "Z");
            List<TestPlan> result = destroyRepair.apply(plans);

            assertThat(PlanInvariants.referenceTotalCost(result)).as("run %d", run).isEqualTo(230);
            results.add(routes(result));
        }
        assertThat(results).allSatisfy(route -> assertThat(route).isEqualTo(List.of(List.of("p1", "O", "A"),
                List.of("p2", "O", "B", "Y"), List.of("p3", "O", "Z", "X"))));
    }

    @Test
    void theTieOrderDecidesTheResultOfTheMirroredInstance() {
        // The control for the test above: trying the tied customers in the opposite order moves Y instead of X, so a
        // hash-ordered ranking would make the result vary from run to run.
        TestNode a = TestNode.customer("A", -10, 0, 1);
        TestNode b = TestNode.customer("B", 10, 0, 1);
        TestNode x = TestNode.customer("X", -5, 100, 1);
        TestNode y = TestNode.customer("Y", 5, 100, 1);
        TestNode z = TestNode.customer("Z", 0, 100, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, a, b, x, y, z));
        List<TestPlan> plans = mirrored(a, b, x, y, z, origin);
        TestAssignment assignment = twoSeatFleet();
        assertThat(assignment.assign(plans)).isTrue();
        AbstractDestroyRepairAlgorithm<TestPlan, TestNode> reversedTies = new AbstractDestroyRepairAlgorithm<>(
                calculation, assignment, new RecostingLocalSearch(calculation),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), 1, 0) {
            @Override
            protected List<Map.Entry<TestNode, Integer>> getHighestRankNodes(List<TestPlan> current) {
                List<Map.Entry<TestNode, Integer>> ranked = new ArrayList<>(super.getHighestRankNodes(current));
                Collections.reverse(ranked);
                ranked.sort(Map.Entry.<TestNode, Integer>comparingByValue().reversed());
                return ranked;
            }
        };

        List<TestPlan> result = reversedTies.apply(plans);

        assertThat(PlanInvariants.referenceTotalCost(result)).isEqualTo(230);
        assertThat(routes(result)).containsExactly(List.of("p1", "O", "A", "X"), List.of("p2", "O", "B"),
                List.of("p3", "O", "Z", "Y"));
    }

    // ---------------------------------------------------------------- small-plan pass

    @Test
    void theSmallPlanPassEmptiesAndRemovesAPlan() {
        // A(0,10), B(0,20), C(0,21); p1 = [O, A, B] (20), p2 = [O, C] (21): 41. The threshold disables the ranked pass,
        // so only the small-plan pass acts: destroying A alone gives [O, B] + [O, A, C] = 20 + 21 (no gain), destroying
        // [A, B] empties p1 and gives [O, A, B, C] = 21, which removes p1 from the list.
        TestNode a = TestNode.customer("A", 0, 10, 1);
        TestNode b = TestNode.customer("B", 0, 20, 1);
        TestNode c = TestNode.customer("C", 0, 21, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, a, b, c));
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 5), new TestTrackable("T2", 5)));
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", List.of(origin, a, b)),
                new TestPlan("p2", List.of(origin, c))));
        assertThat(assignment.assign(plans)).isTrue();
        final List<TestPlan> originals = List.copyOf(plans);
        final List<TestPlan> snapshots = PlanInvariants.deepCopy(plans);

        List<TestPlan> result = destroyRepair(calculation, assignment, new RecostingLocalSearch(calculation), 2, 1000)
                .apply(plans);

        assertThat(routes(result)).containsExactly(List.of("p2", "O", "A", "B", "C"));
        assertThat(PlanInvariants.referenceTotalCost(result)).isEqualTo(21);
        PlanInvariants.assertEveryPlanHasTrackable(result);
        for (int i = 0; i < originals.size(); i++) {
            assertThat(originals.get(i).getNodes()).as("caller's plan %d", i)
                    .containsExactlyElementsOf(snapshots.get(i).getNodes());
        }
    }

    // ---------------------------------------------------------------- closed plans

    @Test
    void closedPlansAreRepairedWithoutEverDestroyingADepot() {
        // Closed routes back to D(0,0): [O, N1, F1, D] (10 + 990 + 1000) + [O, N2, F2, D] (20 + 990 + 1010) = 4020.
        // Chains of two that would reach the destination are skipped. A single closed tour O-N1-N2-F1-F2-D costs
        // 1010 + 1010 = 2020, and no closed tour reaching F2 can cost less.
        TestNode destination = TestNode.destination("D", 0, 0);
        TestNode near1 = TestNode.customer("N1", 10, 0, 1);
        TestNode near2 = TestNode.customer("N2", 20, 0, 1);
        TestNode far1 = TestNode.customer("F1", 1000, 0, 1);
        TestNode far2 = TestNode.customer("F2", 1010, 0, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, near1, near2, far1, far2, destination));
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 5), new TestTrackable("T2", 5)));
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", List.of(origin, near1, far1, destination)),
                new TestPlan("p2", List.of(origin, near2, far2, destination))));
        assertThat(assignment.assign(plans)).isTrue();
        assertThat(PlanInvariants.referenceTotalCost(plans)).isEqualTo(4020);

        List<TestPlan> result = destroyRepair(calculation, assignment, new RecostingLocalSearch(calculation), 2, 0)
                .apply(plans);

        assertThat(PlanInvariants.referenceTotalCost(result)).isEqualTo(2020);
        PlanInvariants.assertValidSolution(result, origin, destination, List.of(near1, near2, far1, far2));
    }

    // ---------------------------------------------------------------- passes

    @Test
    void aLocalSearchThatLowersACostBuysExactlyOneMorePass() {
        // [O, B, A] (20 + 10 = 30) with the ranked pass disabled and no plan small enough for the small-plan pass: no
        // move is ever accepted, so only the local search can change anything. A 2-opt lowers the cost to 20 in the
        // first pass, which pays for a second pass; that one lowers nothing and ends the run. An identity local
        // search ends it after the first pass.
        TestNode a = TestNode.customer("A", 0, 10, 1);
        TestNode b = TestNode.customer("B", 0, 20, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, a, b));
        for (boolean improving : new boolean[] {true, false}) {
            CountingLocalSearch localSearch = new CountingLocalSearch(improving
                    ? new TestTwoOpt(calculation, new AlwaysFeasibleChecker()) : new RecostingLocalSearch(calculation));
            TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 5)));
            List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p", List.of(origin, b, a))));
            assertThat(assignment.assign(plans)).isTrue();

            List<TestPlan> result = destroyRepair(calculation, assignment, localSearch, 1, 1000).apply(plans);

            assertThat(localSearch.calls).as("improving=%s", improving).isEqualTo(improving ? 2 : 1);
            assertThat(result.getFirst().getNodes()).containsExactlyElementsOf(improving ? List.of(origin, a, b)
                    : List.of(origin, b, a));
        }
    }

    // ---------------------------------------------------------------- interrupt

    @Test
    void anInstanceInterruptedBeforeTheRunReturnsThePlansUnchanged() {
        Problems.Problem problem = Problems.random(1, 12, 4, 6, 1_000);
        MatrixCostCalculation calculation = problem.calculation();
        List<TestPlan> plans = chunked(problem);
        TestAssignment assignment = problem.assignment();
        assertThat(assignment.assign(plans)).isTrue();
        List<TestPlan> originals = List.copyOf(plans);
        CountingLocalSearch localSearch = new CountingLocalSearch(new RecostingLocalSearch(calculation));
        TestDestroyRepair destroyRepair = destroyRepair(calculation, assignment, localSearch, 2, 0);
        destroyRepair.interrupt();

        List<TestPlan> result = destroyRepair.apply(plans);

        assertThat(result).isSameAs(plans).containsExactlyElementsOf(originals);
        assertThat(localSearch.calls).isZero();
    }

    @Test
    void anInterruptDuringTheRunKeepsTheBestPlansOfTheFinishedPass() {
        // The local search runs once per plan at the end of every pass; interrupting from there ends the run after the
        // first pass. That pass must already have improved the chunked start, and the uninterrupted run (several
        // passes) must get further, otherwise the interrupt would not have cut anything short.
        Problems.Problem problem = Problems.random(1, 12, 4, 6, 1_000);
        MatrixCostCalculation calculation = problem.calculation();

        List<TestPlan> start = chunked(problem);
        TestAssignment assignment = problem.assignment();
        assertThat(assignment.assign(start)).isTrue();
        final long before = PlanInvariants.referenceTotalCost(start);
        InterruptingLocalSearch localSearch = new InterruptingLocalSearch();
        TestDestroyRepair interrupted = destroyRepair(calculation, assignment, localSearch, 2, 0);
        localSearch.target = interrupted;
        List<TestPlan> firstPass = interrupted.apply(start);

        List<TestPlan> fullStart = chunked(problem);
        TestAssignment fullAssignment = problem.assignment();
        assertThat(fullAssignment.assign(fullStart)).isTrue();
        List<TestPlan> full = destroyRepair(calculation, fullAssignment, new RecostingLocalSearch(calculation), 2, 0)
                .apply(fullStart);

        long afterFirstPass = PlanInvariants.referenceTotalCost(firstPass);
        assertThat(localSearch.calls).as("one pass: one local search per plan").isEqualTo(firstPass.size());
        assertThat(afterFirstPass).isLessThan(before).isGreaterThan(PlanInvariants.referenceTotalCost(full));
        PlanInvariants.assertValidSolution(firstPass, problem.origin(), null, problem.customers());
    }

    private List<TestPlan> chunked(Problems.Problem problem) {
        List<TestPlan> plans = new ArrayList<>();
        for (int chunk = 0; chunk < 4; chunk++) {
            plans.add(problem.plan("p" + chunk, problem.customers().subList(chunk * 3, chunk * 3 + 3)));
        }
        return plans;
    }

    @Test
    void interruptReachesTheNestedInsertionAndLocalSearch() {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin));
        CancelSpyLocalSearch localSearch = new CancelSpyLocalSearch();
        boolean[] insertionInterrupted = new boolean[1];
        TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), false) {
            @Override
            public void interrupt() {
                insertionInterrupted[0] = true;
                super.interrupt();
            }
        };
        TestDestroyRepair destroyRepair = new TestDestroyRepair(calculation,
                new TestAssignment(List.of(new TestTrackable("T1", 5))), localSearch, insertion, 1, 0);

        destroyRepair.interrupt();

        assertThat(localSearch.isInterrupted()).isTrue();
        assertThat(insertionInterrupted[0]).isTrue();
        assertThat(localSearch.isCancelled()).as("interrupt is not cancel").isFalse();
    }

    // ---------------------------------------------------------------- the caller's plans

    @Test
    void theRankedPassNeverModifiesTheCallersPlanObjects() {
        TestNode near1 = TestNode.customer("N1", 10, 0, 1);
        TestNode near2 = TestNode.customer("N2", 20, 0, 1);
        TestNode far1 = TestNode.customer("F1", 1000, 0, 1);
        TestNode far2 = TestNode.customer("F2", 1010, 0, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, near1, near2, far1, far2));
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 5), new TestTrackable("T2", 5)));
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", List.of(origin, near1, far1)),
                new TestPlan("p2", List.of(origin, near2, far2))));
        assertThat(assignment.assign(plans)).isTrue();
        List<TestPlan> originals = List.copyOf(plans);
        List<TestPlan> snapshots = PlanInvariants.deepCopy(plans);

        // Chains of one keep the small-plan pass out of the way (both plans hold two customers).
        List<TestPlan> result = destroyRepair(calculation, assignment, new RecostingLocalSearch(calculation), 1, 0)
                .apply(plans);

        assertThat(PlanInvariants.referenceTotalCost(result)).isEqualTo(1010);
        for (int i = 0; i < originals.size(); i++) {
            assertThat(originals.get(i).getNodes()).as("caller's plan %d", i)
                    .containsExactlyElementsOf(snapshots.get(i).getNodes());
            assertThat(originals.get(i)).isEqualTo(snapshots.get(i));
        }
    }

    // ---------------------------------------------------------------- protected hooks

    @Test
    void theProtectedHooksIgnorePlansAndIndexesOutsideTheList() {
        TestNode a = TestNode.customer("A", 0, 10, 1);
        TestNode b = TestNode.customer("B", 0, 20, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, a, b));
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 5), new TestTrackable("T2", 5)));
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", List.of(origin, a)),
                new TestPlan("p2", List.of(origin, b))));
        assertThat(assignment.assign(plans)).isTrue();
        final List<TestPlan> originals = List.copyOf(plans);
        TestDestroyRepair destroyRepair = destroyRepair(calculation, assignment, new RecostingLocalSearch(calculation),
                2, 0);

        // A copy is equal to p1 but not in the list: plans are located by identity, so nothing is destroyed.
        destroyRepair.destroyAndRepair(List.of(), plans, plans.getFirst().copy());
        assertThat(destroyRepair.destroyAndRepair(List.of(), plans, -1, 1, 2)).isFalse();
        assertThat(destroyRepair.destroyAndRepair(List.of(), plans, 2, 1, 2)).isFalse();

        assertThat(plans).containsExactlyElementsOf(originals);
        assertThat(plans).allSatisfy(plan -> assertThat(originals).anySatisfy(o -> assertThat(o).isSameAs(plan)));
    }

    /**
     * Delegating local search that counts its calls.
     */
    private static final class CountingLocalSearch extends LocalSearchAlgorithm<TestPlan> {

        private final LocalSearchAlgorithm<TestPlan> delegate;
        private int calls;

        private CountingLocalSearch(LocalSearchAlgorithm<TestPlan> delegate) {
            this.delegate = delegate;
        }

        @Override
        public TestPlan apply(TestPlan plan) {
            calls++;
            return delegate.apply(plan);
        }

    }

    /**
     * Identity local search that interrupts {@link #target} whenever it runs, i.e. at the end of every pass.
     */
    private static final class InterruptingLocalSearch extends LocalSearchAlgorithm<TestPlan> {

        private AbstractDestroyRepairAlgorithm<TestPlan, TestNode> target;
        private int calls;

        @Override
        public TestPlan apply(TestPlan plan) {
            calls++;
            target.interrupt();
            return plan;
        }

    }

}
