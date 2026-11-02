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

package com.github.liliumsoftware.optimization.assignment;

import com.github.liliumsoftware.optimization.calculation.CostCalculation;
import com.github.liliumsoftware.optimization.domain.TrackableCategory;
import com.github.liliumsoftware.optimization.support.CapacityRules;
import com.github.liliumsoftware.optimization.support.PlanInvariants;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Pins the relocate, exchange and reassign repair passes of {@link TrackableAssignment}: which pairs they touch, the
 * exact assignment they end in, and the cap of 100 000 accepted moves that stops a compare hook that never converges.
 */
class TrackableAssignmentRepairTest {

    private static final int MAX_MOVES = 100_000;

    private final TestNode origin = TestNode.origin("O", 0, 0);

    private static TestTrackable unit(List<TestTrackable> pool, String name) {
        return pool.stream().filter(t -> t.toString().equals(name)).findFirst().orElseThrow();
    }

    private static List<String> units(List<TestPlan> plans) {
        return plans.stream().map(p -> p.getCode() + "=" + p.getTrackable()).toList();
    }

    private TestPlan plan(String code, int customerCount) {
        List<TestNode> nodes = new ArrayList<>();
        nodes.add(origin);
        for (int i = 0; i < customerCount; i++) {
            nodes.add(TestNode.customer(code + "-c" + i, 0, 10 * (i + 1), 1));
        }
        return new TestPlan(code, nodes);
    }

    private TestPlan planOfLength(String code, int length) {
        return new TestPlan(code, List.of(origin, TestNode.customer(code + "-c", 0, length, 1)));
    }

    // ---------------------------------------------------------------- relocate

    @Test
    void relocateHandsTheUnitOfALaterPlanToAnEarlierStrandedOne() {
        // The stranded plan comes first and the holder second, so the first hand-over direction (the later plan gives
        // its unit to the earlier one) is the one that must fire: big goes to the 3-customer plan, the holder moves on
        // to the only free unit that fits it.
        Probe assignment = new Probe(List.of(new TestTrackable("big", 3), new TestTrackable("small", 1)));
        TestPlan stranded = plan("stranded", 3);
        TestPlan holder = plan("holder", 1);
        holder.setTrackable(unit(assignment.getTrackables(), "big#0"));
        List<TestPlan> plans = new ArrayList<>(List.of(stranded, holder));

        assertThat(assignment.relocate(plans, assignment.getTrackables())).isTrue();

        assertThat(units(plans)).containsExactly("stranded=big#0", "holder=small#0");
        assertThat(assignment.relocateCompares).as("one accepted hand-over").isEqualTo(1);
    }

    @Test
    void relocateDrawsTheDonorsNewUnitOnlyFromTheGivenPool() {
        // "small" is free in the fleet but not in the pool handed to the pass, so the holder cannot be re-housed and
        // the hand-over is impossible.
        Probe assignment = new Probe(List.of(new TestTrackable("big", 3), new TestTrackable("small", 1)));
        TestPlan stranded = plan("stranded", 3);
        TestPlan holder = plan("holder", 1);
        holder.setTrackable(unit(assignment.getTrackables(), "big#0"));
        List<TestPlan> plans = new ArrayList<>(List.of(stranded, holder));

        assertThat(assignment.relocate(plans, List.of(unit(assignment.getTrackables(), "big#0")))).isFalse();

        assertThat(units(plans)).containsExactly("stranded=null", "holder=big#0");
        assertThat(assignment.relocateCompares).isZero();
    }

