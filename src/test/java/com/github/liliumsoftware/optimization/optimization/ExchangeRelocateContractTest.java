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

import com.github.liliumsoftware.optimization.calculation.CostCalculation;
import com.github.liliumsoftware.optimization.exception.CancelException;
import com.github.liliumsoftware.optimization.feasibility.FeasibilityChecker;
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;
import com.github.liliumsoftware.optimization.support.AlwaysFeasibleChecker;
import com.github.liliumsoftware.optimization.support.CancelSpyLocalSearch;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.MaxCustomersFeasibilityChecker;
import com.github.liliumsoftware.optimization.support.PlanInvariants;
import com.github.liliumsoftware.optimization.support.RecostingLocalSearch;
import com.github.liliumsoftware.optimization.support.TestAssignment;
import com.github.liliumsoftware.optimization.support.TestExchange;
import com.github.liliumsoftware.optimization.support.TestInsertion;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestOpenTwoOpt;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestRelocate;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the exchange and relocate contracts with exact, hand-checked outcomes on the two-cluster line
 * {@code O(0,0) N1(10,0) N2(20,0) F1(1000,0) F2(1010,0)} (open routes, Manhattan cost): every strategy strictly
 * improves a crafted instance, {@code cross} widens the second plan's segment, partial insertions are rejected, the
 * caller's plan objects are never modified, the plans' equality contract does not change the result, and the
 * cancel/interrupt signals stop the search and reach the nested local search and insertion.
 */
class ExchangeRelocateContractTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode near1 = TestNode.customer("N1", 10, 0, 1);
    private final TestNode near2 = TestNode.customer("N2", 20, 0, 1);
    private final TestNode far1 = TestNode.customer("F1", 1000, 0, 1);
    private final TestNode far2 = TestNode.customer("F2", 1010, 0, 1);
    private final List<TestNode> customers = List.of(near1, near2, far1, far2);

    private static List<List<String>> routes(List<TestPlan> plans) {
        return plans.stream().map(plan -> {
            List<String> route = new ArrayList<>(List.of(plan.getCode()));
            plan.getNodes().forEach(node -> route.add(node.getCode()));
            return route;
        }).toList();
    }

    private MatrixCostCalculation calculation() {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, near1, near2, far1, far2));
        return calculation;
    }

    private TestAssignment fleet() {
        return new TestAssignment(List.of(new TestTrackable("T1", 5), new TestTrackable("T2", 5)));
    }

    /**
     * Two assigned open plans {@code p1} and {@code p2} with the given customers.
     */
    private List<TestPlan> plans(List<TestNode> first, List<TestNode> second) {
        List<TestNode> firstNodes = new ArrayList<>(List.of(origin));
        firstNodes.addAll(first);
        List<TestNode> secondNodes = new ArrayList<>(List.of(origin));
        secondNodes.addAll(second);
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", firstNodes),
                new TestPlan("p2", secondNodes)));
        assertThat(fleet().assign(plans)).isTrue();
        return plans;
    }

    /**
     * As {@link #plans(List, List)}, with {@code p2} costing ten times its length.
     */
    private List<TestPlan> weighted(List<TestNode> first, List<TestNode> second) {
        List<TestPlan> plans = plans(first, second);
        plans.get(1).setCostMultiplier(10);
        return plans;
    }

    /**
     * {@code [O, N1, F1] + [O, N2, F2]}: (10 + 990) + (20 + 990) = 2010, both routes cross the gap.
     */
    private List<TestPlan> crossed() {
        return plans(List.of(near1, far1), List.of(near2, far2));
    }

    /**
     * {@code [O, N1, N2, F1] + [O, F2]}: (10 + 10 + 980) + 1010 = 2010, F1 is parked on the near route.
     */
    private List<TestPlan> misplaced() {
        return plans(List.of(near1, near2, far1), List.of(far2));
    }

    private TestInsertion insertion(MatrixCostCalculation calculation, FeasibilityChecker<TestPlan> checker) {
        return new TestInsertion(calculation, checker, false);
    }

    private TestExchange exchange(MatrixCostCalculation calculation, ExchangeRelocateType type, int chain,
            boolean cross) {
        return new TestExchange(calculation, fleet(), new RecostingLocalSearch(calculation),
                insertion(calculation, new AlwaysFeasibleChecker()), type, 4, chain, cross);
    }

    private TestRelocate relocate(MatrixCostCalculation calculation, ExchangeRelocateType type, int chain) {
        return new TestRelocate(calculation, fleet(), new RecostingLocalSearch(calculation),
                insertion(calculation, new AlwaysFeasibleChecker()), type, 4, chain);
    }

    // ---------------------------------------------------------------- every strategy strictly improves

    @ParameterizedTest
    @CsvSource({"SIMPLE, false, 1050", "TWO_OPT, false, 1050", "TWO_OPT, true, 1030", "INSERTION, false, 1030"})
    void everyExchangeStrategyStrictlyImprovesTheCrossedPlans(ExchangeRelocateType type, boolean realTwoOpt,
            long expected) {
        // Chain 1 swaps single stops. N1 <-> N2 keeps 2010; N1 <-> F2 is the first improving swap. SIMPLE/TWO_OPT put
        // each stop where the other was: [O, F2, F1] (1010 + 10) + [O, N2, N1] (20 + 10) = 1050, and a real 2-opt as
        // the TWO_OPT local search then straightens both routes to [O, F1, F2] + [O, N1, N2] = 1010 + 20 = 1030, which
        // INSERTION reaches directly by inserting each stop at its cheapest slot.
        MatrixCostCalculation calculation = calculation();
        LocalSearchAlgorithm<TestPlan> localSearch = realTwoOpt
                ? new TestOpenTwoOpt(calculation, new AlwaysFeasibleChecker()) : new RecostingLocalSearch(calculation);
        List<TestPlan> plans = crossed();
        long before = PlanInvariants.referenceTotalCost(plans);

        List<TestPlan> result = new TestExchange(calculation, fleet(), localSearch,
                insertion(calculation, new AlwaysFeasibleChecker()), type, 4, 1, true).apply(plans);

        assertThat(before).isEqualTo(2010);
        assertThat(PlanInvariants.referenceTotalCost(result)).isLessThan(before).isEqualTo(expected);
        assertThat(result).extracting(TestPlan::getCustomerCount).containsExactly(2, 2);
        PlanInvariants.assertValidSolution(result, origin, null, customers);
    }

    @ParameterizedTest
    @EnumSource(ExchangeRelocateType.class)
    void everyRelocateStrategyStrictlyImprovesTheMisplacedPlansDownToTheOptimum(ExchangeRelocateType type) {
        // Any route reaching F2 costs at least 1010, so the single route [O, N1, N2, F1, F2] (1010) is optimal; each
        // strategy gets there one stop at a time (F1, then N2, then N1, which empties and drops p1).
        MatrixCostCalculation calculation = calculation();
        List<TestPlan> plans = misplaced();
        long before = PlanInvariants.referenceTotalCost(plans);

        List<TestPlan> result = relocate(calculation, type, 1).apply(plans);

        assertThat(before).isEqualTo(2010);
        assertThat(PlanInvariants.referenceTotalCost(result)).isLessThan(before).isEqualTo(1010);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getNodes()).containsExactly(origin, near1, near2, far1, far2);
        PlanInvariants.assertValidSolution(result, origin, null, customers);
    }

    // ---------------------------------------------------------------- cross

    @Test
    void withoutCrossEitherPlanOfAPairMayGiveUpTheMultiStopSegment() {
        // [O, F1, F2, N1] (1000 + 10 + 1000) + [O, N2] (20) = 2030. The first supported move swaps [F1, F2] against N2:
        // [O, N2, N1] (30) + [O, F1, F2] (1010) = 1040. Both orientations of a pair are tried, so the later plan's
        // [F1, F2] may then be swapped against the earlier plan's single N1 even without cross:
        // [O, N2, F1, F2] (20 + 980 + 10) + [O, N1] (10) = 1020, the result cross reaches as well.
        MatrixCostCalculation calculation = calculation();

        List<TestPlan> withoutCross = exchange(calculation, ExchangeRelocateType.SIMPLE, 2, false)
                .apply(plans(List.of(far1, far2, near1), List.of(near2)));
        List<TestPlan> withCross = exchange(calculation, ExchangeRelocateType.SIMPLE, 2, true)
                .apply(plans(List.of(far1, far2, near1), List.of(near2)));

        assertThat(routes(withoutCross)).containsExactly(List.of("p1", "O", "N2", "F1", "F2"),
                List.of("p2", "O", "N1"));
        assertThat(PlanInvariants.referenceTotalCost(withoutCross)).isEqualTo(1020);
        assertThat(routes(withCross)).containsExactly(List.of("p1", "O", "N2", "F1", "F2"), List.of("p2", "O", "N1"));
        assertThat(PlanInvariants.referenceTotalCost(withCross)).isEqualTo(1020);
    }

    @Test
    void withoutCrossTheLaterPlanMayGiveUpTheMultiStopSegment() {
        // The mirror image: [O, N2] (20) + [O, F1, F2, N1] (1000 + 10 + 1000) = 2030, the multi-stop segment sits in
        // the later plan. Taking chains from the earlier plan only, the best a scan finds is N2 <-> N1: [O, N1] (10)
        // + [O, F1, F2, N2] (1000 + 10 + 990) = 2010. With both orientations the first start pair (N2 against F1)
        // already swaps the later plan's [F1, F2] against N2: [O, F1, F2] (1010) + [O, N2, N1] (30) = 1040; then
        // [F1, F2], now in the earlier plan, goes against N1: [O, N1] (10) + [O, N2, F1, F2] (20 + 980 + 10) = 1020.
        MatrixCostCalculation calculation = calculation();

        List<TestPlan> result = exchange(calculation, ExchangeRelocateType.SIMPLE, 2, false)
                .apply(plans(List.of(near2), List.of(far1, far2, near1)));

        assertThat(routes(result)).containsExactly(List.of("p1", "O", "N1"), List.of("p2", "O", "N2", "F1", "F2"));
        assertThat(PlanInvariants.referenceTotalCost(result)).isEqualTo(1020);
        PlanInvariants.assertValidSolution(result, origin, null, customers);
    }

    @Test
    void onlyCrossSwapsTwoMultiStopSegments() {
        // p2 costs ten times its length: [O, N1, N2] (20) + 10 * [O, F1, F2] (10 * 1010) = 10120. With cross the first
        // start pair swaps [N1, N2] against [F1, F2]: [O, F1, F2] (1010) + 10 * [O, N1, N2] (200) = 1210. Without
        // cross one side is a single customer: every 1-for-1 swap and every [N1, N2] against one far stop costs more,
        // and the best a single-stop side allows is [F1, F2] against N1: [O, F1, F2, N2] (1000 + 10 + 990)
        // + 10 * [O, N1] (100) = 2100, which nothing improves on.
        MatrixCostCalculation calculation = calculation();

        List<TestPlan> withoutCross = exchange(calculation, ExchangeRelocateType.SIMPLE, 2, false)
                .apply(weighted(List.of(near1, near2), List.of(far1, far2)));
        List<TestPlan> withCross = exchange(calculation, ExchangeRelocateType.SIMPLE, 2, true)
                .apply(weighted(List.of(near1, near2), List.of(far1, far2)));

        assertThat(routes(withoutCross)).containsExactly(List.of("p1", "O", "F1", "F2", "N2"),
                List.of("p2", "O", "N1"));
        assertThat(CostCalculation.totalCost(withoutCross)).isEqualTo(2100);
        assertThat(routes(withCross)).containsExactly(List.of("p1", "O", "F1", "F2"), List.of("p2", "O", "N1", "N2"));
        assertThat(CostCalculation.totalCost(withCross)).isEqualTo(1210);
    }

    // ---------------------------------------------------------------- partial insertion

    @Test
    void anInsertionExchangeWhoseSegmentsCannotAllBeReinsertedIsRejected() {
        // At most two customers per plan: swapping two stops against one leaves a segment the insertion cannot place
        // (either the first or the second re-insertion comes back short). Such a move would drop a customer and look
        // cheaper, so it must be rejected; the 1-for-1 swap N1 <-> F2 stays legal and gives [O, F1, F2] + [O, N1, N2]
        // = 1010 + 20 = 1030.
        MatrixCostCalculation calculation = calculation();
        List<TestPlan> plans = crossed();

        List<TestPlan> result = new TestExchange(calculation, fleet(), new RecostingLocalSearch(calculation),
                insertion(calculation, new MaxCustomersFeasibilityChecker(2)), ExchangeRelocateType.INSERTION, 4, 2,
                true).apply(plans);

        assertThat(PlanInvariants.referenceTotalCost(result)).isEqualTo(1030);
        assertThat(result).extracting(TestPlan::getCustomerCount).containsExactly(2, 2);
        PlanInvariants.assertValidSolution(result, origin, null, customers);
    }

    @Test
    void anInsertionRelocateWhoseSegmentCannotAllBeReinsertedIsRejected() {
        // At most three customers per plan: a two-stop segment never fits into the other two-customer plan, so those
        // relocations are rejected rather than losing a stop. Single stops still move: [O, N1] + [O, N2, F1, F2]
        // = 10 + (20 + 980 + 10) = 1020, and N1 can no longer join the full plan.
        MatrixCostCalculation calculation = calculation();

        List<TestPlan> result = new TestRelocate(calculation, fleet(), new RecostingLocalSearch(calculation),
                insertion(calculation, new MaxCustomersFeasibilityChecker(3)), ExchangeRelocateType.INSERTION, 4, 2)
                .apply(crossed());

        assertThat(routes(result)).containsExactly(List.of("p1", "O", "N1"), List.of("p2", "O", "N2", "F1", "F2"));
        assertThat(PlanInvariants.referenceTotalCost(result)).isEqualTo(1020);
        PlanInvariants.assertValidSolution(result, origin, null, customers);
    }

    // ---------------------------------------------------------------- the caller's plans

    @ParameterizedTest
    @EnumSource(ExchangeRelocateType.class)
    void theCallersPlanObjectsAreNeverModified(ExchangeRelocateType type) {
        MatrixCostCalculation calculation = calculation();
        for (boolean relocate : new boolean[] {false, true}) {
            List<TestPlan> plans = relocate ? misplaced() : crossed();
            List<TestPlan> originals = List.copyOf(plans);
            List<TestPlan> snapshots = PlanInvariants.deepCopy(plans);

            List<TestPlan> result = relocate ? relocate(calculation, type, 2).apply(plans)
                    : exchange(calculation, type, 2, true).apply(plans);

            assertThat(PlanInvariants.referenceTotalCost(result)).as("%s relocate=%s improved", type, relocate)
                    .isLessThan(PlanInvariants.referenceTotalCost(snapshots));
            for (int i = 0; i < originals.size(); i++) {
                assertThat(originals.get(i).getNodes()).as("%s relocate=%s, caller's plan %d", type, relocate, i)
                        .containsExactlyElementsOf(snapshots.get(i).getNodes());
                assertThat(originals.get(i)).isEqualTo(snapshots.get(i));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ExchangeRelocateType.class)
    void theResultDoesNotDependOnThePlansEqualityContract(ExchangeRelocateType type) {
        MatrixCostCalculation calculation = calculation();
        List<List<List<String>>> exchanged = new ArrayList<>();
        List<List<List<String>>> relocated = new ArrayList<>();
        for (TestPlan.Equality equality : TestPlan.Equality.values()) {
            List<TestPlan> exchangePlans = plans(List.of(far1, far2, near1), List.of(near2));
            exchangePlans.forEach(plan -> plan.setEquality(equality));
            List<TestPlan> relocatePlans = misplaced();
            relocatePlans.forEach(plan -> plan.setEquality(equality));

            exchanged.add(routes(exchange(calculation, type, 2, true).apply(exchangePlans)));
            relocated.add(routes(relocate(calculation, type, 2).apply(relocatePlans)));
        }
        assertThat(exchanged).as("exchange, VALUE / CODE / IDENTITY").allSatisfy(route ->
                assertThat(route).isEqualTo(exchanged.getFirst()));
        assertThat(relocated).as("relocate, VALUE / CODE / IDENTITY").allSatisfy(route ->
                assertThat(route).isEqualTo(relocated.getFirst()));
        assertThat(exchanged.getFirst()).as("the VALUE run improved").isNotEqualTo(
                routes(plans(List.of(far1, far2, near1), List.of(near2))));
    }

    // ---------------------------------------------------------------- signals

    @Test
    void aCancelledSearchThrowsAndLeavesThePlanListAlone() {
        MatrixCostCalculation calculation = calculation();
        for (AbstractExchangeRelocateAlgorithm<TestPlan, TestNode> algorithm : List.of(
                exchange(calculation, ExchangeRelocateType.SIMPLE, 1, true),
                relocate(calculation, ExchangeRelocateType.SIMPLE, 1))) {
            List<TestPlan> plans = crossed();
            List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);
            algorithm.cancel();

            assertThatThrownBy(() -> algorithm.apply(plans)).isInstanceOf(CancelException.class);
            assertThat(plans).isEqualTo(snapshot);
        }
    }

    @Test
    void anInstanceInterruptedBeforeTheRunReturnsThePlansUnchanged() {
        MatrixCostCalculation calculation = calculation();
        for (AbstractExchangeRelocateAlgorithm<TestPlan, TestNode> algorithm : List.of(
                exchange(calculation, ExchangeRelocateType.SIMPLE, 1, true),
                relocate(calculation, ExchangeRelocateType.SIMPLE, 1))) {
            List<TestPlan> plans = crossed();
            List<TestPlan> originals = List.copyOf(plans);
            algorithm.interrupt();

            List<TestPlan> result = algorithm.apply(plans);

            assertThat(result).isSameAs(plans);
            assertThat(result).containsExactlyElementsOf(originals);
            assertThat(PlanInvariants.referenceTotalCost(result)).isEqualTo(2010);
        }
    }

    @Test
    void anInterruptDuringTheRunKeepsTheFirstAcceptedMove() {
        // The local search runs on every plan after an accepted move; raising interrupt() there stops the scan at the
        // next pair. Relocating F1 next to F2 is the first improving relocation of the misplaced plans ([O, N1, N2]
        // + [O, F1, F2] = 20 + 1010 = 1030, against 1010 uninterrupted); the first exchange of the cross instance
        // above gives 1040 (against 1020).
        MatrixCostCalculation calculation = calculation();
        InterruptingLocalSearch relocateSearch = new InterruptingLocalSearch();
        TestRelocate relocate = new TestRelocate(calculation, fleet(), relocateSearch,
                insertion(calculation, new AlwaysFeasibleChecker()), ExchangeRelocateType.SIMPLE, 4, 1);
        relocateSearch.target = relocate;
        InterruptingLocalSearch exchangeSearch = new InterruptingLocalSearch();
        TestExchange exchange = new TestExchange(calculation, fleet(), exchangeSearch,
                insertion(calculation, new AlwaysFeasibleChecker()), ExchangeRelocateType.SIMPLE, 4, 2, true);
        exchangeSearch.target = exchange;

        List<TestPlan> relocated = relocate.apply(misplaced());
        List<TestPlan> exchanged = exchange.apply(plans(List.of(far1, far2, near1), List.of(near2)));

        assertThat(routes(relocated)).containsExactly(List.of("p1", "O", "N1", "N2"), List.of("p2", "O", "F1", "F2"));
        assertThat(PlanInvariants.referenceTotalCost(relocated)).isEqualTo(1030);
        assertThat(PlanInvariants.referenceTotalCost(exchanged)).isEqualTo(1040);
        PlanInvariants.assertValidSolution(relocated, origin, null, customers);
        PlanInvariants.assertValidSolution(exchanged, origin, null, customers);
    }

    @Test
    void bothSignalsReachTheNestedLocalSearchAndInsertion() {
        MatrixCostCalculation calculation = calculation();
        for (ExchangeRelocateType type : ExchangeRelocateType.values()) {
            for (boolean relocate : new boolean[] {false, true}) {
                CancelSpyLocalSearch localSearch = new CancelSpyLocalSearch();
                SpyInsertion insertion = new SpyInsertion(calculation);
                AbstractExchangeRelocateAlgorithm<TestPlan, TestNode> algorithm = relocate
                        ? new TestRelocate(calculation, fleet(), localSearch, insertion, type, 4, 1)
                        : new TestExchange(calculation, fleet(), localSearch, insertion, type, 4, 1, true);

                algorithm.interrupt();
                assertThat(localSearch.isInterrupted()).as("%s relocate=%s", type, relocate).isTrue();
                assertThat(insertion.interrupted).as("%s relocate=%s", type, relocate).isTrue();
                assertThat(localSearch.isCancelled() || insertion.cancelled.get()).isFalse();

                algorithm.cancel();
                assertThat(localSearch.isCancelled()).as("%s relocate=%s", type, relocate).isTrue();
                assertThat(insertion.cancelled).as("%s relocate=%s", type, relocate).isTrue();
            }
        }
    }

    // ---------------------------------------------------------------- gates

    @Test
    void aPlanTheSearchDoesNotSupportNeverTakesPartInAMove() {
        // A locked plan [O, S(1005,0)] in front of the crossed pair: it would be the natural partner of both other
        // plans, but supports(plan) keeps it out, so it stays as it is while the other two still exchange (2010 ->
        // 1030 for p1 + p2 under INSERTION).
        TestNode stray = TestNode.customer("S", 1005, 0, 1);
        MatrixCostCalculation withStray = new MatrixCostCalculation();
        withStray.initializeCostMatrix(List.of(origin, near1, near2, far1, far2, stray));
        TestPlan locked = new TestPlan("locked", List.of(origin, stray));
        List<TestPlan> plans = new ArrayList<>(List.of(locked));
        plans.addAll(crossed());
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T0", 5), new TestTrackable("T1", 5),
                new TestTrackable("T2", 5)));
        assertThat(assignment.assign(plans)).isTrue();
        AbstractExchangeAlgorithm<TestPlan, TestNode> exchange = new AbstractExchangeAlgorithm<>(withStray, assignment,
                new RecostingLocalSearch(withStray), insertion(withStray, new AlwaysFeasibleChecker()),
                ExchangeRelocateType.INSERTION, 4, 1, true) {
            @Override
            protected boolean supports(TestPlan plan) {
                return !plan.getCode().equals("locked");
            }
        };

        List<TestPlan> result = exchange.apply(plans);

        // Accepted moves replace every plan of the list by its copy, so the locked plan comes back as an equal copy.
        assertThat(result.getFirst().getCode()).isEqualTo("locked");
        assertThat(result.getFirst().getNodes()).containsExactly(origin, stray);
        assertThat(locked.getNodes()).containsExactly(origin, stray);
        assertThat(PlanInvariants.referenceTotalCost(result.subList(1, 3))).isEqualTo(1030);
        assertThat(exchange.apply(List.of(), new ArrayList<>(plans), locked, plans.get(1)))
                .as("the pairwise entry point refuses the locked plan too").isFalse();
        assertThat(exchange.apply(List.of(), new ArrayList<>(plans), plans.get(1), locked)).isFalse();
    }

    @Test
    void aRelocateGateRefusesAnUnsupportedPlanInThePairwiseEntryPoint() {
        MatrixCostCalculation calculation = calculation();
        List<TestPlan> plans = misplaced();
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);
        AbstractRelocateAlgorithm<TestPlan, TestNode> relocate = new AbstractRelocateAlgorithm<>(calculation, fleet(),
                new RecostingLocalSearch(calculation), insertion(calculation, new AlwaysFeasibleChecker()),
                ExchangeRelocateType.SIMPLE, 4, 1) {
            @Override
            protected boolean supports(TestPlan plan) {
                return !plan.getCode().equals("p2");
            }
        };

        assertThat(relocate.apply(List.of(), plans, plans.get(0), plans.get(1))).isFalse();
        assertThat(relocate.apply(List.of(), plans, plans.get(1), plans.get(0))).isFalse();
        assertThat(relocate.apply(plans)).isEqualTo(snapshot);
    }

    @Test
    void aPlanIsNeverExchangedOrRelocatedWithItself() {
        MatrixCostCalculation calculation = calculation();
        for (ExchangeRelocateType type : ExchangeRelocateType.values()) {
            List<TestPlan> plans = misplaced();
            List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);
            TestPlan first = plans.getFirst();

            assertThat(exchange(calculation, type, 2, true).applyPair(plans, first, first)).as("%s", type).isFalse();
            assertThat(relocate(calculation, type, 2).applyPair(plans, first, first)).as("%s", type).isFalse();
            assertThat(plans).isEqualTo(snapshot);
        }
    }

    @Test
    void aPlanWithoutStopsReceivesASegmentAtItsOnlySlot() {
        // A plan without stops has a single slot, position 0, as in the insertion algorithm; there is no last customer
        // to rank, so the neighbour limit does not apply. Moving N1 there gains nothing ([O, F1] (1000) + [N1] (0));
        // moving F1 gives [O, N1] (10) + [F1] (0) = 10, and nothing improves on that.
        MatrixCostCalculation calculation = calculation();
        for (ExchangeRelocateType type : ExchangeRelocateType.values()) {
            List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", List.of(origin, near1, far1)),
                    new TestPlan("empty", new ArrayList<>())));
            assertThat(fleet().assign(plans)).isTrue();

            List<TestPlan> result = relocate(calculation, type, 1).apply(plans);

            assertThat(routes(result)).as("%s", type).containsExactly(List.of("p1", "O", "N1"),
                    List.of("empty", "F1"));
            assertThat(PlanInvariants.referenceTotalCost(result)).isEqualTo(10);
        }
    }

    /**
     * Identity local search that interrupts {@link #target} whenever it runs, i.e. right after an accepted move.
     */
    private static final class InterruptingLocalSearch extends LocalSearchAlgorithm<TestPlan> {

        private AbstractExchangeRelocateAlgorithm<TestPlan, TestNode> target;

        @Override
        public TestPlan apply(TestPlan plan) {
            target.interrupt();
            return plan;
        }

    }

    /**
     * Cheapest insertion that records the signals it receives.
     */
    private static final class SpyInsertion extends TestInsertion {

        private final AtomicBoolean cancelled = new AtomicBoolean();
        private boolean interrupted;

        private SpyInsertion(MatrixCostCalculation calculation) {
            super(calculation, new AlwaysFeasibleChecker(), false);
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            super.cancel();
        }

        @Override
        public void interrupt() {
            interrupted = true;
            super.interrupt();
        }

    }

}
