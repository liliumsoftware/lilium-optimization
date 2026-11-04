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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the order in which one destroy-repair pass visits plans and stops: the plan that moves up into the place of a
 * dissolved plan is still destroyed in the same pass, the stop that moves up into a destroyed chain's place is still
 * probed, and {@code interrupt()} is honoured between ranked customers. Each test stops after the first pass (the
 * local search, which runs once at the end of every pass, raises the interrupt), so a skipped plan or stop shows in
 * the result instead of being picked up by a later pass.
 *
 * <p>Instance (Manhattan cost, open plans): {@code C = [O, X1(100,0), X2(110,0), X3(0,100), X4(0,110)]} costs
 * 100 + 10 + 210 + 10 = 330; {@code Y1(105,0)} sits on the edge X1-X2 and {@code Y2(0,105)} on the edge X3-X4, so
 * either can join C at no extra cost, while a plan of its own costs 105.
 */
class DestroyRepairPassTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode x1 = TestNode.customer("X1", 100, 0, 1);
    private final TestNode x2 = TestNode.customer("X2", 110, 0, 1);
    private final TestNode x3 = TestNode.customer("X3", 0, 100, 1);
    private final TestNode x4 = TestNode.customer("X4", 0, 110, 1);
    private final TestNode y1 = TestNode.customer("Y1", 105, 0, 1);
    private final TestNode y2 = TestNode.customer("Y2", 0, 105, 1);
    private final List<TestNode> customers = List.of(x1, x2, x3, x4, y1, y2);

    private static List<List<String>> routes(List<TestPlan> plans) {
        return plans.stream().map(plan -> {
            List<String> route = new ArrayList<>(List.of(plan.getCode()));
            plan.getNodes().forEach(node -> route.add(node.getCode()));
            return route;
        }).toList();
    }

    private MatrixCostCalculation calculation() {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, x1, x2, x3, x4, y1, y2));
        return calculation;
    }

    private TestAssignment fleet() {
        return new TestAssignment(List.of(new TestTrackable("T1", 10), new TestTrackable("T2", 10),
                new TestTrackable("T3", 10)));
    }

    private TestPlan collector() {
        return new TestPlan("C", List.of(origin, x1, x2, x3, x4));
    }

    private List<TestPlan> assigned(TestAssignment fleet, TestPlan... plans) {
        List<TestPlan> list = new ArrayList<>(List.of(plans));
        assertThat(fleet.assign(list)).isTrue();
        return list;
    }

    @Test
    void thePlanMovingUpIntoADissolvedPlansPlaceIsDestroyedInTheSamePass() {
        // [O, Y1] (105) + [O, Y2] (105) + C (330) = 540. The small-plan step dissolves A into C, B moves up into A's
        // place and is dissolved as well: one plan [O, X1, Y1, X2, X3, Y2, X4] = 330.
        MatrixCostCalculation calculation = calculation();
        TestAssignment fleet = fleet();
        List<TestPlan> plans = assigned(fleet, new TestPlan("A", List.of(origin, y1)),
                new TestPlan("B", List.of(origin, y2)), collector());
        InterruptingLocalSearch localSearch = new InterruptingLocalSearch();
        PassDestroyRepair destroyRepair = new PassDestroyRepair(calculation, fleet, localSearch, 1,
                Integer.MAX_VALUE, false);
        localSearch.target = destroyRepair;

        List<TestPlan> result = destroyRepair.apply(plans);

        assertThat(routes(result)).containsExactly(List.of("C", "O", "X1", "Y1", "X2", "X3", "Y2", "X4"));
        assertThat(PlanInvariants.referenceTotalCost(result)).isEqualTo(330);
        PlanInvariants.assertValidSolution(result, origin, null, customers);
    }

    @Test
    void theStopMovingUpIntoADestroyedChainsPlaceIsProbedInTheSamePass() {
        // [O, Y1, Y2] (105 + 210 = 315) + C (330) = 645, chains of one stop only. Destroying Y1 gives [O, Y2] (105)
        // + C (330) = 435; Y2 moves up into Y1's position and is destroyed too, which empties and drops the plan: 330.
        MatrixCostCalculation calculation = calculation();
        TestAssignment fleet = fleet();
        List<TestPlan> plans = assigned(fleet, new TestPlan("A", List.of(origin, y1, y2)), collector());
        InterruptingLocalSearch localSearch = new InterruptingLocalSearch();
        PassDestroyRepair destroyRepair = new PassDestroyRepair(calculation, fleet, localSearch, 2,
                Integer.MAX_VALUE, true);
        localSearch.target = destroyRepair;

        List<TestPlan> result = destroyRepair.apply(plans);

        assertThat(routes(result)).containsExactly(List.of("C", "O", "X1", "Y1", "X2", "X3", "Y2", "X4"));
        assertThat(PlanInvariants.referenceTotalCost(result)).isEqualTo(330);
        PlanInvariants.assertValidSolution(result, origin, null, customers);
    }

    @Test
    void anInterruptRaisedWhileRankingStopsBeforeTheFirstRankedCustomer() {
        // Ranking the customers raises the interrupt; the pass must stop before probing any of them and return the
        // plans as they are, rather than finish the pass (which would dissolve both small plans).
        MatrixCostCalculation calculation = calculation();
        TestAssignment fleet = fleet();
        List<TestPlan> plans = assigned(fleet, new TestPlan("A", List.of(origin, y1)),
                new TestPlan("B", List.of(origin, y2)), collector());
        List<TestPlan> originals = List.copyOf(plans);
        PassDestroyRepair destroyRepair = new PassDestroyRepair(calculation, fleet,
                new InterruptingLocalSearch(), 1, 0, false);
        destroyRepair.interruptWhileRanking = true;

        List<TestPlan> result = destroyRepair.apply(plans);

        assertThat(result).isSameAs(plans);
        assertThat(result).containsExactlyElementsOf(originals);
        assertThat(PlanInvariants.referenceTotalCost(result)).isEqualTo(540);
    }

    /**
     * Destroy-repair with optional single-stop chains and an interrupt raised from the rank hook.
     */
    private static final class PassDestroyRepair extends AbstractDestroyRepairAlgorithm<TestPlan, TestNode> {

        private final boolean singleStopChains;
        private boolean interruptWhileRanking;

        private PassDestroyRepair(MatrixCostCalculation calculation, TestAssignment assignment,
                LocalSearchAlgorithm<TestPlan> localSearch, int chain, int threshold, boolean singleStopChains) {
            super(calculation, assignment, localSearch, new TestInsertion(calculation, new AlwaysFeasibleChecker(),
                    false), chain, threshold);
            this.singleStopChains = singleStopChains;
        }

        @Override
        protected boolean supports(TestPlan plan, int start, int end) {
            return (!singleStopChains || end - start == 1) && super.supports(plan, start, end);
        }

        @Override
        protected int getRank(TestNode node, TestNode before, TestNode after) {
            if (interruptWhileRanking) {
                interrupt();
            }
            return super.getRank(node, before, after);
        }

    }

    /**
     * Identity local search that interrupts {@link #target} when it runs, i.e. at the end of the first pass.
     */
    private static final class InterruptingLocalSearch extends LocalSearchAlgorithm<TestPlan> {

        private AbstractDestroyRepairAlgorithm<TestPlan, TestNode> target;

        @Override
        public TestPlan apply(TestPlan plan) {
            if (target != null) {
                target.interrupt();
            }
            return plan;
        }

    }

}
