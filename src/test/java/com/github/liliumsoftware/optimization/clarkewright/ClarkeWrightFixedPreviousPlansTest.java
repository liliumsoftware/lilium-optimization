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
import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.NoOpLocalSearch;
import com.github.liliumsoftware.optimization.support.PlanInvariants;
import com.github.liliumsoftware.optimization.support.TestAssignment;
import com.github.liliumsoftware.optimization.support.TestClarkeWright;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPartialClarkeWright;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how Clarke-Wright treats previous plans that cannot be re-assigned ("fixed" plans): they take no part in
 * merging, keep the resource they came with, and their units are withheld from every merge attempt and from the final
 * assignment pass, on every λ worker of the sweep, in the base and the partial variant and under both zone policies.
 *
 * <p>All routes are open paths from {@code O(0,0)} served by non-returning units; the new customers {@code A(0,10)}
 * and {@code B(0,20)} merge into {@code A-B} (saving {@code c(O,B) − c(A,B) = 10}).
 */
class ClarkeWrightFixedPreviousPlansTest {

    private static final List<Double> LAMBDAS = List.of(0.5, 1.0, 2.0);

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode a = TestNode.customer("A", "Z1", 0, 10, 1);
    private final TestNode b = TestNode.customer("B", "Z1", 0, 20, 1);
    private final TestNode p1 = TestNode.customer("P1", "Z1", 0, -10, 1);
    private final TestNode p2 = TestNode.customer("P2", "Z1", 0, -20, 1);
    private final TestNode p3 = TestNode.customer("P3", "Z1", 0, -30, 1);
    private final TestNode p4 = TestNode.customer("P4", "Z1", 0, -40, 1);
    private final TestNode q1 = TestNode.customer("Q1", "Z1", -10, 0, 1);
    private final TestNode q2 = TestNode.customer("Q2", "Z1", -20, 0, 1);
    private final TestNode q3 = TestNode.customer("Q3", "Z1", -30, 0, 1);

    private static List<String> routes(List<TestPlan> plans) {
        return plans.stream().map(p -> p.getCustomers().stream().map(TestNode::getCode)
                .collect(Collectors.joining("-"))).toList();
    }

    private static TestTrackable unit(String code, int capacity, int count, int index) {
        TestTrackable unit = new TestTrackable(code, capacity, count, false);
        unit.setIndex(index);
        return unit;
    }

