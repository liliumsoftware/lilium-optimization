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
import com.github.liliumsoftware.optimization.clarkewright.AbstractPartialClarkeWrightAlgorithm;
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Concrete {@link AbstractPartialClarkeWrightAlgorithm}: re-plans freed customers against a restricted
 * resource pool, using the same seed-plan layout as {@link TestClarkeWright}.
 */
public final class TestPartialClarkeWright
        extends AbstractPartialClarkeWrightAlgorithm<TestPlan, TestNode, TestTrackable> {

    private final TestNode destination;

    public TestPartialClarkeWright(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
            TrackableAssignment<TestPlan, TestTrackable> trackableAssignment,
            LocalSearchAlgorithm<TestPlan> localSearchAlgorithm,
            TestNode destination, OptimizationType optimizationType) {
        super(costCalculation, trackableAssignment, localSearchAlgorithm, optimizationType);
        this.destination = destination;
    }

    public TestPartialClarkeWright(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
            TrackableAssignment<TestPlan, TestTrackable> trackableAssignment,
            LocalSearchAlgorithm<TestPlan> localSearchAlgorithm,
            TestNode destination, OptimizationType optimizationType,
            List<Double> lambdas, List<Double> betas) {
        super(costCalculation, trackableAssignment, localSearchAlgorithm, optimizationType, lambdas, betas);
        this.destination = destination;
    }

    @Override
    protected Comparator<TestPlan> planComparator() {
        return Comparator.comparingLong(TestPlan::getCost).thenComparing(TestPlan::getCode);
    }

    @Override
    protected List<TestPlan> getInitialPlans(TestNode node, List<TestNode> customers) {
        List<TestPlan> plans = new ArrayList<>();
        for (TestNode customer : customers) {
            List<TestNode> nodes = new ArrayList<>();
            nodes.add(node);
            nodes.add(customer);
            if (destination != null) {
                nodes.add(destination);
            }
            plans.add(new TestPlan("pcw-" + customer.getCode(), nodes, customer.getZone()));
        }
        return plans;
    }

    @Override
    protected TestPlan getNewPlan(TestPlan plan, List<TestNode> nodes) {
        return new TestPlan(plan.getCode(), nodes, plan.getZone());
    }

}
