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
import com.github.liliumsoftware.optimization.support.TestAssignment;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link AbstractClarkeWrightAlgorithm#isMergeable(Saving)} hook: by default every saving is tried, so a
 * negative one is merged when the fleet can serve the result; an override with {@code saving.value() > 0} keeps the
 * routes of non-positive savings apart and still merges the positive ones.
 *
 * <p>Open routes from {@code O(0,0)} on non-returning units, so the saving of chaining i into j is
 * {@code c(O,j) − c(i,j)}: for {@code A(0,10)} and {@code B(0,-10)} both orientations save {@code 10 − 20 = −10};
 * for {@code A} and {@code C(0,20)}, {@code s(A,C) = 20 − 10 = 10}.
 */
class ClarkeWrightMergeableTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode a = TestNode.customer("A", 0, 10, 1);
    private final TestNode b = TestNode.customer("B", 0, -10, 1);
    private final TestNode c = TestNode.customer("C", 0, 20, 1);

    private static List<String> routes(List<TestPlan> plans) {
        return plans.stream().map(p -> p.getCustomers().stream().map(TestNode::getCode)
                .collect(Collectors.joining("-"))).toList();
    }

    private Construction construction(boolean positiveOnly) {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, a, b, c));
        return new Construction(calculation, new TestAssignment(List.of(new TestTrackable("T", 2, 2, false))),
                positiveOnly);
    }

    @Test
    void byDefaultANegativeSavingIsMerged() {
        Construction cw = construction(false);

        List<TestPlan> plans = cw.plan(origin, List.of(a, b));

        assertThat(routes(plans)).containsExactly("A-B");
        assertThat(PlanInvariants.referenceTotalCost(plans)).as("10 + 20 instead of 10 + 10").isEqualTo(30);
        assertThat(cw.asked).as("the hook is asked once per saving, both orientations").hasValue(2);
    }

    @Test
    void anOverrideAcceptingOnlyPositiveSavingsKeepsTheRoutesApart() {
        List<TestPlan> plans = construction(true).plan(origin, List.of(a, b));

        assertThat(routes(plans)).containsExactlyInAnyOrder("A", "B");
        assertThat(PlanInvariants.referenceTotalCost(plans)).isEqualTo(20);
        PlanInvariants.assertTrackablesAreDistinct(plans);
    }

    @Test
    void anOverrideAcceptingOnlyPositiveSavingsStillMergesThePositiveOnes() {
        List<TestPlan> plans = construction(true).plan(origin, List.of(a, c));

        assertThat(routes(plans)).containsExactly("A-C");
        assertThat(PlanInvariants.referenceTotalCost(plans)).isEqualTo(20);
    }

    /**
     * A construction that optionally merges only positive savings and counts how often the hook is asked.
     */
    private static final class Construction extends AbstractClarkeWrightAlgorithm<TestPlan, TestNode> {

        private final boolean positiveOnly;
        private final AtomicInteger asked = new AtomicInteger();

        Construction(MatrixCostCalculation calculation, TestAssignment assignment, boolean positiveOnly) {
            super(calculation, assignment, new NoOpLocalSearch(), OptimizationType.NO_RESTRICTION);
            this.positiveOnly = positiveOnly;
        }

        @Override
        protected boolean isMergeable(Saving saving) {
            asked.incrementAndGet();
            return positiveOnly ? saving.value() > 0 : super.isMergeable(saving);
        }

        @Override
        protected Comparator<TestPlan> planComparator() {
            return Comparator.comparingLong(TestPlan::getCost).thenComparing(TestPlan::getCode);
        }

        @Override
        protected List<TestPlan> getInitialPlans(TestNode node, List<TestNode> customers) {
            List<TestPlan> plans = new ArrayList<>();
            for (TestNode customer : customers) {
                plans.add(new TestPlan("cw-" + customer.getCode(), List.of(node, customer)));
            }
            return plans;
        }

        @Override
        protected TestPlan getNewPlan(TestPlan plan, List<TestNode> nodes) {
            return new TestPlan(plan.getCode(), nodes);
        }

    }

}
