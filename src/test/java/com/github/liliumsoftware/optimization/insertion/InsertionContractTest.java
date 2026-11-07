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

package com.github.liliumsoftware.optimization.insertion;

import com.github.liliumsoftware.optimization.feasibility.FeasibilityChecker;
import com.github.liliumsoftware.optimization.feasibility.FeasibilityResult;
import com.github.liliumsoftware.optimization.support.AlwaysFeasibleChecker;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.MaxCustomersFeasibilityChecker;
import com.github.liliumsoftware.optimization.support.PlanInvariants;
import com.github.liliumsoftware.optimization.support.TestInsertion;
import com.github.liliumsoftware.optimization.support.TestInsertionCandidate;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the slot search and the two batch policies of {@link AbstractInsertionAlgorithm} with hand-computed cases:
 * the feasibility checker is consulted only for slots that beat the best feasible slot so far (without changing which
 * slot wins), regret and cheapest insertion produce different routes where they should, regret ties break by the best
 * delta and then by code, a node comparator overrides the regret ranking, and the {@code index} restriction is
 * honoured.
 *
 * <p>The slot cases use the open plan {@code O(0,0), A(0,10), B(0,20), C(0,30)}: its four slots (after O, A, B and
 * C) cost {@code c(prev,x) + c(x,next) − c(prev,next)}, or {@code c(C,x)} for the last one.
 */
class InsertionContractTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode a = TestNode.customer("A", 0, 10, 1);
    private final TestNode b = TestNode.customer("B", 0, 20, 1);
    private final TestNode c = TestNode.customer("C", 0, 30, 1);
    // Regret cases: two open plans P1 = O, N1(0,100) and P2 = O, S1(0,-100), each taking one more customer. Y(0,10)
    // costs 0 in P1 and 20 in P2 (regret 20); X(0,120) costs 20 in P1 and 220 in P2 (regret 200).
    private final TestNode north = TestNode.customer("N1", 0, 100, 1);
    private final TestNode south = TestNode.customer("S1", 0, -100, 1);
    private final TestNode x = TestNode.customer("X", 0, 120, 1);
    private final TestNode y = TestNode.customer("Y", 0, 10, 1);

    private static MatrixCostCalculation calculation(List<TestNode> nodes) {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(nodes);
        return calculation;
    }

    private static String route(TestPlan plan) {
        return plan.getCustomers().stream().map(TestNode::getCode).collect(Collectors.joining("-"));
    }

    /**
     * The nodes {@code O, ...} of a route spelled as customer codes, resolving {@code extra} by its code.
     */
    private List<TestNode> nodes(String route, TestNode extra) {
        List<TestNode> nodes = new ArrayList<>(List.of(origin));
        for (String code : route.split("-")) {
            nodes.add(switch (code) {
                case "A" -> a;
                case "B" -> b;
                case "C" -> c;
                default -> extra;
            });
        }
        return nodes;
    }

    private TestPlan line() {
        return new TestPlan("line", List.of(origin, a, b, c));
    }

    // ---------------------------------------------------------------- feasibility only where it can matter

    @ParameterizedTest
    @CsvSource({
            // X(0,5): slot deltas 0, 10, 30, 25 -> the first slot is the best, no later slot beats it: one check
            "X, 5, 1, X-A-B-C, 0",
            // M(0,15): deltas 10, 0, 10, 15 -> checks for the first slot and the strictly cheaper second one only
            "M, 15, 2, A-M-B-C, 0",
            // Y(0,40): deltas 60, 40, 20, 10 -> every slot beats the previous one, so all four are checked
            "Y, 40, 4, A-B-C-Y, 10"
    })
    void theCheckerIsConsultedOnlyForSlotsThatBeatTheBestSoFar(String code, int position, int expectedChecks,
            String expectedRoute, long expectedDelta) {
        TestNode customer = TestNode.customer(code, 0, position, 1);
        CountingChecker checker = new CountingChecker(plan -> true);
        TestInsertion insertion = new TestInsertion(calculation(List.of(origin, a, b, c, customer)), checker, false);

        TestInsertionCandidate candidate = insertion.getBestInsertionCandidate(List.of(line()), customer);

        assertThat(candidate.getCandidatePlan()).isNotNull();
        assertThat(route(candidate.getCandidatePlan())).isEqualTo(expectedRoute);
        assertThat(candidate.getDeltaCost()).isEqualTo(expectedDelta);
        assertThat(checker.checks).isEqualTo(expectedChecks);
    }

    @ParameterizedTest
    @CsvSource({
            // M directly after A is refused: slot 1 (10) is checked and kept, slot 2 (0) is checked and refused, slot 3
            // ties slot 1 and is not checked, the earlier slot wins
            "M, 15, after-A, A-M-B-C, 2, M-A-B-C, 10",
            // M in front of B is refused: slots 1 and 2 are checked and refused, slot 3 (10) is checked and kept,
            // slot 4 (15) cannot beat it
            "M, 15, before-B, A-M-B-C, 3, A-B-M-C, 10",
            // Y last is refused: the cheapest slot 4 (10) is checked last and refused, slot 3 (20) stays the best
            "Y, 40, last, A-B-C-Y, 4, A-B-Y-C, 20"
    })
    void theCheapestFeasibleSlotWinsWhenACheaperSlotIsInfeasible(String code, int position, String rule,
            String cheapestRoute, int expectedChecks, String expectedRoute, long expectedDelta) {
        TestNode customer = TestNode.customer(code, 0, position, 1);
        Predicate<TestPlan> feasible = switch (rule) {
            case "after-A" -> plan -> !route(plan).contains("A-" + code);
            case "before-B" -> plan -> route(plan).indexOf(code) > route(plan).indexOf("B");
            default -> plan -> !route(plan).endsWith(code);
        };
        assertThat(feasible.test(new TestPlan("cheapest", nodes(cheapestRoute, customer)))).isFalse();
        CountingChecker checker = new CountingChecker(feasible);
        TestInsertion insertion = new TestInsertion(calculation(List.of(origin, a, b, c, customer)), checker, false);

        TestInsertionCandidate candidate = insertion.getBestInsertionCandidate(List.of(line()), customer);

        assertThat(candidate.getCandidatePlan()).isNotNull();
        assertThat(route(candidate.getCandidatePlan())).isEqualTo(expectedRoute);
        assertThat(candidate.getDeltaCost()).isEqualTo(expectedDelta);
        assertThat(checker.checks).isEqualTo(expectedChecks);
    }

    @Test
    void theChosenSlotIsTheEarliestCheapestFeasibleOneForRandomPlansAndRules() {
        // Independent oracle: probe every slot, keep the feasible ones, take the smallest delta (earliest on ties).
        Random random = new Random(20261031L);
        for (int round = 0; round < 300; round++) {
            List<TestNode> nodes = new ArrayList<>(List.of(origin));
            List<TestNode> all = new ArrayList<>(List.of(origin));
            int size = 1 + random.nextInt(5);
            for (int i = 0; i < size; i++) {
                TestNode customer = TestNode.customer("C" + i, random.nextInt(41) - 20, random.nextInt(41) - 20, 1);
                nodes.add(customer);
                all.add(customer);
            }
            TestNode added = TestNode.customer("N", random.nextInt(41) - 20, random.nextInt(41) - 20, 1);
            all.add(added);
            TestPlan plan = new TestPlan("p", nodes);
            Set<Integer> refused = new HashSet<>();
            for (int slot = 1; slot <= size + 1; slot++) {
                if (random.nextInt(3) == 0) {
                    refused.add(slot);
                }
            }
            Predicate<TestPlan> feasible = p -> !refused.contains(p.getNodes().indexOf(added));
            TestInsertion insertion = new TestInsertion(calculation(all), new CountingChecker(feasible), false);

            TestInsertionCandidate candidate = insertion.getBestInsertionCandidate(List.of(plan), added);

            List<TestNode> expected = null;
            long expectedDelta = Long.MAX_VALUE;
            for (int slot = 1; slot <= size + 1; slot++) {
                List<TestNode> probe = new ArrayList<>(nodes);
                probe.add(slot, added);
                long delta = PlanInvariants.referenceCost(probe) - PlanInvariants.referenceCost(nodes);
                if (!refused.contains(slot) && delta < expectedDelta) {
                    expected = probe;
                    expectedDelta = delta;
                }
            }
            if (expected == null) {
                assertThat(candidate).as("round %s", round).isNull();
            } else {
                assertThat(candidate.getCandidatePlan()).as("round %s", round).isNotNull();
                assertThat(candidate.getCandidatePlan().getNodes()).as("round %s", round).isEqualTo(expected);
                assertThat(candidate.getDeltaCost()).as("round %s", round).isEqualTo(expectedDelta);
            }
        }
    }

    @Test
    void aPlanWithoutAnyStopReceivesTheCustomerAsItsOnlyStop() {
        TestNode customer = TestNode.customer("X", 0, 5, 1);
        TestInsertion insertion = new TestInsertion(calculation(List.of(origin, customer)),
                new AlwaysFeasibleChecker(), false);

        TestInsertionCandidate candidate = insertion.getBestInsertionCandidate(
                List.of(new TestPlan("bare", List.of())), customer);

        assertThat(candidate.getCandidatePlan()).isNotNull();
        assertThat(candidate.getCandidatePlan().getNodes()).containsExactly(customer);
        assertThat(candidate.getDeltaCost()).isZero();
    }

    // ---------------------------------------------------------------- regret against cheapest insertion

    private List<TestPlan> northSouth() {
        return new ArrayList<>(List.of(new TestPlan("P1", List.of(origin, north)),
                new TestPlan("P2", List.of(origin, south))));
    }

    private TestInsertion northSouthInsertion(boolean regret, Comparator<TestNode> nodeComparator) {
        return new TestInsertion(calculation(List.of(origin, north, south, x, y)),
                new MaxCustomersFeasibilityChecker(2), regret) {
            @Override
            protected Comparator<TestNode> nodeComparator() {
                return nodeComparator;
            }
        };
    }

    @Test
    void cheapestInsertionPlacesCustomersInBatchOrder() {
        // Y first takes P1 for free; X no longer fits P1 and pays 220 in P2: total 100 + 320 = 420.
        List<TestPlan> plans = northSouth();

        List<TestNode> inserted = northSouthInsertion(false, null).insert(plans, List.of(y, x));

        assertThat(inserted).containsExactly(y, x);
        assertThat(plans).extracting(InsertionContractTest::route).containsExactly("Y-N1", "S1-X");
        assertThat(PlanInvariants.referenceTotalCost(plans)).isEqualTo(420);
    }

    @Test
    void regretInsertionPlacesTheCustomerWithTheMostToLoseFirst() {
        // X (regret 200) goes first and takes P1 for 20; Y then pays 20 in P2: total 120 + 120 = 240.
        List<TestPlan> plans = northSouth();

        List<TestNode> inserted = northSouthInsertion(true, null).insert(plans, List.of(y, x));

        assertThat(inserted).containsExactly(x, y);
        assertThat(plans).extracting(InsertionContractTest::route).containsExactly("N1-X", "Y-S1");
        assertThat(PlanInvariants.referenceTotalCost(plans)).isEqualTo(240);
    }

    @Test
    void aNodeComparatorOverridesTheRegretRankingAndOnlyItsTiesAreLeftToRegret() {
        List<TestPlan> yFirst = northSouth();
        northSouthInsertion(true, Comparator.comparing(TestNode::getCode).reversed()).insert(yFirst, List.of(x, y));
        assertThat(yFirst).as("Y is ordered first although X has the larger regret")
                .extracting(InsertionContractTest::route).containsExactly("Y-N1", "S1-X");

        List<TestPlan> tied = northSouth();
        northSouthInsertion(true, (left, right) -> 0).insert(tied, List.of(y, x));
        assertThat(tied).as("a comparator that ranks everything equal leaves the order to regret")
                .extracting(InsertionContractTest::route).containsExactly("N1-X", "Y-S1");
    }

    @Test
    void aNodeComparatorOrdersTheCheapestInsertionBatch() {
        List<TestPlan> inputOrder = northSouth();
        northSouthInsertion(false, null).insert(inputOrder, List.of(x, y));
        assertThat(inputOrder).extracting(InsertionContractTest::route).containsExactly("N1-X", "Y-S1");

        List<TestPlan> sorted = northSouth();
        List<TestNode> inserted = northSouthInsertion(false, Comparator.comparing(TestNode::getCode).reversed())
                .insert(sorted, List.of(x, y));
        assertThat(inserted).containsExactly(y, x);
        assertThat(sorted).extracting(InsertionContractTest::route).containsExactly("Y-N1", "S1-X");
    }

    @Test
    void equalRegretsAndEqualDeltasAreBrokenByCustomerCode() {
        // One plan with room for one customer: both customers have a single candidate, so both regrets are 0, and both
        // cost 10, so the smaller code goes first; cheapest insertion keeps the batch order instead.
        TestNode first = TestNode.customer("B", 0, 10, 1);
        TestNode second = TestNode.customer("A", 10, 0, 1);
        MatrixCostCalculation calculation = calculation(List.of(origin, first, second));
        for (boolean regret : new boolean[] {true, false}) {
            List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p", List.of(origin))));

            List<TestNode> inserted = new TestInsertion(calculation, new MaxCustomersFeasibilityChecker(1), regret)
                    .insert(plans, List.of(first, second));

            assertThat(inserted).as("regret=%s", regret).containsExactly(regret ? second : first);
            assertThat(route(plans.getFirst())).as("regret=%s", regret).isEqualTo(regret ? "A" : "B");
        }
    }

    @Test
    void equalRegretsGoToTheCheaperInsertionFirst() {
        // One closed plan O-D with room for two customers: every regret is 0. By code alone AA (50,50) went first and
        // took a slot for 200, leaving room for Y only. Ranked by their best delta, Z (2) goes first, then Y (2, in
        // the earliest of its equally cheap slots, in front of Z), and AA no longer fits: O-Y-Z-D costs 4.
        TestNode depotEnd = TestNode.destination("D", 0, 0);
        TestNode z = TestNode.customer("z", 1, 0, 1);
        TestNode yy = TestNode.customer("y", 2, 0, 1);
        TestNode aa = TestNode.customer("aa", 50, 50, 1);
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p", List.of(origin, depotEnd))));
        TestInsertion insertion = new TestInsertion(calculation(List.of(origin, depotEnd, z, yy, aa)),
                new MaxCustomersFeasibilityChecker(2), true);

        List<TestNode> inserted = insertion.insert(plans, List.of(aa, yy, z));

        assertThat(inserted).containsExactly(z, yy);
        assertThat(route(plans.getFirst())).isEqualTo("y-z");
        assertThat(PlanInvariants.referenceTotalCost(plans)).isEqualTo(4);
    }

    // ---------------------------------------------------------------- batch contract

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aBatchCanBeRestrictedToSpecificPlans(boolean regret) {
        TestNode middle = TestNode.customer("M", 0, 15, 1);
        TestNode far = TestNode.customer("F", 100, 0, 1);
        List<TestPlan> plans = new ArrayList<>(List.of(line(), new TestPlan("far", List.of(origin, far))));
        TestInsertion insertion = new TestInsertion(calculation(List.of(origin, a, b, c, middle, far)),
                new AlwaysFeasibleChecker(), regret);

        // M would cost 0 in the line plan; index 1 confines it to the far plan: c(O,M) + c(M,F) - c(O,F) = 30
        assertThat(insertion.insert(plans, List.of(middle), 1)).containsExactly(middle);

        assertThat(route(plans.get(0))).isEqualTo("A-B-C");
        assertThat(route(plans.get(1))).isEqualTo("M-F");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anIndexOutsideThePlanListIsRejected(boolean regret) {
        TestNode middle = TestNode.customer("M", 0, 15, 1);
        List<TestPlan> plans = new ArrayList<>(List.of(line()));
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);
        TestInsertion insertion = new TestInsertion(calculation(List.of(origin, a, b, c, middle)),
                new AlwaysFeasibleChecker(), regret);

        assertThatThrownBy(() -> insertion.insert(plans, middle, 1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> insertion.insert(plans, List.of(middle), -1))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThat(plans).isEqualTo(snapshot);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void theInsertedNodesReturnedAreTheCopiesPlacedInThePlans(boolean regret) {
        TestNode middle = TestNode.customer("M", 0, 15, 1);
        TestNode far = TestNode.customer("F", 0, 100, 1);
        List<TestPlan> plans = new ArrayList<>(List.of(line()));
        TestInsertion insertion = new TestInsertion(calculation(List.of(origin, a, b, c, middle, far)),
                new AlwaysFeasibleChecker(), regret);

        List<TestNode> inserted = insertion.insert(plans, List.of(far, middle));

        // Cheapest insertion places F first (batch order), then M; regret insertion, whose regrets are all 0 with a
        // single plan, places the cheaper M (delta 0) before F (delta 70). Each returned node is the copy that went
        // into the plan, never the caller's instance; the last one is still the very instance on the route (an
        // earlier one sits in a plan that the later insertion copied again).
        assertThat(inserted).containsExactly(regret ? middle : far, regret ? far : middle);
        assertThat(inserted.get(0)).isNotSameAs(inserted.get(0).equals(far) ? far : middle);
        assertThat(inserted.get(1)).isNotSameAs(inserted.get(1).equals(far) ? far : middle);
        assertThat(plans.getFirst().getNodes().get(regret ? 5 : 2)).isSameAs(inserted.get(1));
        assertThat(route(plans.getFirst())).isEqualTo("A-M-B-C-F");
    }

    /**
     * Feasibility checker over a predicate that counts how often it is asked.
     */
    private static final class CountingChecker implements FeasibilityChecker<TestPlan> {

        private final Predicate<TestPlan> feasible;
        private int checks;

        CountingChecker(Predicate<TestPlan> feasible) {
            this.feasible = feasible;
        }

        @Override
        public FeasibilityResult getResult(TestPlan plan) {
            checks++;
            if (feasible.test(plan)) {
                return FeasibilityResult.FEASIBLE;
            }
            return new FeasibilityResult() {
                @Override
                public String getCause() {
                    return "refused";
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

    }

}
