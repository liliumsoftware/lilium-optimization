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

import com.github.liliumsoftware.optimization.BaseAlgorithm;
import com.github.liliumsoftware.optimization.BaseAlgorithmRunner;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.domain.PlanModifier;
import com.github.liliumsoftware.optimization.generator.PlanCodeGenerator;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * Concrete {@link BaseAlgorithmRunner} standing in for a host application's orchestration: it initializes the
 * cost matrix, "constructs" a trivial single plan holding every customer, and finalizes through the plan
 * modifiers. The protected skeleton hooks are re-exposed so the tests can drive them individually — that
 * skeleton, not the toy construction, is what is under test.
 */
public final class TestRunner extends BaseAlgorithmRunner<TestPlan, TestNode, TestNode> {

    private final List<BaseAlgorithm> algorithms;
    private final PlanCodeGenerator generator;

    @Getter
    private List<TestPlan> plans = new ArrayList<>();

    public TestRunner(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
            PlanCodeGenerator planCodeGenerator, List<BaseAlgorithm> algorithms,
            List<PlanModifier<TestPlan>> planModifiers) {
        super(costCalculation, planCodeGenerator, planModifiers);
        this.algorithms = algorithms;
        this.generator = planCodeGenerator;
    }

    @Override
    public void plan(TestNode node, List<TestNode> customers) {
        initialize(node, customers);
        List<TestNode> nodes = new ArrayList<>();
        nodes.add(node);
        nodes.addAll(customers);
        TestPlan plan = new TestPlan(generator.generate(), nodes);
        costCalculation.calculateCost(plan);
        plans = new ArrayList<>(List.of(plan));
        finalizePlans(plans);
    }

    // ------------------------------------------------------------------ exposed skeleton hooks

    public void initializeMatrix(TestNode node, List<TestNode> customers, TestNode... otherNodes) {
        initialize(node, customers, otherNodes);
    }

    @Override
    public void finalizePlans(List<TestPlan> plansToFinalize) {
        super.finalizePlans(plansToFinalize);
    }

    public List<TestNode> unplannedNodes(List<TestPlan> currentPlans, List<TestNode> customers) {
        return getUnplannedNodes(currentPlans, customers);
    }

    public boolean interrupted() {
        return isInterrupted();
    }

    @Override
    protected List<BaseAlgorithm> algorithms() {
        return algorithms;
    }

}
