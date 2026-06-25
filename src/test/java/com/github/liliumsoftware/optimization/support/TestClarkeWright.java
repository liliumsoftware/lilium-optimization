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

package com.github.liliumsoftware.optimization.support;

import com.github.liliumsoftware.optimization.OptimizationType;
import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.clarkewright.AbstractClarkeWrightAlgorithm;
import com.github.liliumsoftware.optimization.clarkewright.Saving;
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Concrete Clarke-Wright construction over {@link TestPlan}s.
 *
 * <p>The seed plans it produces are {@code ORIGIN -> customer -> DESTINATION} round trips when the fixture has
 * a return depot, and open {@code ORIGIN -> customer} paths when it does not — both layouts are legal under
 * {@code Planable}'s contract, and the open one is what exposes how the merge core handles a missing
 * destination.
 *
 * <p>Also exposes the {@code protected} savings/merge internals so the tests can check them directly instead
 * of inferring them from an end-to-end run.
 */
public final class TestClarkeWright extends AbstractClarkeWrightAlgorithm<TestPlan, TestNode> {

    private final TestNode destination;
    private TestPlan.Equality planEquality = TestPlan.Equality.VALUE;
    private boolean withoutOrigin;

    public TestClarkeWright(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
            TrackableAssignment<TestPlan, ?> trackableAssignment,
            LocalSearchAlgorithm<TestPlan> localSearchAlgorithm,
            TestNode destination) {
        this(costCalculation, trackableAssignment, localSearchAlgorithm, destination, OptimizationType.NO_RESTRICTION);
    }

    public TestClarkeWright(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
            TrackableAssignment<TestPlan, ?> trackableAssignment,
            LocalSearchAlgorithm<TestPlan> localSearchAlgorithm,
            TestNode destination, OptimizationType optimizationType) {
        super(costCalculation, trackableAssignment, localSearchAlgorithm, optimizationType);
        this.destination = destination;
    }

    public TestClarkeWright(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
            TrackableAssignment<TestPlan, ?> trackableAssignment,
            LocalSearchAlgorithm<TestPlan> localSearchAlgorithm,
            TestNode destination, OptimizationType optimizationType, List<Double> lambdas) {
        super(costCalculation, trackableAssignment, localSearchAlgorithm, optimizationType, lambdas);
        this.destination = destination;
    }

    public TestClarkeWright(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
            TrackableAssignment<TestPlan, ?> trackableAssignment,
            LocalSearchAlgorithm<TestPlan> localSearchAlgorithm,
            TestNode destination, OptimizationType optimizationType,
            List<Double> lambdas, List<Double> betas) {
        super(costCalculation, trackableAssignment, localSearchAlgorithm, optimizationType, lambdas, betas);
        this.destination = destination;
    }

    /**
     * Makes every plan this construction creates (seeds and merge results) use the given equality contract.
     */
    public TestClarkeWright withPlanEquality(TestPlan.Equality equality) {
        this.planEquality = equality;
        return this;
    }

    /**
     * Makes the seed plans start at their customer, without an origin stop ({@code customer[, DESTINATION]}), as a
     * consumer whose routes are product sequences does.
     */
    public TestClarkeWright withoutOriginStops() {
        this.withoutOrigin = true;
        return this;
    }

    // ------------------------------------------------------------------ exposed internals

    public List<Saving> savings(TestNode origin, List<TestNode> customers, double lambda, double beta) {
        return getSavings(origin, customers, node -> origin, node -> origin, lambda, beta);
    }

    public double savingValue(TestNode origin, TestNode nodeI, TestNode nodeJ, double lambda) {
        return getSavingValue(origin, origin, nodeI, nodeJ, lambda);
    }

    public double savingValue(TestNode origin, TestNode nodeI, TestNode nodeJ, double lambda, double beta) {
        return getSavingValue(origin, origin, origin, origin, nodeI, nodeJ, lambda, beta);
    }

    /**
     * The savings for merging {@code plans}, computed from their real route starts and ends.
     */
    public List<Saving> savingsForPlans(TestNode origin, List<TestNode> customers, List<TestPlan> plans,
            double lambda) {
        return getSavings(origin, customers, getRouteStarts(origin, plans), getRouteEnds(origin, plans), lambda, 0);
    }

    public boolean closedRoute(TestPlan plan) {
        return isClosedRoute(plan);
    }

    public List<TestPlan> merge(List<TestPlan> plans, List<Saving> savings) {
        return mergePlans(plans, savings);
    }

    public java.util.function.Function<TestNode, TestNode> routeEnds(TestNode origin, List<TestPlan> plans) {
        return getRouteEnds(origin, plans);
    }

    public List<TestPlan> initialPlans(TestNode origin, List<TestNode> customers) {
        return getInitialPlans(origin, customers);
    }

    public Comparator<List<TestPlan>> comparatorOfPlans() {
        return plansComparator();
    }

    // ------------------------------------------------------------------ domain hooks

    @Override
    protected Comparator<TestPlan> planComparator() {
        return Comparator.comparingLong(TestPlan::getCost).thenComparing(TestPlan::getCode);
    }

    @Override
    protected List<TestPlan> getInitialPlans(TestNode node, List<TestNode> customers) {
        List<TestPlan> plans = new ArrayList<>();
        for (TestNode customer : customers) {
            List<TestNode> nodes = new ArrayList<>();
            if (!withoutOrigin) {
                nodes.add(node);
            }
            nodes.add(customer);
            if (destination != null) {
                nodes.add(destination);
            }
            TestPlan plan = new TestPlan("cw-" + customer.getCode(), nodes, customer.getZone());
            plan.setEquality(planEquality);
            plans.add(plan);
        }
        return plans;
    }

    @Override
    protected TestPlan getNewPlan(TestPlan plan, List<TestNode> nodes) {
        TestPlan newPlan = new TestPlan(plan.getCode(), nodes, plan.getZone());
        newPlan.setEquality(planEquality);
        return newPlan;
    }

}
