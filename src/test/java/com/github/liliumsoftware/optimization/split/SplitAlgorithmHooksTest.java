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

import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Pins the default hooks of {@link AbstractSplitAlgorithm}: the resource to split against is the first by code unless
 * {@code comparator()} is overridden, {@code supports} can exempt a node, and one node may be carved into at most
 * 100 000 pieces.
 */
class SplitAlgorithmHooksTest {

    private static List<Integer> demands(List<TestNode> nodes) {
        return nodes.stream().map(TestNode::getDemand).toList();
    }

    /**
     * Carves demand against the chosen resource's capacity; the remainder is named after the demand it still carries,
     * so codes stay unique and short however many pieces there are. No comparator or isShrinking override.
     */
    private static AbstractSplitAlgorithm<TestPlan, TestNode, TestTrackable> carver(List<TestTrackable> trackables,
            Set<String> unsupported) {
        return new AbstractSplitAlgorithm<>(trackables) {
            @Override
            protected boolean supports(TestTrackable trackable, TestNode node) {
                return !unsupported.contains(node.getCode());
            }

            @Override
            protected TestNode createRemainingNode(TestNode node, TestTrackable trackable, TestNode newNode) {
                newNode.setDemand(trackable.getCapacity());
                int rest = node.getDemand() - trackable.getCapacity();
                return new TestNode("rest-" + rest, node.getType(), node.getZone(), node.getX(), node.getY(), rest);
            }

            @Override
            protected boolean matchSmallestTrackable(TestTrackable trackable, TestNode node) {
                return node.getDemand() > trackable.getCapacity();
            }
        };
    }

    @Test
    void byDefaultTheResourceFirstByCodeIsSplitAgainst() {
        // "a" (5) sorts before "b" (2) by code, so 7 becomes 5 + 2; ordering by capacity would give 2 + 2 + 2 + 1.
        List<TestNode> nodes = new ArrayList<>(List.of(TestNode.customer("N", 0, 10, 7)));

        carver(List.of(new TestTrackable("b", 2), new TestTrackable("a", 5)), Set.of()).split(nodes);

        assertThat(demands(nodes)).containsExactly(5, 2);
    }

    @Test
    void aNodeTheResourceDoesNotSupportIsLeftWhole() {
        List<TestNode> nodes = new ArrayList<>(List.of(TestNode.customer("skip", 0, 10, 7),
                TestNode.customer("N", 0, 20, 7)));

        carver(List.of(new TestTrackable("a", 3)), Set.of("skip")).split(nodes);

        // N: 7 = 3 + 3 + 1, the two remainders appended in order
        assertThat(nodes).extracting(TestNode::getCode).containsExactly("skip", "N", "rest-4", "rest-1");
        assertThat(demands(nodes)).containsExactly(7, 3, 3, 1);
    }

    @Test
    void aNodeMayBeCarvedIntoExactlyOneHundredThousandPieces() {
        // Demand 100 000 against capacity 1 yields 100 000 pieces of 1: the cap is inclusive.
        List<TestNode> nodes = new ArrayList<>(List.of(TestNode.customer("N", 0, 10, 100_000)));

        assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> carver(List.of(new TestTrackable("one", 1)), Set.of()).split(nodes));

        assertThat(nodes).hasSize(100_000).allSatisfy(node -> assertThat(node.getDemand()).isEqualTo(1));
    }

    @Test
    void oneMorePieceThanTheCapIsRefused() {
        List<TestNode> nodes = new ArrayList<>(List.of(TestNode.customer("N", 0, 10, 100_001)));
        AbstractSplitAlgorithm<TestPlan, TestNode, TestTrackable> split = carver(List.of(new TestTrackable("one", 1)),
                Set.of());

        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> assertThatThrownBy(() -> split.split(nodes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("100000"));
    }

}
