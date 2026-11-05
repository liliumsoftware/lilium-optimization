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

package com.github.liliumsoftware.optimization.clarkewright;

import com.github.liliumsoftware.optimization.OptimizationType;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.NoOpLocalSearch;
import com.github.liliumsoftware.optimization.support.PlanInvariants;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPartialClarkeWright;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the "free units first" rule of {@link AbstractPartialClarkeWrightAlgorithm}: {@code apply} first plans the
 * uncovered customers on the units of the current pool no plan holds and keeps that result, leaving the existing plans
 * untouched; only when that plans nothing do resource hand-overs run. A standalone {@code plan(...)} draws from the
 * current pool.
 *
 * <p>Open routes from {@code O(0,0)} on the y axis; {@code C(0,30)} and {@code D(0,40)} merge into {@code C-D}.
 */
class PartialClarkeWrightFreeUnitsTest {

    private static final BiPredicate<TestPlan, TestTrackable> ANY = (plan, unit) -> true;

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode a = TestNode.customer("A", 0, 10, 1);
    private final TestNode b = TestNode.customer("B", 0, 20, 1);
    private final TestNode c = TestNode.customer("C", 0, 30, 1);
    private final TestNode d = TestNode.customer("D", 0, 40, 1);

    private static List<String> routes(List<TestPlan> plans) {
        return plans.stream().map(p -> p.getCustomers().stream().map(TestNode::getCode)
                .collect(Collectors.joining("-"))).toList();
    }

    private static TestTrackable unit(String code, int capacity, int count, int index) {
        TestTrackable unit = new TestTrackable(code, capacity, count, false);
        unit.setIndex(index);
        return unit;
    }

    private TestPartialClarkeWright partial(RuleAssignment assignment) {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, a, b, c, d));
        return new TestPartialClarkeWright(calculation, assignment, new NoOpLocalSearch(), null,
                OptimizationType.NO_RESTRICTION);
    }

    private List<TestNode> customers() {
        return List.of(a, b, c, d);
    }

    @Test
    void aFreeUnitServesTheUncoveredCustomersWithoutAHandOver() {
        // p1 holds T1 and T2 is free. Before, the first hand-over moved p1 to T2 and C-D took T1; now C-D takes the
        // free T2 and p1 is left alone.
        RuleAssignment assignment = new RuleAssignment(List.of(new TestTrackable("T1", 4), new TestTrackable("T2", 4)),
                ANY);
        TestPlan p1 = new TestPlan("p1", List.of(origin, a, b));
        List<TestPlan> plans = new ArrayList<>(List.of(p1));
        assertThat(assignment.assign(plans)).isTrue();

        List<TestPlan> result = partial(assignment).apply(origin, customers(), plans);

        assertThat(result).isSameAs(plans);
        assertThat(result.getFirst()).as("the existing plan is not touched").isSameAs(p1);
        assertThat(routes(result)).containsExactly("A-B", "C-D");
        assertThat(result).extracting(p -> p.getTrackable().getCode()).containsExactly("T1", "T2");
        PlanInvariants.assertValidSolution(result, origin, null, customers());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void aPlanWithoutAResourceDoesNotKeepTheFreeUnitsIdle(int units) {
        // No plan holds a unit, so the hand-over search could do nothing and C, D stayed unplanned; the free units now
        // serve them.
        RuleAssignment assignment = new RuleAssignment(List.of(new TestTrackable("T", 4, units, false)), ANY);
        TestPlan p1 = new TestPlan("p1", List.of(origin, a, b));
        List<TestPlan> plans = new ArrayList<>(List.of(p1));

        List<TestPlan> result = partial(assignment).apply(origin, customers(), plans);

        assertThat(result.getFirst()).isSameAs(p1);
        assertThat(p1.getTrackable()).isNull();
        assertThat(routes(result)).containsExactly("A-B", "C-D");
        assertThat(result.get(1).getTrackable()).isEqualTo(unit("T", 4, units, 0));
    }

    @Test
    void theFreeUnitsAreTakenFromTheCurrentPool() {
        // The current pool is narrowed to T1 and T3. p1 holds T1, so only T3 is free; T2 is outside the pool. Before,
        // the hand-over moved p1 to T2 and C-D took T1.
        RuleAssignment assignment = new RuleAssignment(List.of(new TestTrackable("T1", 4), new TestTrackable("T2", 4),
                new TestTrackable("T3", 4)), ANY).withPool(List.of(unit("T1", 4, 1, 0), unit("T3", 4, 1, 0)));
        TestPlan p1 = new TestPlan("p1", List.of(origin, a, b));
        List<TestPlan> plans = new ArrayList<>(List.of(p1));
        assertThat(assignment.assign(plans)).isTrue();

        List<TestPlan> result = partial(assignment).apply(origin, customers(), plans);

        assertThat(routes(result)).containsExactly("A-B", "C-D");
        assertThat(result).extracting(p -> p.getTrackable().getCode()).containsExactly("T1", "T3");
    }

    @Test
    void whenTheFreeUnitsPlanNothingTheHandOverSearchRuns() {
        // C and D are cold and only T1 (held by p1) is refrigerated: the free T2 plans nothing, so p1 is handed over
        // to T2 and C-D takes T1.
        RuleAssignment assignment = RuleAssignment.coldChain(List.of(new TestTrackable("T1", 4),
                new TestTrackable("T2", 4)), Set.of("T1"), Set.of("C", "D"));
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", List.of(origin, a, b))));
        assertThat(assignment.assign(plans)).isTrue();

        List<TestPlan> result = partial(assignment).apply(origin, customers(), plans);

        assertThat(routes(result)).containsExactly("A-B", "C-D");
        assertThat(result).extracting(p -> p.getTrackable().getCode()).containsExactly("T2", "T1");
    }

    @Test
    void aStandalonePlanDrawsFromTheCurrentPool() {
        // Three units T#0..T#2 of capacity 2, the current pool narrowed to T#1 and T#2: the two routes use only those.
        // Before, a standalone plan(...) drew from the whole expanded fleet and used T#0 and T#1.
        RuleAssignment assignment = new RuleAssignment(List.of(new TestTrackable("T", 2, 3, false)), ANY)
                .withPool(List.of(unit("T", 2, 3, 1), unit("T", 2, 3, 2)));

        List<TestPlan> plans = partial(assignment).plan(origin, customers());

        assertThat(routes(plans)).containsExactlyInAnyOrder("A-B", "C-D");
        assertThat(plans).extracting(p -> p.getTrackable().getIndex()).containsExactlyInAnyOrder(1, 2);
    }

}
