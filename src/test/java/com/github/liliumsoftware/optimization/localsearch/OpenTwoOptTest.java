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

import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.feasibility.FeasibilityChecker;
import com.github.liliumsoftware.optimization.feasibility.FeasibilityResult;
import com.github.liliumsoftware.optimization.support.AlwaysFeasibleChecker;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.PlanInvariants;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestOpenTwoOpt;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTwoOpt;
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

    /**
     * Independent local-optimum oracle for an open plan: none of the three candidates the open 2-opt builds for a
     * customer position {@code i} and a cut {@code j} (reverse {@code [i, j)}; move {@code [i, j)} behind the tail;
     * move it behind the reversed tail) is cheaper under the Manhattan reference cost.
     */
    private static void assertNoCandidateMoveIsCheaper(List<TestNode> nodes, long seed) {
        long cost = PlanInvariants.referenceCost(nodes);
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).getType() != Nodeable.Type.CUSTOMER) {
                continue;
            }
            for (int j = i + 2; j <= nodes.size(); j++) {
                List<TestNode> head = nodes.subList(0, i);
                List<TestNode> segment = nodes.subList(i, j);
                List<TestNode> tail = nodes.subList(j, nodes.size());
                List<TestNode> reversedSegment = new ArrayList<>(segment);
                Collections.reverse(reversedSegment);
                List<TestNode> reversedTail = new ArrayList<>(tail);
                Collections.reverse(reversedTail);
                for (List<List<TestNode>> parts : List.of(List.of(head, reversedSegment, tail),
                        List.of(head, tail, segment), List.of(head, reversedTail, segment))) {
                    List<TestNode> candidate = new ArrayList<>();
                    parts.forEach(candidate::addAll);
                    assertThat(PlanInvariants.referenceCost(candidate))
                            .as("candidate %s of %s (seed %d)", candidate, nodes, seed).isGreaterThanOrEqualTo(cost);
                }
            }
        }
    }

    private MatrixCostCalculation calc(List<TestNode> nodes) {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(nodes);
        return calculation;
    }

    // ---------------------------------------------------------------- known examples

    private TestOpenTwoOpt twoOpt(MatrixCostCalculation calculation) {
        return new TestOpenTwoOpt(calculation, new AlwaysFeasibleChecker());
    }

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

    // ---------------------------------------------------------------- structure

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

    // ---------------------------------------------------------------- degenerate sizes

    @Test
    void theAssignedResourceSurvivesAMove() {
        MatrixCostCalculation calculation = calc(List.of(origin, a, b));
        TestPlan plan = new TestPlan("p", List.of(origin, b, a));
        plan.setTrackable(new com.github.liliumsoftware.optimization.support.TestTrackable("T1", 5));

        TestPlan result = twoOpt(calculation).apply(plan);

        assertThat(result.getNodes()).containsExactly(origin, a, b);
        assertThat(result.getTrackable()).as("a local-search move must not unassign the plan").isNotNull();
    }

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

    // ---------------------------------------------------------------- feasibility gating

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

    // ---------------------------------------------------------------- relocation candidates

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

    @Test
    void movingASegmentBehindTheTailImprovesWhereNoReversalDoes() {
        // O(0,0) A(-10,-10) B(-10,0) C(10,0) D(10,-10) in the order O A B C D costs 20 + 10 + 20 + 10 = 60, and no
        // reversal of a segment is cheaper (the closed 2-opt keeps it). Moving [A, B] behind the tail gives O C D A B
        // = 10 + 10 + 20 + 10 = 50, which nothing improves further.
        TestNode first = TestNode.customer("A", -10, -10, 1);
        TestNode second = TestNode.customer("B", -10, 0, 1);
        TestNode third = TestNode.customer("C", 10, 0, 1);
        TestNode fourth = TestNode.customer("D", 10, -10, 1);
        MatrixCostCalculation calculation = calc(List.of(origin, first, second, third, fourth));
        TestPlan plan = new TestPlan("p", List.of(origin, first, second, third, fourth));

        TestPlan reversalsOnly = new TestTwoOpt(calculation, new AlwaysFeasibleChecker()).apply(plan.copy());
        TestPlan result = twoOpt(calculation).apply(plan);

        assertThat(PlanInvariants.referenceCost(reversalsOnly)).isEqualTo(60);
        assertThat(result.getNodes()).containsExactly(origin, third, fourth, first, second);
        assertThat(PlanInvariants.referenceCost(result)).isEqualTo(50);
    }

    @Test
    void movingASegmentBehindTheReversedTailImprovesWhereNeitherReversalNorPlainMoveDoes() {
        // O(0,0) A(10,0) B(20,0) C(10,10) D(0,10) in the order O A B C D costs 10 + 10 + 20 + 10 = 50. No reversal and
        // no plain move of a segment behind the tail is cheaper; [A, B] behind the reversed tail [D, C] gives the
        // loop O D C A B = 10 + 10 + 10 + 10 = 40.
        TestNode first = TestNode.customer("A", 10, 0, 1);
        TestNode second = TestNode.customer("B", 20, 0, 1);
        TestNode third = TestNode.customer("C", 10, 10, 1);
        TestNode fourth = TestNode.customer("D", 0, 10, 1);
        MatrixCostCalculation calculation = calc(List.of(origin, first, second, third, fourth));
        TestPlan plan = new TestPlan("p", List.of(origin, first, second, third, fourth));

        TestPlan reversalsOnly = new TestTwoOpt(calculation, new AlwaysFeasibleChecker()).apply(plan.copy());
        TestPlan result = twoOpt(calculation).apply(plan);

        assertThat(PlanInvariants.referenceCost(reversalsOnly)).isEqualTo(50);
        assertThat(result.getNodes()).containsExactly(origin, fourth, third, first, second);
        assertThat(PlanInvariants.referenceCost(result)).isEqualTo(40);
    }

    // ---------------------------------------------------------------- one step, three candidates

    @Test
    void anInfeasibleRelocationIsRefusedLikeAnInfeasibleReversal() {
        // The two instances above, with a checker that refuses everything: the cheaper relocated plans are proposed
        // and refused, so the input comes back as the very same instance.
        List<List<TestNode>> instances = List.of(
                List.of(origin, TestNode.customer("A", -10, -10, 1), TestNode.customer("B", -10, 0, 1),
                        TestNode.customer("C", 10, 0, 1), TestNode.customer("D", 10, -10, 1)),
                List.of(origin, TestNode.customer("A", 10, 0, 1), TestNode.customer("B", 20, 0, 1),
                        TestNode.customer("C", 10, 10, 1), TestNode.customer("D", 0, 10, 1)));
        for (List<TestNode> nodes : instances) {
            MatrixCostCalculation calculation = calc(nodes);
            int[] refused = new int[1];
            FeasibilityChecker<TestPlan> never = new FeasibilityChecker<>() {
                @Override
                public FeasibilityResult getResult(TestPlan plan) {
                    refused[0]++;
                    return new FeasibilityResult() {
                        @Override
                        public String getCause() {
                            return "never";
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
            TestPlan plan = new TestPlan("p", nodes);

            assertThat(new TestOpenTwoOpt(calculation, never).apply(plan)).isSameAs(plan);
            assertThat(plan.getNodes()).containsExactlyElementsOf(nodes);
            assertThat(refused[0]).as("the cheaper relocation was proposed").isPositive();
        }
    }

    /**
     * {@code O(0,0) A(10,30) B(0,20) C(40,10) D(0,10)}, starting from {@code [O, A, B, C, D]} = 40 + 20 + 50 + 40
     * = 150. The first step is {@code (i, j) = (1, 3)}, segment {@code [A, B]}: the reversal {@code [O, B, A, C, D]}
     * costs 130, the relocation {@code [O, C, D, A, B]} 140, and the reversed tail in front of the segment
     * {@code [O, D, C, A, B]} 120.
     */
    private List<TestNode> fivePointRoute(TestNode a, TestNode b, TestNode c, TestNode d) {
        return List.of(origin, a, b, c, d);
    }

    @Test
    void allThreeCandidatesOfAStepAreBuiltFromTheSamePlanAndTheCheapestWins() {
        TestNode a = TestNode.customer("A", 10, 30, 1);
        TestNode b = TestNode.customer("B", 0, 20, 1);
        TestNode c = TestNode.customer("C", 40, 10, 1);
        TestNode d = TestNode.customer("D", 0, 10, 1);
        MatrixCostCalculation calculation = calc(List.of(origin, a, b, c, d));
        RecordingOpenTwoOpt twoOpt = new RecordingOpenTwoOpt(calculation);

        twoOpt.apply(new TestPlan("p", fivePointRoute(a, b, c, d)));

        // The third candidate is built from the input, not from the reversal the first candidate produced (which would
        // give [O, D, C, B, A]), and of the three improving candidates the cheapest, the third (120), is taken.
        assertThat(twoOpt.candidates.subList(0, 4)).containsExactly(List.of("O", "B", "A", "C", "D"),
                List.of("O", "C", "D", "A", "B"), List.of("O", "D", "C", "A", "B"), List.of("O", "C", "D", "A", "B"));
    }

    @Test
    void keepingTheCheapestCandidateOfEachStepReachesTheOptimumOfTheFivePointRoute() {
        // From [O, D, C, A, B] (120) the search continues to [O, D, B, A, C] = 10 + 10 + 20 + 40 = 90, the brute-force
        // optimum. Building the third candidate from the first one instead takes [O, D, C, B, A] (120) and ends at
        // [O, B, A, D, C] (110).
        TestNode a = TestNode.customer("A", 10, 30, 1);
        TestNode b = TestNode.customer("B", 0, 20, 1);
        TestNode c = TestNode.customer("C", 40, 10, 1);
        TestNode d = TestNode.customer("D", 0, 10, 1);
        MatrixCostCalculation calculation = calc(List.of(origin, a, b, c, d));

        TestPlan result = twoOpt(calculation).apply(new TestPlan("p", fivePointRoute(a, b, c, d)));

        assertThat(result.getNodes()).containsExactly(origin, d, b, a, c);
        assertThat(PlanInvariants.referenceCost(result)).isEqualTo(90)
                .isEqualTo(bruteForceOptimum(List.of(a, b, c, d)));
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
            assertNoCandidateMoveIsCheaper(result.getNodes(), seed);
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

    /**
     * Open 2-opt that records the stop order of every candidate it builds.
     */
    private static final class RecordingOpenTwoOpt extends AbstractOpenTwoOptAlgorithm<TestPlan, TestNode> {

        private final List<List<String>> candidates = new ArrayList<>();

        private RecordingOpenTwoOpt(MatrixCostCalculation calculation) {
            super(calculation, new AlwaysFeasibleChecker());
        }

        @Override
        protected TestPlan getPlan(TestPlan plan, List<TestNode> nodes) {
            candidates.add(nodes.stream().map(TestNode::getCode).toList());
            return plan.withNodes(nodes);
        }

    }

}
