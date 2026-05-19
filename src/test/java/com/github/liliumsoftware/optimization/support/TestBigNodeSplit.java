package com.github.liliumsoftware.optimization.support;

import com.github.liliumsoftware.optimization.split.AbstractBigNodeSplitAlgorithm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Concrete "split against the biggest resource" algorithm: a node that fits no trackable at all is carved
 * against the largest still-unused one, and that chunk gets its own dedicated single-node plan (consuming the
 * resource), leaving the remainder to be planned normally.
 */
public final class TestBigNodeSplit extends AbstractBigNodeSplitAlgorithm<TestPlan, TestNode, TestTrackable> {

    private final TestNode origin;

    public TestBigNodeSplit(List<TestTrackable> trackables, TestNode origin) {
        super(trackables);
        this.origin = origin;
    }

    @Override
    protected Comparator<TestTrackable> comparator() {
        return Comparator.comparingInt(TestTrackable::getCapacity).thenComparing(TestTrackable::getCode);
    }

    /**
     * "This resource can serve the node as-is", i.e. no splitting needed for it.
     */
    @Override
    protected boolean matchBiggestTrackable(TestTrackable trackable, TestNode node) {
        return node.getDemand() <= trackable.getCapacity();
    }

    @Override
    protected TestNode createRemainingNode(TestNode node, TestTrackable trackable, TestNode newNode) {
        int carved = trackable.getCapacity();
        newNode.setDemand(carved);
        return new TestNode(node.getCode() + "'", node.getType(), node.getZone(),
                node.getX(), node.getY(), node.getDemand() - carved);
    }

    @Override
    protected TestPlan getNewPlan(TestNode node, TestTrackable trackable) {
        List<TestNode> nodes = new ArrayList<>();
        nodes.add(origin);
        nodes.add(node);
        TestPlan plan = new TestPlan("big-" + node.getCode(), nodes, node.getZone());
        plan.setTrackable(trackable);
        return plan;
    }

}