    @Test
    void relocateNeverConsultsTheHooksForPairsThatCannotHandOver() {
        // Two assigned plans, or two unassigned ones, offer no hand-over: the pass must not probe them at all.
        Probe assignment = new Probe(List.of(new TestTrackable("A", 5), new TestTrackable("B", 5),
                new TestTrackable("C", 5)));
        TestPlan a = plan("a", 1);
        TestPlan b = plan("b", 1);
        a.setTrackable(unit(assignment.getTrackables(), "A#0"));
        b.setTrackable(unit(assignment.getTrackables(), "B#0"));
        List<TestPlan> assigned = new ArrayList<>(List.of(a, b));

        assertThat(assignment.relocate(assigned, assignment.getTrackables())).isTrue();
        assertThat(units(assigned)).containsExactly("a=A#0", "b=B#0");
        assertThat(assigned.get(0)).isSameAs(a);
        assertThat(assigned.get(1)).isSameAs(b);

        List<TestPlan> unassigned = new ArrayList<>(List.of(plan("x", 9), plan("y", 9)));
        assertThat(assignment.relocate(unassigned, assignment.getTrackables())).isFalse();
        assertThat(units(unassigned)).containsExactly("x=null", "y=null");

        assertThat(assignment.singleAssigns).isZero();
        assertThat(assignment.listAssigns).isZero();
        assertThat(assignment.relocateCompares).isZero();
    }

    @Test
    void relocateRescuesWhatItCanAndLeavesTheRestUnassigned() {
        // a holds the only big unit; b and d need it (3 customers each). b gets big1 and a moves to the free small2;
        // d can then be served by nothing (small units carry 1, and handing big1 over would leave b without a unit).
        Probe assignment = new Probe(List.of(new TestTrackable("big1", 5), new TestTrackable("small1", 1),
                new TestTrackable("small2", 1)));
        TestPlan a = plan("a", 1);
        TestPlan b = plan("b", 3);
        TestPlan c = plan("c", 1);
        TestPlan d = plan("d", 3);
        a.setTrackable(unit(assignment.getTrackables(), "big1#0"));
        c.setTrackable(unit(assignment.getTrackables(), "small1#0"));
        List<TestPlan> plans = new ArrayList<>(List.of(a, b, c, d));

        assertThat(assignment.relocate(plans, assignment.getTrackables())).isFalse();

        assertThat(units(plans)).containsExactly("a=small2#0", "b=big1#0", "c=small1#0", "d=null");
        assertThat(assignment.relocateCompares).as("only the accepted hand-over reaches the compare hook")
                .isEqualTo(1);
    }

    @ParameterizedTest(name = "stranded first: {0}")
    @ValueSource(booleans = {true, false})
    void relocateOffersTheDonorItsReplacementInComparatorOrder(boolean strandedFirst) {
        // Fleet (and pool) order: mid (3), large (9), small (1). The holder hands mid to the 3-customer plan and needs
        // a new unit for its single customer: large and small both fit. In pool order it would take large; sorted by
        // the comparator for the donor (smallest capacity first) it takes small. Both hand-over directions agree.
        Probe assignment = new Probe(List.of(new TestTrackable("mid", 3), new TestTrackable("large", 9),
                new TestTrackable("small", 1)));
        TestPlan stranded = plan("stranded", 3);
        TestPlan holder = plan("holder", 1);
        holder.setTrackable(unit(assignment.getTrackables(), "mid#0"));
        List<TestPlan> plans = new ArrayList<>(strandedFirst ? List.of(stranded, holder) : List.of(holder, stranded));

        assertThat(assignment.relocate(plans, assignment.getTrackables())).isTrue();

        assertThat(units(plans)).containsExactlyInAnyOrder("stranded=mid#0", "holder=small#0");
        assertThat(assignment.comparatorCalls).as("the comparator is asked for the donor, still on its old unit")
                .containsExactly("holder=mid#0");
        PlanInvariants.assertTrackablesAreDistinct(plans);
    }

