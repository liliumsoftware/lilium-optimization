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
