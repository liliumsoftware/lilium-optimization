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

import com.github.liliumsoftware.optimization.support.SpeedAwareAssignment;
import com.github.liliumsoftware.optimization.support.TestAssignment;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the idle-unit bookkeeping the zone groups rely on: {@link TrackableAssignment#withoutIdleUnits} keeps idle units
 * out of reach, {@link TrackableAssignment#hasIdleUnits} sees them, and {@link TrackableAssignment#wantedIdleUnits}
 * reports exactly when a group could have used one (a plan an idle unit would carry, or a cheaper idle unit of another
 * category).
 */
class TrackableAssignmentIdleUnitsTest {

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

    private TestPlan planOfLength(String code, int length) {
        return new TestPlan(code, List.of(origin, TestNode.customer(code + "-c", 0, length, 1)));
    }

    // ---------------------------------------------------------------- withoutIdleUnits / hasIdleUnits

    @Test
    void withoutIdleUnitsIsAReadOnlySnapshotOfTheOtherPlans() {
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("X", 5), new TestTrackable("Y", 5)));
        TestPlan other = plan("other", 1);
        TestPlan mine = plan("mine", 1);
        List<TestPlan> otherPlans = new ArrayList<>(List.of(other));

        List<TestPlan> restricted = assignment.withoutIdleUnits(otherPlans, List.of(mine));
        otherPlans.add(plan("later", 1));

        assertThat(restricted).containsExactly(other);
        assertThatThrownBy(() -> restricted.add(mine)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(assignment.wantedIdleUnits(restricted)).as("nothing was refused yet").isFalse();
    }

    @Test
    void hasIdleUnitsTellsWhetherTheGivenPlansLeaveAUnitOfThePoolUnused() {
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("X", 5), new TestTrackable("Y", 5)));
        TestPlan onX = plan("x", 1);
        onX.setTrackable(unit(assignment.getTrackables(), "X#0"));
        TestPlan onY = plan("y", 1);
        onY.setTrackable(unit(assignment.getTrackables(), "Y#0"));
        TestPlan none = plan("none", 1);

        assertThat(assignment.hasIdleUnits(List.of())).isTrue();
        assertThat(assignment.hasIdleUnits(List.of(onX, none))).isTrue();
        assertThat(assignment.hasIdleUnits(List.of(onX, onY))).isFalse();
        assertThat(assignment.hasIdleUnits(List.of(onX, onY, none))).isFalse();
        assertThat(new TestAssignment(List.of()).hasIdleUnits(List.of())).as("no fleet, no idle unit").isFalse();
    }

    // ---------------------------------------------------------------- wantedIdleUnits

    @Test
    void aPlainOtherPlansListNeverReportsAWantedIdleUnit() {
        // Even a refusal that an idle unit would have avoided is only recorded on a withoutIdleUnits list.
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("X", 5), new TestTrackable("Y", 5)));
        TestPlan other = plan("other", 1);
        other.setTrackable(unit(assignment.getTrackables(), "X#0"));
        List<TestPlan> plain = new ArrayList<>(List.of(other));

        assertThat(assignment.assignGreedy(plain, List.of(plan("a", 1), plan("b", 1)))).isFalse();
        assertThat(assignment.assign(plain, new ArrayList<>(List.of(plan("c", 1), plan("d", 1))))).isFalse();

        assertThat(assignment.wantedIdleUnits(plain)).isFalse();
        assertThat(assignment.wantedIdleUnits(new ArrayList<>())).isFalse();
    }

    @Test
    void anUnassignedPlanThatAnIdleUnitWouldCarryMarksTheIdleUnitsWanted() {
        // The group holds small (capacity 1); big (5) is idle. q has 3 customers: nothing in reach carries it, the
        // relocation fails, and the idle big unit would have - so the group wanted an idle unit. q stays unassigned
        // (the idle unit is only probed, on a copy).
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("small", 1),
                new TestTrackable("big", 5)));
        TestPlan p = plan("p", 1);
        p.setTrackable(unit(assignment.getTrackables(), "small#0"));
        TestPlan q = plan("q", 3);
        List<TestPlan> group = new ArrayList<>(List.of(p, q));
        List<TestPlan> restricted = assignment.withoutIdleUnits(List.of(), group);

        assertThat(assignment.assign(restricted, group)).isFalse();

        assertThat(assignment.wantedIdleUnits(restricted)).isTrue();
        assertThat(group.get(0).getTrackable()).hasToString("small#0");
        assertThat(group.get(1).getTrackable()).isNull();
    }

    @Test
    void anUnassignedPlanNoIdleUnitCarriesLeavesTheIdleUnitsUnwanted() {
        // The idle unit mid (2) cannot carry the 3-customer plan either, and moving p onto it is not cheaper (the cost
        // does not depend on the unit): wanting it would only make the ordered phase repeat a refusal.
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("small", 1),
                new TestTrackable("mid", 2)));
        TestPlan p = plan("p", 1);
        p.setTrackable(unit(assignment.getTrackables(), "small#0"));
        List<TestPlan> group = new ArrayList<>(List.of(p, plan("q", 3)));
        List<TestPlan> restricted = assignment.withoutIdleUnits(List.of(), group);

        assertThat(assignment.assign(restricted, group)).isFalse();

        assertThat(assignment.wantedIdleUnits(restricted)).isFalse();
    }

    @Test
    void withoutAnyIdleUnitARefusalIsNotAWantedIdleUnit() {
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("X", 5)));
        TestPlan p = plan("p", 1);
        p.setTrackable(unit(assignment.getTrackables(), "X#0"));
        List<TestPlan> group = new ArrayList<>(List.of(p, plan("extra", 1)));
        List<TestPlan> restricted = assignment.withoutIdleUnits(List.of(), group);

        assertThat(assignment.assign(restricted, group)).isFalse();

        assertThat(assignment.wantedIdleUnits(restricted)).isFalse();
    }

    @ParameterizedTest(name = "idle unit of capacity {0} wanted: {1}")
    @CsvSource({
            // 100 x 2 = 200 < 500: cheaper, other category -> wanted
            "2, true",
            // same category as the held unit: never a reassign candidate
            "5, false",
            // 100 x 7 = 700 > 500: other category but dearer -> not wanted
            "7, false"
    })
    void anIdleUnitOfAnotherCategoryIsWantedOnlyWhenItWouldMakeAPlanCheaper(int idleCapacity, boolean wanted) {
        // Speed-aware cost (length x capacity): p runs 100 on A (5) for 500. Every plan is assigned, so only the
        // cheaper-idle-unit rule can mark the idle unit wanted; p keeps A either way, the idle unit is out of reach.
        SpeedAwareAssignment assignment = new SpeedAwareAssignment(List.of(new TestTrackable("A", 5),
                new TestTrackable("I", idleCapacity)));
        TestPlan p = planOfLength("p", 100);
        assignment.assignWithTrackable(p, unit(assignment.getTrackables(), "A#0"));
        List<TestPlan> group = new ArrayList<>(List.of(p));
        List<TestPlan> restricted = assignment.withoutIdleUnits(List.of(), group);

        assertThat(assignment.assign(restricted, group)).isTrue();

        assertThat(assignment.wantedIdleUnits(restricted)).isEqualTo(wanted);
        assertThat(group.getFirst().getTrackable()).hasToString("A#0");
        assertThat(group.getFirst().getCost()).isEqualTo(500);
    }

    @Test
    void aWantedIdleUnitStaysRecordedAfterALaterSuccessfulRun() {
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("X", 5), new TestTrackable("Y", 5)));
        TestPlan p = plan("p", 1);
        p.setTrackable(unit(assignment.getTrackables(), "X#0"));
        List<TestPlan> restricted = assignment.withoutIdleUnits(List.of(), List.of(p));

        assertThat(assignment.assign(restricted, new ArrayList<>(List.of(p.copy(), plan("extra", 1))))).isFalse();
        assertThat(assignment.wantedIdleUnits(restricted)).isTrue();

        assertThat(assignment.assign(restricted, new ArrayList<>(List.of(p.copy())))).isTrue();
        assertThat(assignment.wantedIdleUnits(restricted)).isTrue();
    }

    // ---------------------------------------------------------------- assignGreedy

    @Test
    void assignGreedyAgainstAWithoutIdleUnitsListKeepsTheIdleUnitOutOfReach() {
        // X is held by the group, Y is idle. Against the (empty!) restricted list the extra plan is refused - with a
        // plain empty list it would have taken Y. The earlier plan keeps its unit, and the refusal is recorded.
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("X", 5), new TestTrackable("Y", 5)));
        TestPlan p = plan("p", 1);
        p.setTrackable(unit(assignment.getTrackables(), "X#0"));
        List<TestPlan> restricted = assignment.withoutIdleUnits(List.of(), List.of(p));
        assertThat(restricted).isEmpty();

        TestPlan held = p.copy();
        TestPlan extra = plan("extra", 1);
        assertThat(assignment.assignGreedy(restricted, List.of(held, extra))).isFalse();

        assertThat(held.getTrackable()).hasToString("X#0");
        assertThat(extra.getTrackable()).isNull();
        assertThat(assignment.wantedIdleUnits(restricted)).isTrue();

        TestPlan again = p.copy();
        TestPlan extraAgain = plan("extra", 1);
        assertThat(assignment.assignGreedy(new ArrayList<>(), List.of(again, extraAgain))).isTrue();
        assertThat(again.getTrackable()).hasToString("X#0");
        assertThat(extraAgain.getTrackable()).hasToString("Y#0");
    }

    @Test
    void assignGreedyAgainstAPlainListSkipsTheUnitsTheOtherPlansHold() {
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("X", 5), new TestTrackable("Y", 5),
                new TestTrackable("Z", 5)));
        TestPlan other = plan("other", 1);
        other.setTrackable(unit(assignment.getTrackables(), "X#0"));
        TestPlan a = plan("a", 1);
        TestPlan b = plan("b", 1);

        assertThat(assignment.assignGreedy(List.of(other), List.of(a, b))).isTrue();

        // smallest-fitting first with the code as tie-break, X being out of reach: a takes Y, b takes Z
        assertThat(a.getTrackable()).hasToString("Y#0");
        assertThat(b.getTrackable()).hasToString("Z#0");
    }

    @Test
    void assignGreedyStopsAtTheFirstPlanItCannotAssign() {
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("X", 5), new TestTrackable("Y", 5)));
        TestPlan other = plan("other", 1);
        other.setTrackable(unit(assignment.getTrackables(), "X#0"));
        TestPlan a = plan("a", 1);
        TestPlan tooBig = plan("big", 9);
        TestPlan c = plan("c", 1);

        assertThat(assignment.assignGreedy(List.of(other), List.of(a, tooBig, c))).isFalse();

        assertThat(a.getTrackable()).hasToString("Y#0");
        assertThat(tooBig.getTrackable()).isNull();
        assertThat(c.getTrackable()).as("never tried").isNull();
    }

}
