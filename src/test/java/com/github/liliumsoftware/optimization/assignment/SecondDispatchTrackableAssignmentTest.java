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

import com.github.liliumsoftware.optimization.domain.Planable;
import com.github.liliumsoftware.optimization.domain.Trackable;
import com.github.liliumsoftware.optimization.domain.TrackableCategory;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestSecondDispatchAssignment;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins how {@link SecondDispatchTrackableAssignment} narrows the pool for a second run: which units survive
 * {@code secondDispatchOnly} (a used unit only when it returns <em>and</em> supports a second dispatch), how
 * {@code secondDispatchAndOthers} separates first runs from the rest, and its rejection of a plan without a unit.
 */
class SecondDispatchTrackableAssignmentTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);

    private static TestTrackable unit(List<TestTrackable> pool, String name) {
        return pool.stream().filter(t -> t.toString().equals(name)).findFirst().orElseThrow();
    }

    private static List<String> names(List<?> units) {
        return units.stream().map(Object::toString).toList();
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
    void secondDispatchAndOthersRejectsAPlanWithoutAUnitByName() {
        TestSecondDispatchAssignment assignment = new TestSecondDispatchAssignment(List.of(
                new TestTrackable("V", 5, 1, true), new TestTrackable("W", 5, 1, true)));
        TestPlan assigned = plan("assigned", 1);
        assigned.setTrackable(unit(assignment.currentTrackables(), "V#0"));
        TestPlan unassigned = plan("orphan", 1);
        List<TestTrackable> before = assignment.currentTrackables();

        assertThatThrownBy(() -> assignment.secondDispatchAndOthers(List.of(assigned, unassigned), List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Plan orphan has no trackable");

        assertThat(assignment.currentTrackables()).as("the pool is left as it was").isEqualTo(before);
    }

    @Test
    void secondDispatchOnlyIgnoresAPlanWithoutAUnit() {
        TestSecondDispatchAssignment assignment = new TestSecondDispatchAssignment(List.of(
                new TestTrackable("V", 5, 1, true), new TestTrackable("W", 5, 1, false)));
        TestPlan onW = plan("w", 1);
        onW.setTrackable(unit(assignment.currentTrackables(), "W#0"));

        assignment.secondDispatchOnly(List.of(plan("none", 1), onW));

        assertThat(names(assignment.currentTrackables())).containsExactly("V#0");
    }

    @Test
    void secondDispatchOnlyPutsAFreshCopyOfAReturningUnitBackAfterTheUnusedOnes() {
        // Fleet V (returning), W (one-way), U (returning, unused). V and W are used: the unused U comes first, then
        // the planned units in plan order, of which only V may run again.
        TestSecondDispatchAssignment assignment = new TestSecondDispatchAssignment(List.of(
                new TestTrackable("V", 5, 1, true), new TestTrackable("W", 5, 1, false),
                new TestTrackable("U", 5, 1, true)));
        TestTrackable v = unit(assignment.currentTrackables(), "V#0");
        TestPlan onV = plan("v", 1);
        onV.setTrackable(v);
        TestPlan onW = plan("w", 1);
        onW.setTrackable(unit(assignment.currentTrackables(), "W#0"));

        assignment.secondDispatchOnly(List.of(onV, onW));

        assertThat(names(assignment.currentTrackables())).containsExactly("U#0", "V#0");
        assertThat(assignment.currentTrackables().get(1)).as("re-dispatched as a copy").isNotSameAs(v);
    }

    @Test
    void secondDispatchOnlyThenSecondDispatchAndOthersPlansTheSecondRunFromTheWholeFleet() {
        // secondDispatchOnly narrows the pool to U, V; the second run of V is then planned on a copy of V. The later
        // secondDispatchAndOthers starts again from the whole fleet: units no plan holds (U), plus the units of every
        // plan that is not a first run of a re-dispatched unit (W's plan and V's second run).
        TestSecondDispatchAssignment assignment = new TestSecondDispatchAssignment(List.of(
                new TestTrackable("V", 5, 1, true), new TestTrackable("W", 5, 1, false),
                new TestTrackable("U", 5, 1, true)));
        TestPlan firstV = plan("first-v", 1);
        firstV.setTrackable(unit(assignment.currentTrackables(), "V#0"));
        TestPlan onW = plan("w", 1);
        onW.setTrackable(unit(assignment.currentTrackables(), "W#0"));
        assignment.secondDispatchOnly(List.of(firstV, onW));
        assertThat(names(assignment.currentTrackables())).containsExactly("U#0", "V#0");

        TestPlan secondV = plan("second-v", 1);
        secondV.setSecondDispatch(true);
        assertThat(assignment.assignWithTrackable(secondV, unit(assignment.currentTrackables(), "V#0"))).isTrue();
        TestPlan extra = plan("extra", 1);

        List<TestPlan> toPlan = assignment.secondDispatchAndOthers(List.of(firstV, onW, secondV), List.of(extra));

        assertThat(toPlan).containsExactly(onW, secondV, extra);
        assertThat(names(assignment.currentTrackables())).containsExactly("U#0", "W#0", "V#0");
    }

    @Test
    void secondDispatchAndOthersLeavesOutOnlyTheFirstRunItselfNotAPlanEqualToIt() {
        // Plans compare by code. The first run of V and the plan on W share the code "dup", so they are equal; only
        // the first run (the plan object whose unit is re-dispatched) is left out, the plan on W stays.
        TestSecondDispatchAssignment assignment = new TestSecondDispatchAssignment(List.of(
                new TestTrackable("V", 5, 1, true), new TestTrackable("W", 5, 1, false)));
        TestPlan firstV = plan("dup", 1);
        firstV.setEquality(TestPlan.Equality.CODE);
        firstV.setTrackable(unit(assignment.currentTrackables(), "V#0"));
        TestPlan onW = plan("dup", 2);
        onW.setEquality(TestPlan.Equality.CODE);
        onW.setTrackable(unit(assignment.currentTrackables(), "W#0"));
        assertThat(onW).isEqualTo(firstV);
        TestPlan secondV = plan("second-v", 1);
        secondV.setSecondDispatch(true);
        secondV.setTrackable(unit(assignment.currentTrackables(), "V#0"));

        List<TestPlan> toPlan = assignment.secondDispatchAndOthers(List.of(firstV, onW, secondV), List.of());

        assertThat(toPlan).hasSize(2);
        assertThat(toPlan.get(0)).isSameAs(onW);
        assertThat(toPlan.get(1)).isSameAs(secondV);
        assertThat(names(assignment.currentTrackables())).containsExactly("W#0", "V#0");
    }

    @Test
    void secondDispatchAndOthersPutsAUnitHeldByTwoRemainingPlansIntoThePoolOnce() {
        // V runs three times: its first run is left out, its second and third runs both remain and both hold V. The
        // pool gets the unused U, then V once, as the instance of the second run (the first remaining plan holding it).
        TestSecondDispatchAssignment assignment = new TestSecondDispatchAssignment(List.of(
                new TestTrackable("V", 5, 1, true), new TestTrackable("U", 5, 1, true)));
        TestTrackable v = unit(assignment.currentTrackables(), "V#0");
        TestPlan firstV = plan("first-v", 1);
        firstV.setTrackable(v);
        TestPlan secondV = plan("second-v", 1);
        secondV.setSecondDispatch(true);
        secondV.setTrackable(v.copy());
        TestPlan thirdV = plan("third-v", 1);
        thirdV.setSecondDispatch(true);
        thirdV.setTrackable(v.copy());

        List<TestPlan> toPlan = assignment.secondDispatchAndOthers(List.of(firstV, secondV, thirdV), List.of());

        assertThat(toPlan).containsExactly(secondV, thirdV);
        assertThat(names(assignment.currentTrackables())).containsExactly("U#0", "V#0");
        assertThat(assignment.currentTrackables().get(1)).isSameAs(secondV.getTrackable());
    }

    @Test
    void secondDispatchAndOthersWithoutASecondRunKeepsEveryPlan() {
        TestSecondDispatchAssignment assignment = new TestSecondDispatchAssignment(List.of(
                new TestTrackable("V", 5, 1, true), new TestTrackable("U", 5, 1, true)));
        TestPlan onV = plan("v", 1);
        onV.setTrackable(unit(assignment.currentTrackables(), "V#0"));

        List<TestPlan> toPlan = assignment.secondDispatchAndOthers(List.of(onV), List.of());

        assertThat(toPlan).containsExactly(onV);
        assertThat(names(assignment.currentTrackables())).containsExactly("U#0", "V#0");
    }

    @Test
    void aReturningUnitThatDoesNotSupportASecondDispatchIsDroppedOnceUsed() {
        // R returns and supports a second dispatch, N returns but does not, O does neither; F (one-way) is unused.
        // Only R survives among the used units; an unused unit always stays.
        Fleet assignment = new Fleet(List.of(new Unit("R", true, true), new Unit("N", true, false),
                new Unit("O", false, false), new Unit("F", false, false)));
        List<Run> runs = List.of(new Run("r", assignment.unit("R")), new Run("n", assignment.unit("N")),
                new Run("o", assignment.unit("O")));

        assignment.secondDispatchOnly(runs);

        assertThat(names(assignment.pool())).containsExactly("F#0", "R#0");
    }

    @Test
    void theSecondDispatchRuleCanBeOverriddenPerUnit() {
        Fleet assignment = new Fleet(List.of(new Unit("R", true, true), new Unit("N", true, false))) {
            @Override
            protected boolean supportSecondDispatch(List<Run> plans, Unit trackable) {
                return true;
            }
        };
        List<Run> runs = List.of(new Run("r", assignment.unit("R")), new Run("n", assignment.unit("N")));

        assignment.secondDispatchOnly(runs);

        assertThat(names(assignment.pool())).containsExactly("R#0", "N#0");
    }

    /**
     * A resource whose "returns" and "supports a second dispatch" flags are set independently.
     */
    private static final class Unit implements Trackable<Unit> {

        private final String code;
        private final boolean returns;
        private final boolean supportSecondDispatch;
        private int index;

        private Unit(String code, boolean returns, boolean supportSecondDispatch) {
            this.code = code;
            this.returns = returns;
            this.supportSecondDispatch = supportSecondDispatch;
        }

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public Unit copy() {
            Unit copy = new Unit(code, returns, supportSecondDispatch);
            copy.index = index;
            return copy;
        }

        @Override
        public int getIndex() {
            return index;
        }

        @Override
        public void setIndex(int index) {
            this.index = index;
        }

        @Override
        public boolean isReturns() {
            return returns;
        }

        @Override
        public boolean isSupportSecondDispatch() {
            return supportSecondDispatch;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Unit u && u.code.equals(code) && u.index == index;
        }

        @Override
        public int hashCode() {
            return Objects.hash(code, index);
        }

        @Override
        public String toString() {
            return code + "#" + index;
        }

    }

    /**
     * A node-less plan holding one {@link Unit}.
     */
    private record Run(String code, Unit unit) implements Planable<Run, TestNode, Unit> {

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public List<TestNode> getNodes() {
            return List.of();
        }

        @Override
        public Unit getTrackable() {
            return unit;
        }

        @Override
        public long getCost() {
            return 0;
        }

        @Override
        public Run copy() {
            return new Run(code, unit == null ? null : unit.copy());
        }

    }

    /**
     * Second-dispatch assignment over {@link Unit}s; only the pool narrowing is exercised.
     */
    private static class Fleet extends SecondDispatchTrackableAssignment<Run, Unit> {

        Fleet(List<Unit> units) {
            super(units);
        }

        Unit unit(String code) {
            return currentTrackables.stream().filter(u -> u.getCode().equals(code)).findFirst().orElseThrow();
        }

        List<Unit> pool() {
            return List.copyOf(currentTrackables);
        }

        @Override
        protected boolean assign(Run plan, Unit trackable) {
            return false;
        }

        @Override
        protected boolean assign(Run plan, List<Unit> trackables) {
            return false;
        }

        @Override
        protected Comparator<Unit> comparator(Run plan) {
            return Comparator.comparing(Unit::getCode);
        }

        @Override
        protected TrackableCategory getCategory(Unit trackable) {
            return new TrackableCategory() {
            };
        }

    }

}
