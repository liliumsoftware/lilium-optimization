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

import com.github.liliumsoftware.optimization.BaseAlgorithm;
import com.github.liliumsoftware.optimization.exception.CancelException;
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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the control signals and gates of both 2-opt variants: {@code interrupt()} keeps the best plan found so far
 * (the input itself when raised before the run), {@code cancel()} aborts with {@link CancelException},
 * {@code supports(plan)} can veto a plan, and the feasibility checker vetoes individual improving moves.
 *
 * <p>The main instance is the collinear open route {@code O(0,0) B(0,20) A(0,10) D(0,40) C(0,30)}, cost
 * {@code 20 + 10 + 30 + 10 = 70}. Both variants first reverse {@code [B, A]} ({@code O A B D C}, cost 50) and then
 * {@code [D, C]} ({@code O A B C D}, the optimum 40), so a run stopped after the first accepted move ends at 50.
 */
class LocalSearchControlTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode a = TestNode.customer("A", 0, 10);
    private final TestNode b = TestNode.customer("B", 0, 20);
    private final TestNode c = TestNode.customer("C", 0, 30);
    private final TestNode d = TestNode.customer("D", 0, 40);

    private MatrixCostCalculation calculation() {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, a, b, c, d));
        return calculation;
    }

    private TestPlan twoMovesFromOptimal() {
        return new TestPlan("p", List.of(origin, b, a, d, c));   // 20 + 10 + 30 + 10 = 70
    }

    /**
     * The two variants under one signature, so every signal test runs against both.
     */
    private List<Function<FeasibilityChecker<TestPlan>, LocalSearchAlgorithm<TestPlan>>> variants() {
        MatrixCostCalculation calculation = calculation();
        return List.of(checker -> new TestTwoOpt(calculation, checker),
                checker -> new TestOpenTwoOpt(calculation, checker));
    }

    // ---------------------------------------------------------------- interrupt

    @Test
    void anInstanceInterruptedBeforeTheRunReturnsTheInputUnchanged() {
        for (Function<FeasibilityChecker<TestPlan>, LocalSearchAlgorithm<TestPlan>> variant : variants()) {
            LocalSearchAlgorithm<TestPlan> twoOpt = variant.apply(new AlwaysFeasibleChecker());
            TestPlan input = twoMovesFromOptimal();
            twoOpt.interrupt();

            TestPlan result = twoOpt.apply(input);

            assertThat(result).as("%s", twoOpt.getClass().getSimpleName()).isSameAs(input);
            assertThat(result.getNodes()).containsExactly(origin, b, a, d, c);
        }
    }

    @Test
    void anUninterruptedRunReachesTheOptimumOfTheInterruptInstance() {
        // The control for the next test: without an interrupt both variants make the second move as well.
        for (Function<FeasibilityChecker<TestPlan>, LocalSearchAlgorithm<TestPlan>> variant : variants()) {
            TestPlan result = variant.apply(new AlwaysFeasibleChecker()).apply(twoMovesFromOptimal());

            assertThat(result.getNodes()).containsExactly(origin, a, b, c, d);
            assertThat(PlanInvariants.referenceCost(result)).isEqualTo(40);
        }
    }

    @Test
    void anInterruptDuringTheRunStopsAfterTheImprovementInProgress() {
        // The checker is only consulted for a cheaper candidate, so its first call is the first accepted move; it
        // raises interrupt() there, and the run must return that move's plan (50) instead of going on to 40.
        for (Function<FeasibilityChecker<TestPlan>, LocalSearchAlgorithm<TestPlan>> variant : variants()) {
            InterruptingChecker checker = new InterruptingChecker();
            LocalSearchAlgorithm<TestPlan> twoOpt = variant.apply(checker);
            checker.target = twoOpt;
            TestPlan input = twoMovesFromOptimal();

            TestPlan result = twoOpt.apply(input);

            String name = twoOpt.getClass().getSimpleName();
            assertThat(checker.calls).as("%s consulted the checker once", name).isEqualTo(1);
            assertThat(result.getNodes()).as(name).containsExactly(origin, a, b, d, c);
            assertThat(PlanInvariants.referenceCost(result)).as("%s: cheaper than the input, not optimal", name)
                    .isEqualTo(50);
            assertThat(input.getNodes()).as("%s: the input plan", name).containsExactly(origin, b, a, d, c);
        }
    }

    // ---------------------------------------------------------------- cancel

    @Test
    void aCancelledInstanceThrowsCancelException() {
        for (Function<FeasibilityChecker<TestPlan>, LocalSearchAlgorithm<TestPlan>> variant : variants()) {
            LocalSearchAlgorithm<TestPlan> twoOpt = variant.apply(new AlwaysFeasibleChecker());
            TestPlan input = twoMovesFromOptimal();
            twoOpt.cancel();

            assertThatThrownBy(() -> twoOpt.apply(input)).as("%s", twoOpt.getClass().getSimpleName())
                    .isInstanceOf(CancelException.class);
            assertThat(input.getNodes()).containsExactly(origin, b, a, d, c);
        }
    }

    @Test
    void aCancelRaisedDuringTheRunAbortsIt() {
        // cancel() from inside the run (here from the checker on the first accepted move) must surface at the next
        // checkCancel() of the inner loop rather than letting the run finish.
        for (Function<FeasibilityChecker<TestPlan>, LocalSearchAlgorithm<TestPlan>> variant : variants()) {
            InterruptingChecker checker = new InterruptingChecker();
            checker.cancelInstead = true;
            LocalSearchAlgorithm<TestPlan> twoOpt = variant.apply(checker);
            checker.target = twoOpt;

            assertThatThrownBy(() -> twoOpt.apply(twoMovesFromOptimal()))
                    .as("%s", twoOpt.getClass().getSimpleName()).isInstanceOf(CancelException.class);
            assertThat(checker.calls).isEqualTo(1);
        }
    }

    // ---------------------------------------------------------------- supports(plan)

    @Test
    void aPlanTheSearchDoesNotSupportIsReturnedAsIs() {
        MatrixCostCalculation calculation = calculation();
        Predicate<TestPlan> locked = plan -> !plan.getCode().equals("locked");
        AbstractTwoOptAlgorithm<TestPlan, TestNode> closed = new AbstractTwoOptAlgorithm<>(calculation,
                new AlwaysFeasibleChecker()) {
            @Override
            protected boolean supports(TestPlan plan) {
                return locked.test(plan);
            }

            @Override
            protected TestPlan twoOptSwap(TestPlan plan, int i, int j) {
                return plan.withNodes(calculateTwoOptSwap(plan, i, j));
            }
        };
        AbstractOpenTwoOptAlgorithm<TestPlan, TestNode> open = new AbstractOpenTwoOptAlgorithm<>(calculation,
                new AlwaysFeasibleChecker()) {
            @Override
            protected boolean supports(TestPlan plan) {
                return locked.test(plan);
            }

            @Override
            protected TestPlan getPlan(TestPlan plan, List<TestNode> nodes) {
                return plan.withNodes(nodes);
            }
        };
        for (LocalSearchAlgorithm<TestPlan> twoOpt : List.of(closed, open)) {
            TestPlan lockedPlan = new TestPlan("locked", List.of(origin, b, a, d, c));

            assertThat(twoOpt.apply(lockedPlan)).isSameAs(lockedPlan);
            assertThat(lockedPlan.getNodes()).containsExactly(origin, b, a, d, c);
            // The same hook lets every other plan through to the full search.
            assertThat(PlanInvariants.referenceCost(twoOpt.apply(twoMovesFromOptimal()))).isEqualTo(40);
        }
    }

    // ---------------------------------------------------------------- feasibility

    @Test
    void closedTwoOptRefusesTheCheapestReversalWhenItIsInfeasibleAndTakesAFeasibleOne() {
        // O C A B (30 + 20 + 10 = 60) with "A may not be served first". The cheapest reversal O A C B (40) and the
        // optimum O A B C (30) both start with A and are refused; O B A C (20 + 10 + 20 = 50) is the improving
        // feasible reversal, and from there only infeasible or non-improving reversals remain.
        MatrixCostCalculation calculation = calculation();
        PredicateChecker aNotFirst = new PredicateChecker(plan -> !plan.getCustomers().getFirst().equals(a));

        TestPlan result = new TestTwoOpt(calculation, aNotFirst).apply(new TestPlan("p", List.of(origin, c, a, b)));

        assertThat(result.getNodes()).containsExactly(origin, b, a, c);
        assertThat(PlanInvariants.referenceCost(result)).isEqualTo(50);
        assertThat(aNotFirst.rejected).as("the cheaper infeasible reversals were proposed").isPositive();
    }

    @Test
    void closedTwoOptKeepsAPlanWhoseOnlyImprovementIsInfeasible() {
        MatrixCostCalculation calculation = calculation();
        TestPlan input = new TestPlan("p", List.of(origin, b, a));   // 20 + 10 = 30; O A B costs 20 but is refused
        PredicateChecker never = new PredicateChecker(plan -> false);

        TestPlan result = new TestTwoOpt(calculation, never).apply(input);

        assertThat(result).isSameAs(input);
        assertThat(result.getNodes()).containsExactly(origin, b, a);
        assertThat(never.rejected).isEqualTo(1);
    }

    // ---------------------------------------------------------------- degenerate

    @Test
    void anEmptyPlanIsReturnedUnchangedByBothVariants() {
        for (Function<FeasibilityChecker<TestPlan>, LocalSearchAlgorithm<TestPlan>> variant : variants()) {
            TestPlan empty = new TestPlan("empty", new ArrayList<>());

            TestPlan result = variant.apply(new AlwaysFeasibleChecker()).apply(empty);

            assertThat(result).isSameAs(empty);
            assertThat(result.getNodes()).isEmpty();
        }
    }

    /**
     * Feasible exactly when the predicate holds; counts the refusals.
     */
    private static final class PredicateChecker implements FeasibilityChecker<TestPlan> {

        private final Predicate<TestPlan> feasible;
        private int rejected;

        private PredicateChecker(Predicate<TestPlan> feasible) {
            this.feasible = feasible;
        }

        @Override
        public FeasibilityResult getResult(TestPlan plan) {
            if (feasible.test(plan)) {
                return FeasibilityResult.FEASIBLE;
            }
            rejected++;
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

    /**
     * Accepts everything and signals {@link #target} (interrupt, or cancel) on its first call.
     */
    private static final class InterruptingChecker implements FeasibilityChecker<TestPlan> {

        private BaseAlgorithm target;
        private boolean cancelInstead;
        private int calls;

        @Override
        public FeasibilityResult getResult(TestPlan plan) {
            if (calls++ == 0) {
                if (cancelInstead) {
                    target.cancel();
                } else {
                    target.interrupt();
                }
            }
            return FeasibilityResult.FEASIBLE;
        }

        @Override
        public String getMessage(String key, Object... params) {
            return key;
        }

    }

}