    @ParameterizedTest(name = "ghost first: {0}")
    @ValueSource(booleans = {true, false})
    void theDefaultRelocateRuleRejectsAHandOverThatAssignsNoMorePlans(boolean ghostFirst) {
        // The single-unit hook reports success without recording the unit, so a "hand-over" only moves the holder to
        // another unit: the number of assigned plans stays 1 and the default rule (strictly more) refuses the move, in
        // either hand-over direction.
        Probe assignment = new Probe(List.of(new TestTrackable("U", 5), new TestTrackable("V", 5)));
        assignment.ghostSingleAssign = true;
        TestPlan ghost = plan("ghost", 1);
        TestPlan holder = plan("holder", 1);
        holder.setTrackable(unit(assignment.getTrackables(), "U#0"));
        List<TestPlan> plans = new ArrayList<>(ghostFirst ? List.of(ghost, holder) : List.of(holder, ghost));

        assertThat(assignment.relocate(plans, assignment.getTrackables())).isFalse();

        assertThat(units(plans)).containsExactlyInAnyOrder("ghost=null", "holder=U#0");
        assertThat(assignment.relocateCompares).isEqualTo(1);
    }

    @ParameterizedTest(name = "ghost first: {0}")
    @ValueSource(booleans = {true, false})
    void aRelocateHookThatAcceptsEveryMoveStopsAtTheMoveCap(boolean ghostFirst) {
        // With the same success-reporting hook and a relocate rule that accepts everything, the holder bounces between
        // U and V forever; the pass must stop after exactly 100 000 accepted moves and report the ghost unassigned.
        Probe assignment = new Probe(List.of(new TestTrackable("U", 5), new TestTrackable("V", 5)));
        assignment.ghostSingleAssign = true;
        assignment.relocateAcceptsAll = true;
        TestPlan ghost = plan("ghost", 1);
        TestPlan holder = plan("holder", 1);
        holder.setTrackable(unit(assignment.getTrackables(), "U#0"));
        List<TestPlan> plans = new ArrayList<>(ghostFirst ? List.of(ghost, holder) : List.of(holder, ghost));

        boolean allAssigned = assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> assignment.relocate(plans, assignment.getTrackables()));

