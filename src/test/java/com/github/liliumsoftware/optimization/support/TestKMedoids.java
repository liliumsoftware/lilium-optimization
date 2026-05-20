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

import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.cluster.AbstractKMedoidsAlgorithm;
import com.github.liliumsoftware.optimization.insertion.AbstractInsertionAlgorithm;

import java.util.ArrayList;
import java.util.List;

/**
 * Concrete k-medoids seeding. Each seed plan is an open {@code ORIGIN -> medoid} path named after its medoid,
 * so the cluster a plan represents is visible in its code.
 */
public final class TestKMedoids extends AbstractKMedoidsAlgorithm<TestPlan, TestNode> {

    public TestKMedoids(PathCostCalculation<TestPlan, TestNode, ?> costCalculation,
            TrackableAssignment<TestPlan, ?> trackableAssignment,
            AbstractInsertionAlgorithm<TestPlan, TestNode, ?> insertionAlgorithm) {
        super(costCalculation, trackableAssignment, insertionAlgorithm);
    }

    @Override
    protected TestPlan getNewPlan(TestNode origin, TestNode customer) {
        List<TestNode> nodes = new ArrayList<>();
        nodes.add(origin);
        nodes.add(customer);
        return new TestPlan("km-" + customer.getCode(), nodes, customer.getZone());
    }

}
