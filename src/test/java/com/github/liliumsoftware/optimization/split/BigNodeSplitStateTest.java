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

package com.github.liliumsoftware.optimization.split;

import com.github.liliumsoftware.optimization.support.TestBigNodeSplit;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the state an {@link AbstractBigNodeSplitAlgorithm} keeps across {@code split} calls: a unit carved against is
 * consumed for good, a call publishes its plans only once it completes, and a refused or plan-less carve behaves as
 * documented.
 */
class BigNodeSplitStateTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);

    private static List<Integer> demands(List<TestNode> nodes) {
        return nodes.stream().map(TestNode::getDemand).toList();
    }

    private static List<String> codes(List<TestNode> nodes) {
        return nodes.stream().map(TestNode::getCode).toList();
    }

    @Test
    void aUnitConsumedByOneCallIsNotHandedOutByALaterCall() {
        TestBigNodeSplit split = new TestBigNodeSplit(List.of(new TestTrackable("only", 6)), origin);

        List<TestNode> first = split.split(new ArrayList<>(List.of(TestNode.customer("A", 0, 10, 20))));
        // 20 fits nothing: 6 is carved for "only", the remaining 14 waits for normal planning
        assertThat(demands(first)).containsExactly(6, 14);

        List<TestNode> second = split.split(new ArrayList<>(List.of(TestNode.customer("B", 0, 20, 20))));

        assertThat(demands(second)).as("no unit left to carve against").containsExactly(20);
        assertThat(split.getSingleNodePlans()).extracting(TestPlan::getCode).containsExactly("big-A");
        assertThat(split.getSingleNodePlans().getFirst().getTrackable()).hasToString("only#0");
    }

    @Test
    void aLaterCallCarvesAgainstTheBiggestUnitStillUnconsumed() {
        // A (10) is carved 6 + 4 against large in the first call (medium carries the 4 whole, so it stays free); the
        // second call carves B (20) against medium, not large again: 4 + 16.
        TestBigNodeSplit split = new TestBigNodeSplit(List.of(new TestTrackable("medium", 4),
                new TestTrackable("large", 6)), origin);

        assertThat(demands(split.split(new ArrayList<>(List.of(TestNode.customer("A", 0, 10, 10))))))
                .containsExactly(6, 4);
        List<TestNode> second = split.split(new ArrayList<>(List.of(TestNode.customer("B", 0, 20, 20))));

        assertThat(demands(second)).containsExactly(4, 16);
        assertThat(split.getSingleNodePlans()).extracting(p -> p.getCode() + "=" + p.getTrackable())
                .containsExactly("big-A=large#0", "big-B=medium#0");
    }

    @Test
    void aCallThatThrowsRecordsNoPlanAndConsumesNoUnit() {
        // A is carved against big2 (the biggest by capacity, then code); B's remainder keeps B's code and the call
        // throws. Neither A's plan nor big2 is booked, so the next call carves C against big2 again.
        AbstractBigNodeSplitAlgorithm<TestPlan, TestNode, TestTrackable> split = new AbstractBigNodeSplitAlgorithm<>(
                List.of(new TestTrackable("big1", 6), new TestTrackable("big2", 6))) {
            @Override
            protected TestNode createRemainingNode(TestNode node, TestTrackable trackable, TestNode newNode) {
                newNode.setDemand(trackable.getCapacity());
                String code = node.getCode().equals("B") ? "B" : node.getCode() + "'";
                return new TestNode(code, node.getType(), node.getZone(), node.getX(), node.getY(),
                        node.getDemand() - trackable.getCapacity());
            }

            @Override
            protected boolean matchBiggestTrackable(TestTrackable trackable, TestNode node) {
                return node.getDemand() <= trackable.getCapacity();
            }

            @Override
            protected TestPlan getNewPlan(TestNode node, TestTrackable trackable) {
                TestPlan plan = new TestPlan("big-" + node.getCode(), List.of(origin, node));
                plan.setTrackable(trackable);
                return plan;
            }

            @Override
            protected Comparator<TestTrackable> comparator() {
                return Comparator.comparingInt(TestTrackable::getCapacity).thenComparing(TestTrackable::getCode);
            }
        };
        List<TestNode> failing = new ArrayList<>(List.of(TestNode.customer("A", 0, 10, 20),
                TestNode.customer("B", 0, 20, 20)));

        assertThatThrownBy(() -> split.split(failing)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("B");

        assertThat(split.getSingleNodePlans()).isEmpty();
        List<TestNode> next = split.split(new ArrayList<>(List.of(TestNode.customer("C", 0, 30, 10))));
        // 10 = 6 carved for big2 + 4, which big1 carries whole
        assertThat(demands(next)).containsExactly(6, 4);
        assertThat(split.getSingleNodePlans()).extracting(p -> p.getCode() + "=" + p.getTrackable())
                .containsExactly("big-C=big2#0");
    }

    @Test
    void aNodeTheBiggestUnitDoesNotSupportIsLeftWholeAndTheUnitStaysFree() {
        // "only" refuses B; B stays as it is and the unit goes to C, the next oversized node.
        AbstractBigNodeSplitAlgorithm<TestPlan, TestNode, TestTrackable> split = refusing(Set.of("B"),
                List.of(new TestTrackable("only", 6)));
        List<TestNode> nodes = new ArrayList<>(List.of(TestNode.customer("B", 0, 10, 20),
                TestNode.customer("C", 0, 20, 20)));

        split.split(nodes);

        assertThat(codes(nodes)).containsExactly("B", "C", "C'");
        assertThat(demands(nodes)).containsExactly(20, 6, 14);
        assertThat(split.getSingleNodePlans()).extracting(p -> p.getCode() + "=" + p.getTrackable())
                .containsExactly("big-C=only#0");
    }

    @Test
    void aCarveWithoutAPlanStillSplitsTheNodeButRecordsNothing() {
        AbstractBigNodeSplitAlgorithm<TestPlan, TestNode, TestTrackable> split = new AbstractBigNodeSplitAlgorithm<>(
                List.of(new TestTrackable("only", 6))) {
            @Override
            protected TestNode createRemainingNode(TestNode node, TestTrackable trackable, TestNode newNode) {
                newNode.setDemand(trackable.getCapacity());
                return new TestNode(node.getCode() + "'", node.getType(), node.getZone(), node.getX(), node.getY(),
                        node.getDemand() - trackable.getCapacity());
            }

            @Override
            protected boolean matchBiggestTrackable(TestTrackable trackable, TestNode node) {
                return node.getDemand() <= trackable.getCapacity();
            }

            @Override
            protected TestPlan getNewPlan(TestNode node, TestTrackable trackable) {
                return null;
            }

            @Override
            protected Comparator<TestTrackable> comparator() {
                return Comparator.comparingInt(TestTrackable::getCapacity);
            }
        };
        List<TestNode> nodes = new ArrayList<>(List.of(TestNode.customer("A", 0, 10, 20)));

        split.split(nodes);

        assertThat(codes(nodes)).containsExactly("A", "A'");
        assertThat(demands(nodes)).containsExactly(6, 14);
        assertThat(split.getSingleNodePlans()).isEmpty();
    }

    @Test
    void theSingleNodePlansAreAnUnmodifiableSnapshot() {
        TestBigNodeSplit split = new TestBigNodeSplit(List.of(new TestTrackable("u1", 6), new TestTrackable("u2", 6)),
                origin);
        split.split(new ArrayList<>(List.of(TestNode.customer("A", 0, 10, 10))));

        List<TestPlan> snapshot = split.getSingleNodePlans();
        split.split(new ArrayList<>(List.of(TestNode.customer("B", 0, 20, 10))));

        assertThat(snapshot).extracting(TestPlan::getCode).containsExactly("big-A");
        assertThat(split.getSingleNodePlans()).extracting(TestPlan::getCode).containsExactly("big-A", "big-B");
        assertThatThrownBy(() -> snapshot.add(snapshot.getFirst())).isInstanceOf(UnsupportedOperationException.class);
    }

    private AbstractBigNodeSplitAlgorithm<TestPlan, TestNode, TestTrackable> refusing(Set<String> refused,
            List<TestTrackable> fleet) {
        return new AbstractBigNodeSplitAlgorithm<>(fleet) {
            @Override
            protected boolean supports(TestTrackable trackable, TestNode node) {
                return !refused.contains(node.getCode());
            }

            @Override
            protected TestNode createRemainingNode(TestNode node, TestTrackable trackable, TestNode newNode) {
                newNode.setDemand(trackable.getCapacity());
                return new TestNode(node.getCode() + "'", node.getType(), node.getZone(), node.getX(), node.getY(),
                        node.getDemand() - trackable.getCapacity());
            }

            @Override
            protected boolean matchBiggestTrackable(TestTrackable trackable, TestNode node) {
                return node.getDemand() <= trackable.getCapacity();
            }

            @Override
            protected TestPlan getNewPlan(TestNode node, TestTrackable trackable) {
                TestPlan plan = new TestPlan("big-" + node.getCode(), List.of(origin, node));
                plan.setTrackable(trackable);
                return plan;
            }

            @Override
            protected Comparator<TestTrackable> comparator() {
                return Comparator.comparingInt(TestTrackable::getCapacity);
            }
        };
    }

}
