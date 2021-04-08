package ir.baho.algorithm.support;

import ir.baho.algorithm.assignment.TrackableAssignment;
import ir.baho.algorithm.calculation.PathCostCalculation;
import ir.baho.algorithm.cluster.AbstractKMedoidsAlgorithm;
import ir.baho.algorithm.insertion.AbstractInsertionAlgorithm;

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
