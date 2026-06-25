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

package com.github.liliumsoftware.optimization.localsearch;

import com.github.liliumsoftware.optimization.feasibility.FeasibilityChecker;
import com.github.liliumsoftware.optimization.feasibility.FeasibilityResult;
import com.github.liliumsoftware.optimization.support.AlwaysFeasibleChecker;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.PlanInvariants;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestOpenTwoOpt;
import com.github.liliumsoftware.optimization.support.TestPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Open-path 2-opt differs from the closed variant in exactly one respect that matters: an open route has no
 * closing edge, so the <em>last</em> stop may legitimately change — unless the plan has a destination, which
 * must stay pinned at the end. On top of the plain segment reversal it evaluates two relocation variants, so
 * these tests check both that it finds improvements a bare reversal would miss and that it never breaks the
 * route's structural invariants.
 *
 * <p>Expectations are hand-computed on the collinear instance {@code O(0,0), A(0,10), B(0,20), C(0,30)} under
 * Manhattan distance, and cross-checked against a brute-force optimum for random instances.
 */
class OpenTwoOptTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode a = TestNode.customer("A", 0, 10, 1);
    private final TestNode b = TestNode.customer("B", 0, 20, 1);
    private final TestNode c = TestNode.customer("C", 0, 30, 1);

    private MatrixCostCalculation calc(List<TestNode> nodes) {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(nodes);
        return calculation;
    }

    private TestOpenTwoOpt twoOpt(MatrixCostCalculation calculation) {
        return new TestOpenTwoOpt(calculation, new AlwaysFeasibleChecker());
    }

    // ---------------------------------------------------------------- known examples

    @Test
    void reversesAnInvertedPairIntoTheCheaperOrder() {
        MatrixCostCalculation calculation = calc(List.of(origin, a, b));
        TestPlan plan = new TestPlan("p", List.of(origin, b, a));   // 20 + 10 = 30

        TestPlan result = twoOpt(calculation).apply(plan);

        assertThat(result.getNodes()).containsExactly(origin, a, b);   // 10 + 10 = 20
        assertThat(PlanInvariants.referenceCost(result)).isEqualTo(20);
    }

    @Test
    void findsTheOptimalOrderOfThreeCollinearCustomers() {
        MatrixCostCalculation calculation = calc(List.of(origin, a, b, c));
        TestPlan plan = new TestPlan("p", List.of(origin, c, a, b));   // 30 + 20 + 10 = 60

        TestPlan result = twoOpt(calculation).apply(plan);

        // O-A-B-C = 10 + 10 + 10 = 30 is the unique optimum for an open path.
        assertThat(result.getNodes()).containsExactly(origin, a, b, c);
        assertThat(PlanInvariants.referenceCost(result)).isEqualTo(30);
    }

    @Test
    void anAlreadyOptimalRouteIsReturnedUnchanged() {
        MatrixCostCalculation calculation = calc(List.of(origin, a, b, c));
        TestPlan plan = new TestPlan("p", List.of(origin, a, b, c));

        TestPlan result = twoOpt(calculation).apply(plan);

        assertThat(result.getNodes()).containsExactly(origin, a, b, c);
        assertThat(result).as("no improvement means the very same instance comes back").isSameAs(plan);
    }

    @Test
    void theLastStopMayChangeWhenThereIsNoDestination() {
        // This is the whole point of the open variant: a plain closed 2-opt pins the final node, which on an
        // open path leaves the (often expensive) last leg unfixable.
        MatrixCostCalculation calculation = calc(List.of(origin, a, b, c));
        TestPlan plan = new TestPlan("p", List.of(origin, b, c, a));   // 20 + 10 + 20 = 50

        TestPlan result = twoOpt(calculation).apply(plan);

        assertThat(PlanInvariants.referenceCost(result)).isLessThan(50);
        assertThat(result.getNodes()).containsExactlyInAnyOrder(origin, a, b, c);
        assertThat(result.getNodes().getFirst()).isEqualTo(origin);
    }

    // ---------------------------------------------------------------- structure

    @Test
    void aDestinationStaysPinnedToTheEnd() {
        TestNode destination = TestNode.destination("D", 0, 0);
        MatrixCostCalculation calculation = calc(List.of(origin, a, b, c, destination));
        TestPlan plan = new TestPlan("p", List.of(origin, c, a, b, destination));

        TestPlan result = twoOpt(calculation).apply(plan);

        assertThat(result.getNodes().getFirst()).isEqualTo(origin);
        assertThat(result.getNodes().getLast()).as("the return depot must stay last").isEqualTo(destination);
        assertThat(result.getNodes()).containsExactlyInAnyOrder(origin, a, b, c, destination);
        PlanInvariants.assertNodeLayout(List.of(result));
    }

    @Test
    void aGetPlanThatRebuildsTheGivenPlanInPlaceNeitherAliasesCandidatesNorTouchesTheInput() {
        // The closed 2-opt hands its hook a copy, which may be rebuilt in place. The open variant handed getPlan the
        // live best plan — on the first move the caller's own object — so such a hook overwrote the input and made
        // all three candidates the same object.
        MatrixCostCalculation calculation = calc(List.of(origin, a, b, c));
        AbstractOpenTwoOptAlgorithm<TestPlan, TestNode> inPlace = new AbstractOpenTwoOptAlgorithm<>(calculation,
                new AlwaysFeasibleChecker()) {
            @Override
            protected TestPlan getPlan(TestPlan plan, List<TestNode> nodes) {
                plan.getNodes().clear();
                plan.getNodes().addAll(nodes);
                return plan;
            }
        };
        TestPlan input = new TestPlan("p", List.of(origin, c, a, b));
        TestPlan snapshot = input.copy();

        TestPlan result = inPlace.apply(input);

        assertThat(input).as("the caller's plan").isEqualTo(snapshot);
        assertThat(result.getNodes()).containsExactly(origin, a, b, c);
    }

    @Test
    void theFirstCustomerOfAPlanWithoutAnOriginTakesPartInTheMoves() {
        // [A, B, D] with D(0,0): nothing is pinned at index 0, so the reversal B-A-D (20) must be found (30 before).
        TestNode d = TestNode.destination("D", 0, 0);
        MatrixCostCalculation calculation = calc(List.of(a, b, d));

        assertThat(twoOpt(calculation).apply(new TestPlan("p", List.of(a, b, d))).getNodes()).containsExactly(b, a, d);
        assertThat(new com.github.liliumsoftware.optimization.support.TestTwoOpt(calculation,
                new AlwaysFeasibleChecker())
                .apply(new TestPlan("p", List.of(a, b, d))).getNodes()).containsExactly(b, a, d);
    }

    @Test
    void theOriginIsNeverMoved() {
        MatrixCostCalculation calculation = calc(List.of(origin, a, b, c));
        TestPlan plan = new TestPlan("p", List.of(origin, c, b, a));

        assertThat(twoOpt(calculation).apply(plan).getNodes().getFirst()).isEqualTo(origin);
    }

    @Test
    void theAssignedResourceSurvivesAMove() {
        MatrixCostCalculation calculation = calc(List.of(origin, a, b));
        TestPlan plan = new TestPlan("p", List.of(origin, b, a));
        plan.setTrackable(new com.github.liliumsoftware.optimization.support.TestTrackable("T1", 5));

        TestPlan result = twoOpt(calculation).apply(plan);

        assertThat(result.getNodes()).containsExactly(origin, a, b);
        assertThat(result.getTrackable()).as("a local-search move must not unassign the plan").isNotNull();
    }

    // ---------------------------------------------------------------- degenerate sizes

    @Test
    void routesTooShortToReorderComeBackUnchanged() {
        MatrixCostCalculation calculation = calc(List.of(origin, a, b));

        TestPlan depotOnly = new TestPlan("d", List.of(origin));
        TestPlan oneCustomer = new TestPlan("one", List.of(origin, a));
        TestPlan twoCustomers = new TestPlan("two", List.of(origin, a, b));   // already optimal

        assertThat(twoOpt(calculation).apply(depotOnly).getNodes()).containsExactly(origin);
        assertThat(twoOpt(calculation).apply(oneCustomer).getNodes()).containsExactly(origin, a);
        assertThat(twoOpt(calculation).apply(twoCustomers).getNodes()).containsExactly(origin, a, b);
    }

    @Test
    void zeroLengthEdgesLeaveNothingToImprove() {
        TestNode first = TestNode.customer("A", 5, 5, 1);
        TestNode second = TestNode.customer("B", 5, 5, 1);
        MatrixCostCalculation calculation = calc(List.of(origin, first, second));
        TestPlan plan = new TestPlan("p", List.of(origin, first, second));

        TestPlan result = twoOpt(calculation).apply(plan);

        assertThat(PlanInvariants.referenceCost(result)).isEqualTo(10);
        assertThat(result.getNodes()).containsExactlyInAnyOrder(origin, first, second);
    }

    // ---------------------------------------------------------------- feasibility gating

    @Test
    void anImprovingButInfeasibleMoveIsRefused() {
        MatrixCostCalculation calculation = calc(List.of(origin, a, b));
        // Reject exactly the improving ordering (A first): the cheaper route exists but is not allowed, so the
        // expensive-but-legal one must survive.
        FeasibilityChecker<TestPlan> aMayNotComeFirst = new FeasibilityChecker<>() {
            @Override
            public FeasibilityResult getResult(TestPlan plan) {
                if (!plan.getCustomers().getFirst().getCode().equals("A")) {
                    return FeasibilityResult.FEASIBLE;
                }
                return new FeasibilityResult() {
                    @Override
                    public String getCause() {
                        return "a.not.first";
                    }

                    @Override
                    public Object[] getParams() {
                        return new Object[0];
                    }
                };
            }

            @Override
            public String getMessage(String key, Object... params) {
                return key;
            }
        };
        TestPlan plan = new TestPlan("p", List.of(origin, b, a));

        TestPlan result = new TestOpenTwoOpt(calculation, aMayNotComeFirst).apply(plan);

        assertThat(result.getNodes()).containsExactly(origin, b, a);
        assertThat(PlanInvariants.referenceCost(result)).isEqualTo(30);
    }

    // ---------------------------------------------------------------- invariants over random instances

    @ParameterizedTest
    @ValueSource(longs = {3L, 17L, 99L, 2718L})
    void neverWorsensNeverBeatsTheOptimumAndSettlesAtALocalOptimum(long seed) {
        Random random = new Random(seed);
        for (int trial = 0; trial < 25; trial++) {
            int customerCount = 3 + random.nextInt(4);   // 3..6 keeps brute force cheap
            List<TestNode> customers = new ArrayList<>();
            for (int i = 0; i < customerCount; i++) {
                customers.add(TestNode.customer("C" + i, random.nextInt(60), random.nextInt(60), 1));
            }
            List<TestNode> all = new ArrayList<>();
            all.add(origin);
            all.addAll(customers);

            List<TestNode> shuffled = new ArrayList<>(customers);
            Collections.shuffle(shuffled, random);
            List<TestNode> initial = new ArrayList<>();
            initial.add(origin);
            initial.addAll(shuffled);

            TestPlan plan = new TestPlan("p", initial);
            long before = PlanInvariants.referenceCost(plan);
            MatrixCostCalculation calculation = calc(all);
            TestOpenTwoOpt twoOpt = twoOpt(calculation);

            TestPlan result = twoOpt.apply(plan.copy());
            long after = PlanInvariants.referenceCost(result);

            assertThat(after).as("never worsens (seed %d)", seed).isLessThanOrEqualTo(before);
            assertThat(after).as("never beats the brute-force optimum (seed %d)", seed)
                    .isGreaterThanOrEqualTo(bruteForceOptimum(customers));
            assertThat(result.getNodes()).as("still a permutation of the same stops (seed %d)", seed)
                    .containsExactlyInAnyOrderElementsOf(initial);
            assertThat(result.getNodes()).doesNotHaveDuplicates();
            assertThat(result.getNodes().getFirst()).isEqualTo(origin);

            // A local optimum: running again cannot improve further, and it terminates rather than oscillating.
            assertThat(PlanInvariants.referenceCost(twoOpt.apply(result.copy()))).isEqualTo(after);
        }
    }

    /**
     * Cheapest open path over all customer orderings with the origin fixed first — an oracle independent of the
     * algorithm and of the production cost matrix.
     */
    private long bruteForceOptimum(List<TestNode> customers) {
        return permutations(customers).stream().mapToLong(permutation -> {
            List<TestNode> path = new ArrayList<>();
            path.add(origin);
            path.addAll(permutation);
            return PlanInvariants.referenceCost(path);
        }).min().orElseThrow();
    }

    private <E> List<List<E>> permutations(List<E> items) {
        if (items.isEmpty()) {
            return List.of(List.of());
        }
        List<List<E>> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<E> rest = new ArrayList<>(items);
            E head = rest.remove(i);
            for (List<E> tail : permutations(rest)) {
                List<E> permutation = new ArrayList<>();
                permutation.add(head);
                permutation.addAll(tail);
                result.add(permutation);
            }
        }
        return result;
    }

}
