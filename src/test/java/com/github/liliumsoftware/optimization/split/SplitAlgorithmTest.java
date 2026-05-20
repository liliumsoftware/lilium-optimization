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

import com.github.liliumsoftware.optimization.OptimizationType;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.TestBigNodeSplit;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestSplit;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The split algorithms are pre-planning transformations that carve a node too large for any single resource
 * into pieces that fit. The invariant that matters is conservation: the carved pieces must add up to exactly
 * the original demand, or the plan silently under- or over-delivers.
 *
 * <p>Both walk the node list while appending remainders to its end, which is what lets a single pass keep
 * carving until everything fits — and is also why a non-shrinking remainder would loop forever. The examples
 * below use demand 10 against a smallest resource of 3, which must produce 3 + 3 + 3 + 1.
 */
class SplitAlgorithmTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);

    /**
     * A {@link TestSplit}-like carver that cannot tell whether a remainder shrank (no size check), optionally giving
     * the remainder the same code as the node it came from.
     */
    private static AbstractSplitAlgorithm<TestPlan, TestNode, TestTrackable> sizeBlindSplit(
            List<TestTrackable> trackables, boolean sameCode) {
        return new AbstractSplitAlgorithm<>(trackables) {
            @Override
            protected Comparator<TestTrackable> comparator() {
                return Comparator.comparingInt(TestTrackable::getCapacity);
            }

            @Override
            protected boolean matchSmallestTrackable(TestTrackable trackable, TestNode node) {
                return node.getDemand() > trackable.getCapacity();
            }

            @Override
            protected TestNode createRemainingNode(TestNode node, TestTrackable trackable, TestNode newNode) {
                int carved = trackable.getCapacity();
                newNode.setDemand(carved);
                TestNode remainder = new TestNode(sameCode ? node.getCode() : node.getCode() + "'", node.getType(),
                        node.getZone(), node.getX(), node.getY(), node.getDemand() - carved);
                return sameCode ? remainder.withCodeEquality() : remainder;
            }
        };
    }

    // ---------------------------------------------------------------- split against the smallest resource

    private List<Integer> demands(List<TestNode> nodes) {
        return nodes.stream().map(TestNode::getDemand).toList();
    }

    @Test
    void carvesAnOversizedNodeIntoPiecesTheSmallestResourceCanServe() {
        List<TestNode> nodes = new ArrayList<>(List.of(TestNode.customer("A", 0, 10, 10)));
        TestSplit split = new TestSplit(List.of(new TestTrackable("big", 5), new TestTrackable("small", 3)));

        List<TestNode> result = split.split(nodes);

        assertThat(demands(result)).containsExactly(3, 3, 3, 1);
        assertThat(demands(result).stream().mapToInt(Integer::intValue).sum())
                .as("demand is conserved").isEqualTo(10);
        assertThat(result).extracting(TestNode::getCode).containsExactly("A", "A'", "A''", "A'''");
        assertThat(result).extracting(TestNode::getCode).doesNotHaveDuplicates();
    }

    @Test
    void keepsTheCarvedPieceInPlaceAndAppendsTheRemainder() {
        List<TestNode> nodes = new ArrayList<>(List.of(
                TestNode.customer("first", 0, 10, 4),
                TestNode.customer("tail", 0, 20, 1)));
        TestSplit split = new TestSplit(List.of(new TestTrackable("small", 3)));

        List<TestNode> result = split.split(nodes);

        // "first" is carved down to 3 where it sat; its remainder of 1 goes to the end, past "tail".
        assertThat(result).extracting(TestNode::getCode).containsExactly("first", "tail", "first'");
        assertThat(demands(result)).containsExactly(3, 1, 1);
    }

    @Test
    void nodesThatAlreadyFitAreLeftUntouched() {
        List<TestNode> nodes = new ArrayList<>(List.of(
                TestNode.customer("A", 0, 10, 3), TestNode.customer("B", 0, 20, 1)));
        List<TestNode> snapshot = List.copyOf(nodes);
        TestSplit split = new TestSplit(List.of(new TestTrackable("small", 3)));

        assertThat(split.split(nodes)).isEqualTo(snapshot);
    }

    @Test
    void withoutAnyResourceNothingCanBeSplit() {
        List<TestNode> nodes = new ArrayList<>(List.of(TestNode.customer("A", 0, 10, 100)));
        List<TestNode> snapshot = List.copyOf(nodes);

        assertThat(new TestSplit(List.of()).split(nodes)).isEqualTo(snapshot);
    }

    @Test
    void theSameListInstanceIsReturnedMutatedInPlace() {
        List<TestNode> nodes = new ArrayList<>(List.of(TestNode.customer("A", 0, 10, 7)));
        TestSplit split = new TestSplit(List.of(new TestTrackable("small", 3)));

        // Documented behaviour: this is an in-place rewrite, not a pure function.
        assertThat(split.split(nodes)).isSameAs(nodes);
        assertThat(demands(nodes)).containsExactly(3, 3, 1);
    }

    @Test
    void severalOversizedNodesAreAllCarvedDown() {
        List<TestNode> nodes = new ArrayList<>(List.of(
                TestNode.customer("A", 0, 10, 5), TestNode.customer("B", 0, 20, 4)));
        TestSplit split = new TestSplit(List.of(new TestTrackable("small", 2)));

        List<TestNode> result = split.split(nodes);

        assertThat(result).allSatisfy(node -> assertThat(node.getDemand()).isLessThanOrEqualTo(2));
        assertThat(demands(result).stream().mapToInt(Integer::intValue).sum()).isEqualTo(9);
    }

    @Test
    void aSmallestResourceThatCanCarryNothingLeavesTheNodeUnsplit() {
        // The zero-capacity resource sorts first; every carve left a remainder as big as the node, which was appended
        // and carved again until the heap ran out.
        List<TestNode> nodes = new ArrayList<>(List.of(TestNode.customer("A", 0, 10, 25)));
        TestSplit split = new TestSplit(List.of(new TestTrackable("Z", 0), new TestTrackable("T", 10)));

        List<TestNode> result = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> split.split(nodes));

        assertThat(demands(result)).containsExactly(25);
        assertThat(result.getFirst().getCode()).isEqualTo("A");
    }

    @Test
    void aRunawaySplitFailsWithAClearErrorWhenTheRemainderCannotBeMeasured() {
        // Without an isShrinking override the library cannot see that the remainder never shrinks; it must still
        // stop rather than run out of memory.
        List<TestNode> nodes = new ArrayList<>(List.of(TestNode.customer("A", 0, 10, 25)));
        AbstractSplitAlgorithm<TestPlan, TestNode, TestTrackable> split = sizeBlindSplit(List.of(new TestTrackable("Z",
                0)), false);

        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> assertThatThrownBy(() -> split.split(nodes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("A")
                .hasMessageContaining("Z"));
    }

    @Test
    void aRemainderThatKeepsItsNodesCodeIsRejectedAtTheSplit() {
        // The carved piece keeps the node's code, so a remainder with the same code gives two customers one code —
        // which the cost matrix and every construction entry point reject much later, far from the cause. (It also
        // made removing "the node" by equals take out the wrong piece.)
        List<TestNode> nodes = new ArrayList<>(List.of(TestNode.customer("A", 0, 10, 25).withCodeEquality()));

        assertThatThrownBy(() -> sizeBlindSplit(List.of(new TestTrackable("T", 10)), true).split(nodes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("A")
                .hasMessageContaining("code");
        AbstractBigNodeSplitAlgorithm<TestPlan, TestNode, TestTrackable> sameCode = new AbstractBigNodeSplitAlgorithm<>(
                List.of(new TestTrackable("big", 6))) {
            @Override
            protected TestNode createRemainingNode(TestNode node, TestTrackable trackable, TestNode newNode) {
                newNode.setDemand(trackable.getCapacity());
                return new TestNode(node.getCode(), node.getType(), node.getZone(), node.getX(), node.getY(),
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
        assertThatThrownBy(() -> sameCode.split(new ArrayList<>(List.of(TestNode.customer("B", 0, 10, 10)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("B");
    }

    @Test
    void anEmptyNodeListIsHandled() {
        assertThat(new TestSplit(List.of(new TestTrackable("small", 3))).split(new ArrayList<>())).isEmpty();
    }

    // ---------------------------------------------------------------- split against the biggest resource

    @Test
    void carvesAgainstTheBiggestResourceAndEmitsADedicatedPlan() {
        List<TestNode> nodes = new ArrayList<>(List.of(TestNode.customer("A", 0, 10, 10)));
        TestBigNodeSplit split = new TestBigNodeSplit(
                List.of(new TestTrackable("medium", 4), new TestTrackable("large", 6)), origin);

        List<TestNode> result = split.split(nodes);

        // 10 fits nothing, so 6 is peeled off for the large vehicle; the remaining 4 fits the medium one and is
        // left for normal planning.
        assertThat(demands(result)).containsExactly(6, 4);
        assertThat(split.getSingleNodePlans()).hasSize(1);
        assertThat(split.getSingleNodePlans().getFirst().getCustomers())
                .extracting(TestNode::getDemand).containsExactly(6);
        assertThat(split.getSingleNodePlans().getFirst().getTrackable().getCode()).isEqualTo("large");
        assertThat(split.getSingleNodePlans().getFirst().getNodes().getFirst()).isEqualTo(origin);
    }

    @Test
    void nodesServableAsTheyAreProduceNoPlans() {
        List<TestNode> nodes = new ArrayList<>(List.of(TestNode.customer("A", 0, 10, 4)));
        List<TestNode> snapshot = List.copyOf(nodes);
        TestBigNodeSplit split = new TestBigNodeSplit(List.of(new TestTrackable("large", 6)), origin);

        assertThat(split.split(nodes)).isEqualTo(snapshot);
        assertThat(split.getSingleNodePlans()).isEmpty();
    }

    @Test
    void eachResourceIsConsumedByAtMostOneCarvedPiece() {
        List<TestNode> nodes = new ArrayList<>(List.of(
                TestNode.customer("A", 0, 10, 20), TestNode.customer("B", 0, 20, 20)));
        TestBigNodeSplit split = new TestBigNodeSplit(List.of(new TestTrackable("only", 6)), origin);

        List<TestNode> result = split.split(nodes);

        assertThat(split.getSingleNodePlans()).as("one resource means one dedicated plan").hasSize(1);
        assertThat(demands(result).stream().mapToInt(Integer::intValue).sum())
                .as("demand is conserved even when resources run out").isEqualTo(40);
    }

    @Test
    void withoutAnyResourceNoBigNodeIsSplit() {
        List<TestNode> nodes = new ArrayList<>(List.of(TestNode.customer("A", 0, 10, 100)));
        List<TestNode> snapshot = List.copyOf(nodes);
        TestBigNodeSplit split = new TestBigNodeSplit(List.of(), origin);

        assertThat(split.split(nodes)).isEqualTo(snapshot);
        assertThat(split.getSingleNodePlans()).isEmpty();
    }

    // ---------------------------------------------------------------- zone partitioning of nodes

    @Test
    void zoneSplitPutsEveryNodeInExactlyOneGroup() {
        MatrixCostCalculation calculation = new MatrixCostCalculation(OptimizationType.LOCATION_ZONE,
                Map.of("Z1", List.of("Z1", "Z2"), "Z2", List.of("Z2", "Z1"), "Z3", List.of("Z3")));
        List<TestNode> nodes = List.of(
                TestNode.customer("A", "Z1", 0, 10, 1),
                TestNode.customer("B", "Z2", 0, 20, 1),
                TestNode.customer("C", "Z3", 500, 500, 1));

        List<List<TestNode>> groups = new ZoneSplitAlgorithm<>(calculation, 10).split(new ArrayList<>(nodes));

        assertThat(groups).hasSize(2);
        assertThat(groups.stream().flatMap(List::stream)).containsExactlyInAnyOrderElementsOf(nodes);
        assertThat(groups).anySatisfy(group -> assertThat(group).extracting(TestNode::getCode)
                .containsExactlyInAnyOrder("A", "B"));
        assertThat(groups).anySatisfy(group -> assertThat(group).extracting(TestNode::getCode)
                .containsExactly("C"));
    }

    @Test
    void zoneSplitCollapsesEverythingWhenZonesAreUnrestricted() {
        MatrixCostCalculation calculation = new MatrixCostCalculation();   // NO_RESTRICTION
        List<TestNode> nodes = List.of(
                TestNode.customer("A", "Z1", 0, 10, 1),
                TestNode.customer("B", "Z2", 0, 20, 1),
                TestNode.customer("C", "Z3", 500, 500, 1));

        List<List<TestNode>> groups = new ZoneSplitAlgorithm<>(calculation, 10).split(new ArrayList<>(nodes));

        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst()).containsExactlyInAnyOrderElementsOf(nodes);
    }

    @Test
    void zoneSplitRespectsTheMaximumGroupCount() {
        MatrixCostCalculation calculation = new MatrixCostCalculation(OptimizationType.LOCATION_ZONE,
                Map.of("Z1", List.of("Z1"), "Z2", List.of("Z2"), "Z3", List.of("Z3")));
        List<TestNode> nodes = List.of(
                TestNode.customer("A", "Z1", 0, 10, 1),
                TestNode.customer("B", "Z2", 0, 20, 1),
                TestNode.customer("C", "Z3", 500, 500, 1));

        List<List<TestNode>> groups = new ZoneSplitAlgorithm<>(calculation, 2).split(new ArrayList<>(nodes));

        assertThat(groups).hasSize(2);
        assertThat(groups.stream().flatMap(List::stream))
                .as("capping the group count must not lose a node")
                .containsExactlyInAnyOrderElementsOf(nodes);
    }

    @Test
    void zoneSplitOfAnEmptyNodeListIsEmpty() {
        assertThat(new ZoneSplitAlgorithm<>(new MatrixCostCalculation(), 4).split(new ArrayList<>()))
                .isEmpty();
    }

}