    private MatrixCostCalculation calculation(OptimizationType type) {
        MatrixCostCalculation calculation = type == OptimizationType.LOCATION_ZONE
                ? new MatrixCostCalculation(type, Map.of("Z1", List.of("Z1"))) : new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, a, b, p1, p2, p3, p4, q1, q2, q3));
        return calculation;
    }

    private TestPlan plan(String code, TestTrackable unit, TestNode... customers) {
        List<TestNode> nodes = new ArrayList<>();
        nodes.add(origin);
        nodes.addAll(List.of(customers));
        TestPlan plan = new TestPlan(code, nodes, "Z1");
        plan.setTrackable(unit);
        return plan;
    }

    @ParameterizedTest
    @EnumSource(Variant.class)
    void theUnitOfAFixedPreviousPlanIsWithheldFromEveryMerge(Variant variant) {
        // The previous plan holds T#0 but serves three customers, more than any unit takes, so it cannot be
        // re-assigned. Before, every merge re-assigned it too and failed; A got T#1 and B was dropped. Now A-B merges
        // on T#1 and T#0 stays with the previous plan.
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T", 2, 2, false)));
        TestPlan previous = plan("previous", unit("T", 2, 2, 0), p1, p2, p3);
        List<TestPlan> snapshot = PlanInvariants.deepCopy(List.of(previous));

        List<TestPlan> plans = variant.create(calculation(variant.type), assignment).plan(origin, List.of(a, b),
                previous);

        assertThat(routes(plans)).containsExactly("A-B", "P1-P2-P3");
        assertThat(plans).extracting(TestPlan::getTrackable).containsExactly(unit("T", 2, 2, 1), unit("T", 2, 2, 0));
        assertThat(plans.get(1)).isNotSameAs(previous);
        assertThat(List.of(previous)).as("the caller's plan is not modified").isEqualTo(snapshot);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(plans, List.of(a, b, p1, p2, p3));
        PlanInvariants.assertTrackablesAreDistinct(plans);
    }

    @ParameterizedTest
    @EnumSource(Variant.class)
    void aPlanThatOnlyFitsTheUnitOfALaterFixedPlanIsFixedToo(Variant variant) {
        // Units Small (1), Mid (2) and Big (3). The first previous plan (three customers, holding Small) is probed
        // first and fits Big; the second (four customers, holding Big) fits nothing and is fixed, which withholds Big.
        // The repeated split then finds that the first plan no longer fits either, so it is fixed as well: both keep
        // their units and A-B merges on Mid. Had the first plan kept taking part, every merge would have failed.
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("Small", 1),
                new TestTrackable("Mid", 2), new TestTrackable("Big", 3)));
        TestPlan first = plan("first", unit("Small", 1, 1, 0), q1, q2, q3);
        TestPlan second = plan("second", unit("Big", 3, 1, 0), p1, p2, p3, p4);

        List<TestPlan> plans = variant.create(calculation(variant.type), assignment).plan(origin, List.of(a, b),
                first, second);

        assertThat(routes(plans)).containsExactly("A-B", "Q1-Q2-Q3", "P1-P2-P3-P4");
        assertThat(plans).extracting(p -> p.getTrackable().getCode()).containsExactly("Mid", "Small", "Big");
        PlanInvariants.assertTrackablesAreDistinct(plans);
    }

    @Test
    void aMergeOverrideCallingSuperWithholdsTheFixedUnitsOnEveryWorker() {
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T", 2, 2, false)));
        TestPlan previous = plan("previous", unit("T", 2, 2, 0), p1, p2, p3);
        CountingMerge cw = new CountingMerge(calculation(OptimizationType.NO_RESTRICTION), assignment);

        List<TestPlan> plans = cw.plan(origin, List.of(a, b), previous);

        assertThat(cw.merges).as("one merge pass per λ").hasValue(LAMBDAS.size());
        assertThat(routes(plans)).containsExactly("A-B", "P1-P2-P3");
        assertThat(plans).extracting(TestPlan::getTrackable).containsExactly(unit("T", 2, 2, 1), unit("T", 2, 2, 0));
    }

    @Test
    void aPreviousPlanThatCanBeReassignedStillTakesPart() {
        // The previous plan holds T#1 and fits a unit, so it takes part and is re-assigned in list order (T#0); A-B
        // takes the other unit. Nothing is fixed, so the result is the one the merge pass produces.
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T", 2, 2, false)));
        TestPlan previous = plan("previous", unit("T", 2, 2, 1), p1);

        List<TestPlan> plans = new TestClarkeWright(calculation(OptimizationType.NO_RESTRICTION), assignment,
                new NoOpLocalSearch(), null, OptimizationType.NO_RESTRICTION, LAMBDAS).plan(origin, List.of(a, b),
                previous);

        assertThat(routes(plans)).containsExactly("P1", "A-B");
        assertThat(plans).extracting(TestPlan::getTrackable).containsExactly(unit("T", 2, 2, 0), unit("T", 2, 2, 1));
    }

    /**
     * The four entry paths: base and partial variant, each under both zone policies, all sweeping {@link #LAMBDAS}.
     */
    enum Variant {
        BASE(OptimizationType.NO_RESTRICTION, false),
        BASE_ZONED(OptimizationType.LOCATION_ZONE, false),
        PARTIAL(OptimizationType.NO_RESTRICTION, true),
        PARTIAL_ZONED(OptimizationType.LOCATION_ZONE, true);

        private final OptimizationType type;
        private final boolean partial;

        Variant(OptimizationType type, boolean partial) {
            this.type = type;
            this.partial = partial;
        }

        AbstractClarkeWrightAlgorithm<TestPlan, TestNode> create(MatrixCostCalculation calculation,
                TestAssignment assignment) {
            if (partial) {
                return new TestPartialClarkeWright(calculation, assignment, new NoOpLocalSearch(), null, type, LAMBDAS,
                        List.of());
            }
            return new TestClarkeWright(calculation, assignment, new NoOpLocalSearch(), null, type, LAMBDAS);
        }
    }

    /**
     * A construction whose merge override counts its calls and delegates to {@code super}.
     */
    private static final class CountingMerge extends AbstractClarkeWrightAlgorithm<TestPlan, TestNode> {

        private final AtomicInteger merges = new AtomicInteger();

        CountingMerge(PathCostCalculation<TestPlan, TestNode, ?> calculation,
                TrackableAssignment<TestPlan, ?> assignment) {
            super(calculation, assignment, new NoOpLocalSearch(),
                    OptimizationType.NO_RESTRICTION, LAMBDAS);
        }

        @Override
        protected List<TestPlan> mergePlans(List<TestPlan> plans, List<Saving> savings) {
            merges.incrementAndGet();
            return super.mergePlans(plans, savings);
        }

        @Override
        protected Comparator<TestPlan> planComparator() {
            return Comparator.comparingLong(TestPlan::getCost).thenComparing(TestPlan::getCode);
        }

        @Override
        protected List<TestPlan> getInitialPlans(TestNode node, List<TestNode> customers) {
            List<TestPlan> plans = new ArrayList<>();
            for (TestNode customer : customers) {
                plans.add(new TestPlan("cw-" + customer.getCode(), List.of(node, customer), customer.getZone()));
            }
            return plans;
        }

        @Override
        protected TestPlan getNewPlan(TestPlan plan, List<TestNode> nodes) {
            return new TestPlan(plan.getCode(), nodes, plan.getZone());
        }

    }

}