        assertThat(allAssigned).isFalse();
        assertThat(assignment.relocateCompares).isEqualTo(MAX_MOVES);
        // an even number of bounces brings the holder back to U
        assertThat(units(plans)).containsExactlyInAnyOrder("ghost=null", "holder=U#0");
    }

    @Test
    void aRelocateHookThatAcceptsEveryMoveStillEndsWhenTheMovesConverge() {
        // With hooks that honour the contract every hand-over assigns one more plan, so accepting everything converges
        // long before the cap.
        Probe assignment = new Probe(List.of(new TestTrackable("big", 3), new TestTrackable("small", 1)));
        assignment.relocateAcceptsAll = true;
        TestPlan holder = plan("holder", 1);
        holder.setTrackable(unit(assignment.getTrackables(), "big#0"));
        List<TestPlan> plans = new ArrayList<>(List.of(holder, plan("stranded", 3)));

        assertThat(assignment.relocate(plans, assignment.getTrackables())).isTrue();

        assertThat(units(plans)).containsExactly("holder=small#0", "stranded=big#0");
        assertThat(assignment.relocateCompares).isEqualTo(1);
    }

    // ---------------------------------------------------------------- exchange

    @Test
    void exchangeOnlyTriesPairsOfAssignedPlansInDifferentCategories() {
        // Slowness = capacity. p100 on slowA (500), p50 on slowB (250), p10 on fast (20), u unassigned (length 30, cost
        // 30): total 800. Pass 1: (p100, p50) share a category and are skipped; swapping p100 and p10 gives
        // 200 + 250 + 50 + 30 = 530 and is taken; every pair with u is skipped. Pass 2 tries (p100 fast, p50 slowB) ->
        // 500 + 100 + 50 + 30 = 680 and (p100 fast, p10 slowA) -> 800, both rejected. Three probes in all.
        Probe assignment = new Probe(List.of(new TestTrackable("fast", 2), new TestTrackable("slowA", 5),
                new TestTrackable("slowB", 5)));
        assignment.speedAware = true;
        TestPlan p100 = planOfLength("p100", 100);
        TestPlan p50 = planOfLength("p50", 50);
        TestPlan p10 = planOfLength("p10", 10);
        TestPlan u = planOfLength("u", 30);
        assignment.assignWithTrackable(p100, unit(assignment.getTrackables(), "slowA#0"));
        assignment.assignWithTrackable(p50, unit(assignment.getTrackables(), "slowB#0"));
        assignment.assignWithTrackable(p10, unit(assignment.getTrackables(), "fast#0"));
        List<TestPlan> plans = new ArrayList<>(List.of(p100, p50, p10, u));
        assertThat(CostCalculation.totalCost(plans)).isEqualTo(800);

        assignment.exchange(plans);

        assertThat(units(plans)).containsExactly("p100=fast#0", "p50=slowB#0", "p10=slowA#0", "u=null");
        assertThat(CostCalculation.totalCost(plans)).isEqualTo(530);
        assertThat(assignment.exchangeCompares).isEqualTo(3);
    }

    @Test
    void exchangeOfPlansInOneCategoryOrWithoutUnitsChangesNothing() {
        Probe assignment = new Probe(List.of(new TestTrackable("A", 5), new TestTrackable("B", 5)));
        assignment.speedAware = true;
        TestPlan a = planOfLength("a", 100);
        TestPlan b = planOfLength("b", 10);
        assignment.assignWithTrackable(a, unit(assignment.getTrackables(), "A#0"));
        assignment.assignWithTrackable(b, unit(assignment.getTrackables(), "B#0"));
        List<TestPlan> plans = new ArrayList<>(List.of(a, plan("none", 1), b));

        assignment.exchange(plans);

        assertThat(units(plans)).containsExactly("a=A#0", "none=null", "b=B#0");
        assertThat(plans.get(0)).isSameAs(a);
        assertThat(plans.get(2)).isSameAs(b);
        // assignWithTrackable probes on a copy and then commits: two hook calls per set-up assignment, none after
        assertThat(assignment.singleAssigns).isEqualTo(4);
        assertThat(assignment.exchangeCompares).isZero();
    }

    @Test
    void anExchangeHookThatAcceptsTiesStopsAtTheMoveCap() {
        // Cost does not depend on the unit, so every swap of the two plans is a tie; a "not worse" rule swaps them
        // forever. The pass must stop after exactly 100 000 accepted swaps, still without double-booking.
        Probe assignment = new Probe(List.of(new TestTrackable("five", 5), new TestTrackable("six", 6)));
        assignment.exchangeAcceptsTies = true;
        TestPlan first = plan("first", 1);
        TestPlan second = plan("second", 1);
        first.setTrackable(unit(assignment.getTrackables(), "five#0"));
        second.setTrackable(unit(assignment.getTrackables(), "six#0"));
        List<TestPlan> plans = new ArrayList<>(List.of(first, second));

        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> assignment.exchange(plans));

        assertThat(assignment.exchangeCompares).isEqualTo(MAX_MOVES);
        // an even number of swaps restores the start
        assertThat(units(plans)).containsExactly("first=five#0", "second=six#0");
        PlanInvariants.assertTrackablesAreDistinct(plans);
    }

    // ---------------------------------------------------------------- reassign

    @Test
    void aReassignHookThatAcceptsTiesStopsAfterExactlyTheMoveCap() {
        // One plan and two idle units of different categories: a "not worse" rule moves it back and forth; every
        // accepted move counts, so the rule is consulted exactly 100 000 times.
        Probe assignment = new Probe(List.of(new TestTrackable("T1", 10), new TestTrackable("T2", 20)));
        assignment.reassignAcceptsTies = true;
        TestPlan only = plan("p", 1);
        only.setTrackable(unit(assignment.getTrackables(), "T1#0"));
        List<TestPlan> plans = new ArrayList<>(List.of(only));

        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> assignment.reassign(plans));

        assertThat(assignment.reassignCompares).isEqualTo(MAX_MOVES);
        assertThat(units(plans)).containsExactly("p=T1#0");
    }

    @Test
    void reassignLeavesAPlanWithoutAUnitAlone() {
        // The unassigned plan is skipped (it is not the reassign pass's job to assign it); the assigned one moves from
        // slow (100 x 5) to the idle fast unit (100 x 2).
        Probe assignment = new Probe(List.of(new TestTrackable("fast", 2), new TestTrackable("slow", 5)));
        assignment.speedAware = true;
        TestPlan none = planOfLength("none", 10);
        TestPlan p = planOfLength("p", 100);
        assignment.assignWithTrackable(p, unit(assignment.getTrackables(), "slow#0"));
        List<TestPlan> plans = new ArrayList<>(List.of(none, p));

        assignment.reassign(plans);

        assertThat(units(plans)).containsExactly("none=null", "p=fast#0");
        assertThat(plans.get(1).getCost()).isEqualTo(200);
        assertThat(plans.getFirst()).isSameAs(none);
    }

    /**
     * Capacity-rule assignment that counts its hook calls and can switch to a resource-dependent cost (cost = length
     * x capacity), to compare rules that accept ties, or to a single-unit hook that reports success without recording
     * the unit.
     */
    private static final class Probe extends TrackableAssignment<TestPlan, TestTrackable> {

        private final List<String> comparatorCalls = new ArrayList<>();
        private boolean speedAware;
        private boolean ghostSingleAssign;
        private boolean relocateAcceptsAll;
        private boolean exchangeAcceptsTies;
        private boolean reassignAcceptsTies;
        private int singleAssigns;
        private int listAssigns;
        private int relocateCompares;
        private int exchangeCompares;
        private int reassignCompares;

        private Probe(List<TestTrackable> trackables) {
            super(trackables);
        }

        @Override
        protected boolean assign(TestPlan plan, TestTrackable trackable) {
            singleAssigns++;
            if (ghostSingleAssign) {
                return true;
            }
            boolean assigned = CapacityRules.assign(plan, trackable);
            if (assigned && speedAware) {
                plan.setCostMultiplier(trackable.getCapacity());
            }
            return assigned;
        }

        @Override
        protected boolean assign(TestPlan plan, List<TestTrackable> trackables) {
            listAssigns++;
            boolean assigned = CapacityRules.assign(plan, trackables);
            if (assigned && speedAware) {
                plan.setCostMultiplier(plan.getTrackable().getCapacity());
            }
            return assigned;
        }

        @Override
        protected Comparator<TestTrackable> comparator(TestPlan plan) {
            comparatorCalls.add(plan.getCode() + "=" + plan.getTrackable());
            return CapacityRules.comparator();
        }

        @Override
        protected TrackableCategory getCategory(TestTrackable trackable) {
            return CapacityRules.category(trackable);
        }

        @Override
        protected boolean compareRelocate(List<TestPlan> newPlans, List<TestPlan> oldPlans) {
            relocateCompares++;
            return relocateAcceptsAll || super.compareRelocate(newPlans, oldPlans);
        }

        @Override
        protected boolean compareExchange(TestPlan firstPlan, TestPlan secondPlan, List<TestPlan> newPlans,
                List<TestPlan> oldPlans) {
            exchangeCompares++;
            if (exchangeAcceptsTies) {
                return CostCalculation.totalCost(newPlans) <= CostCalculation.totalCost(oldPlans);
            }
            return super.compareExchange(firstPlan, secondPlan, newPlans, oldPlans);
        }

        @Override
        protected boolean compareReassign(TestPlan plan, TestPlan newPlan) {
            reassignCompares++;
            if (reassignAcceptsTies) {
                return newPlan.getCost() <= plan.getCost();
            }
            return super.compareReassign(plan, newPlan);
        }

    }

}
