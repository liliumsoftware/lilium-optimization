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
