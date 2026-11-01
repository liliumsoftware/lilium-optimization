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

import com.github.liliumsoftware.optimization.support.TestAssignment;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestSecondDispatchAssignment;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the whole-set and sub-pool overloads of the finite {@link TrackableAssignment}: where they stop on a failure,
 * which pool they draw from, that they never touch the caller's lists, and that a single-unit probe leaves a refused
 * plan as it was.
 */
class TrackableAssignmentOverloadsTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);

    private static TestTrackable unit(List<TestTrackable> pool, String name) {
        return pool.stream().filter(t -> t.toString().equals(name)).findFirst().orElseThrow();
    }

    private TestPlan plan(String code, int customerCount) {
        List<TestNode> nodes = new ArrayList<>();
        nodes.add(origin);
        for (int i = 0; i < customerCount; i++) {
            nodes.add(TestNode.customer(code + "-c" + i, 0, 10 * (i + 1), 1));
        }
        return new TestPlan(code, nodes);
    }

    @Test
    void assignStopsAtTheFirstPlanItCannotAssignAndKeepsTheEarlierOnes() {
        // T (2) carries p1; p2 needs 3 and fails; p3 would fit the remaining U but is never tried.
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T", 2), new TestTrackable("U", 2)));
        TestPlan p1 = plan("p1", 1);
        TestPlan p2 = plan("p2", 3);
        TestPlan p3 = plan("p3", 1);

        assertThat(assignment.assign(List.of(p1, p2, p3))).isFalse();

        assertThat(p1.getTrackable()).hasToString("T#0");
        assertThat(p2.getTrackable()).isNull();
        assertThat(p3.getTrackable()).isNull();
    }

    @Test
    void assignOfAWholeSetOffersEachPlanThePoolInComparatorOrder() {
        // Smallest fitting first: although big comes first in the fleet, the 1-customer plan takes small and the
        // 2-customer plan the next fitting one.
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("big", 5), new TestTrackable("mid", 2),
                new TestTrackable("small", 1)));
        TestPlan single = plan("single", 1);
        TestPlan pair = plan("pair", 2);

        assertThat(assignment.assign(List.of(single, pair))).isTrue();

        assertThat(single.getTrackable()).hasToString("small#0");
        assertThat(pair.getTrackable()).hasToString("mid#0");
    }

    @Test
    void assignAndExchangeOffersEachPlanThePoolInComparatorOrder() {
        // Same fleet as above: with every unit costing the same, the repair passes find nothing to improve, so the
        // greedy step's smallest-fitting-first choice is what stays.
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("big", 5), new TestTrackable("mid", 2),
                new TestTrackable("small", 1)));
        TestPlan single = plan("single", 1);
        TestPlan pair = plan("pair", 2);
        List<TestPlan> plans = new ArrayList<>(List.of(single, pair));

        assertThat(assignment.assignAndExchange(plans)).isTrue();

        assertThat(plans.get(0).getTrackable()).hasToString("small#0");
        assertThat(plans.get(1).getTrackable()).hasToString("mid#0");
    }

    @Test
    void assignGreedyAgainstOtherPlansOffersTheRestOfThePoolInComparatorOrder() {
        // "spare" is held by another group's plan, so the remaining big, mid and small are offered smallest first.
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("big", 5), new TestTrackable("mid", 2),
                new TestTrackable("spare", 1), new TestTrackable("small", 1)));
        TestPlan holder = plan("holder", 1);
        holder.setTrackable(unit(assignment.getTrackables(), "spare#0"));
        TestPlan single = plan("single", 1);
        TestPlan pair = plan("pair", 2);

        assertThat(assignment.assignGreedy(List.of(holder), List.of(single, pair))).isTrue();

        assertThat(single.getTrackable()).hasToString("small#0");
        assertThat(pair.getTrackable()).hasToString("mid#0");
    }

    @Test
    void assignWithTrackablesWithdrawsEachUnitAndStopsAtTheFirstFailure() {
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("A", 1), new TestTrackable("B", 1),
                new TestTrackable("C", 1)));
        List<TestTrackable> subPool = new ArrayList<>(List.of(unit(assignment.getTrackables(), "B#0"),
                unit(assignment.getTrackables(), "A#0")));
        TestPlan p1 = plan("p1", 1);
        TestPlan p2 = plan("p2", 1);
        TestPlan p3 = plan("p3", 1);

        assertThat(assignment.assignWithTrackables(List.of(p1, p2, p3), subPool)).isFalse();

        // ordered by the comparator (A before B), each unit used once, C is outside the sub-pool
        assertThat(p1.getTrackable()).hasToString("A#0");
        assertThat(p2.getTrackable()).hasToString("B#0");
        assertThat(p3.getTrackable()).isNull();
        assertThat(subPool).as("the caller's sub-pool is neither sorted nor emptied").extracting(Object::toString)
                .containsExactly("B#0", "A#0");
    }

    @Test
    void assigningOnePlanFromASubPoolSkipsHeldUnitsAndLeavesTheCallersListsAlone() {
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("big", 5),
                new TestTrackable("small", 1), new TestTrackable("tiny", 1)));
        TestPlan holder = plan("holder", 1);
        holder.setTrackable(unit(assignment.getTrackables(), "small#0"));
        List<TestPlan> plans = new ArrayList<>(List.of(holder));
        List<TestTrackable> subPool = new ArrayList<>(List.of(unit(assignment.getTrackables(), "big#0"),
                unit(assignment.getTrackables(), "small#0"), unit(assignment.getTrackables(), "tiny#0")));
        TestPlan newPlan = plan("new", 1);

        assertThat(assignment.assignWithTrackables(plans, newPlan, subPool)).isTrue();

        // small is held by the holder, so the smallest fitting free unit is tiny
        assertThat(newPlan.getTrackable()).hasToString("tiny#0");
        assertThat(subPool).extracting(Object::toString).containsExactly("big#0", "small#0", "tiny#0");
        assertThat(plans).containsExactly(holder);
    }

    @Test
    void assigningOnePlanFromASubPoolRefusesAPlanAlreadyInTheListAndKeepsItsUnit() {
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("big", 5),
                new TestTrackable("small", 1)));
        TestPlan holder = plan("holder", 1);
        holder.setTrackable(unit(assignment.getTrackables(), "big#0"));

        assertThat(assignment.assignWithTrackables(List.of(holder), holder, assignment.getTrackables())).isFalse();
        assertThat(assignment.assign(List.of(holder), holder)).isFalse();

        assertThat(holder.getTrackable()).as("a refused plan is not re-assigned").hasToString("big#0");
    }

    @Test
    void aRefusedSingleUnitProbeLeavesThePlansCurrentUnitInPlace() {
        // The capacity hook clears the plan's unit on refusal; probing on a copy keeps that from reaching the plan.
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("big", 5),
                new TestTrackable("small", 1)));
        TestPlan plan = plan("p", 3);
        assertThat(assignment.assignWithTrackable(plan, unit(assignment.getTrackables(), "big#0"))).isTrue();

        assertThat(assignment.assignWithTrackable(plan, unit(assignment.getTrackables(), "small#0"))).isFalse();

        assertThat(plan.getTrackable()).hasToString("big#0");
    }

    @Test
    void anAcceptedSingleUnitProbeAssignsThatVeryUnit() {
        // Not the comparator's choice: the caller named the unit.
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("big", 5),
                new TestTrackable("small", 1)));
        TestPlan plan = plan("p", 1);
        TestTrackable big = unit(assignment.getTrackables(), "big#0");

        assertThat(assignment.assignWithTrackable(plan, big)).isTrue();

        assertThat(plan.getTrackable()).isSameAs(big);
    }

    @Test
    void aFiniteFleetNeverSharesUnits() {
        assertThat(new TestAssignment(List.of(new TestTrackable("T", 5))).sharesUnits()).isFalse();
        assertThat(new TestSecondDispatchAssignment(List.of(new TestTrackable("T", 5))).sharesUnits()).isFalse();
    }

}
