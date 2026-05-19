package com.github.liliumsoftware.optimization.support;

import com.github.liliumsoftware.optimization.split.AbstractSplitAlgorithm;

import java.util.Comparator;
import java.util.List;

/**
 * Concrete "split against the smallest resource" algorithm, in demand units: a node whose demand exceeds the
 * smallest trackable's capacity is carved into a piece exactly filling that capacity plus a remainder node
 * carrying the rest.
 *
 * <p>The carved piece keeps the original code (it is the node that stays in place) while the remainder gets a
 * derived {@code code+"'"} so codes stay unique across repeated carving — a run must terminate because each
 * remainder is strictly smaller than the node it came from.
 */
public final class TestSplit extends AbstractSplitAlgorithm<TestPlan, TestNode, TestTrackable> {

    public TestSplit(List<TestTrackable> trackables) {
        super(trackables);
    }

    @Override
    protected Comparator<TestTrackable> comparator() {
        return Comparator.comparingInt(TestTrackable::getCapacity).thenComparing(TestTrackable::getCode);
    }

    @Override
    protected boolean matchSmallestTrackable(TestTrackable trackable, TestNode node) {
        return node.getDemand() > trackable.getCapacity();
    }

    @Override
    protected boolean isShrinking(TestNode node, TestNode remainingNode) {
        return remainingNode.getDemand() < node.getDemand();
    }

    @Override
    protected TestNode createRemainingNode(TestNode node, TestTrackable trackable, TestNode newNode) {
        int carved = trackable.getCapacity();
        newNode.setDemand(carved);
        return new TestNode(node.getCode() + "'", node.getType(), node.getZone(),
                node.getX(), node.getY(), node.getDemand() - carved);
    }

}
