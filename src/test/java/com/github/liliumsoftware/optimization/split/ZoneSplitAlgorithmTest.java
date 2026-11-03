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
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins how plans are partitioned into independent, non-interacting zone groups for parallel optimization.
 */
class ZoneSplitAlgorithmTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestPlan z1 = new TestPlan("p1", List.of(origin, TestNode.customer("A", "Z1", 0, 1)));
    private final TestPlan z2 = new TestPlan("p2", List.of(origin, TestNode.customer("B", "Z2", 0, 2)));
    private final TestPlan z3 = new TestPlan("p3", List.of(origin, TestNode.customer("C", "Z3", 0, 3)));

    private static TestPlan plan(String code, String zone, int customers) {
        List<TestNode> nodes = new ArrayList<>(List.of(TestNode.origin("O", 0, 0)));
        for (int i = 0; i < customers; i++) {
            nodes.add(TestNode.customer(code + i, zone, 0, i + 1));
        }
        return new TestPlan(code, nodes);
    }

    private static MatrixCostCalculation isolated(String... zones) {
        Map<String, List<String>> neighbours = new HashMap<>();
        for (String zone : zones) {
            neighbours.put(zone, List.of(zone));
        }
        return new MatrixCostCalculation(OptimizationType.LOCATION_ZONE, neighbours);
    }

    @Test
    void noRestrictionCollapsesEverythingIntoOneGroup() {
        MatrixCostCalculation calc = new MatrixCostCalculation();   // NO_RESTRICTION => every zone is a neighbour
        List<List<TestPlan>> groups = new ZoneSplitAlgorithm<>(calc, 10).groups(List.of(z1, z2, z3));

        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst()).containsExactly(z1, z2, z3);   // input order within the group
    }

    @Test
    void neighbouringZonesMergeWhileIsolatedZonesStaySeparate() {
        MatrixCostCalculation calc = new MatrixCostCalculation(
                OptimizationType.LOCATION_ZONE, Map.of("Z1", List.of("Z2"), "Z2", List.of("Z1")));
        List<List<TestPlan>> groups = new ZoneSplitAlgorithm<>(calc, 10).groups(List.of(z1, z2, z3));

        // Groups come back in the order of their first input plan, each in input order.
        assertThat(groups).containsExactly(List.of(z1, z2), List.of(z3));
        assertThat(new ZoneSplitAlgorithm<>(calc, 10).groups(List.of(z3, z2, z1)))
                .containsExactly(List.of(z3), List.of(z2, z1));
    }

    @Test
    void aOneWayNeighbourRelationGroupsThePlansWhateverTheInputOrder() {
        // Z1 lists Z2 as a neighbour but not the other way round. Moves may cross that boundary (from Z1's side), so
        // the plans interact; asking only isNeighbor(earlier, later) made the grouping depend on the input order.
        MatrixCostCalculation calc = new MatrixCostCalculation(
                OptimizationType.LOCATION_ZONE, Map.of("Z1", List.of("Z1", "Z2"), "Z2", List.of("Z2")));
        ZoneSplitAlgorithm<TestPlan, TestNode> zoneSplit = new ZoneSplitAlgorithm<>(calc, 10);

        assertThat(zoneSplit.groups(List.of(z1, z2))).hasSize(1);
        assertThat(zoneSplit.groups(List.of(z2, z1))).hasSize(1);
        assertThat(zoneSplit.split(List.of(z1.getCustomers().getFirst(), z2.getCustomers().getFirst()))).hasSize(1);
        assertThat(zoneSplit.split(List.of(z2.getCustomers().getFirst(), z1.getCustomers().getFirst()))).hasSize(1);
    }

    @Test
    void aPlanWithoutCustomersJoinsTheGroupOfItsOwnZone() {
        // An empty plan has no customer zones; it used to end up alone in a group of its own, where balancing could
        // never hand it any work. It is placed by its own zone instead (a null zone neighbours every zone).
        MatrixCostCalculation restricted = new MatrixCostCalculation(
                OptimizationType.LOCATION_ZONE, Map.of("Z1", List.of("Z1"), "Z3", List.of("Z3")));
        TestPlan emptyZ1 = new TestPlan("e1", List.of(z1.getOrigin()), "Z1");
        TestPlan emptyNoZone = new TestPlan("e0", List.of(z1.getOrigin()));

        assertThat(new ZoneSplitAlgorithm<>(restricted, 10).groups(List.of(z1, emptyZ1, z3)))
                .containsExactly(List.of(z1, emptyZ1), List.of(z3));
        assertThat(new ZoneSplitAlgorithm<>(new MatrixCostCalculation(), 10).groups(List.of(z1, emptyNoZone, z3)))
                .containsExactly(List.of(z1, emptyNoZone, z3));
    }

    @Test
    void maxSplitCountCapsTheNumberOfGroups() {
        MatrixCostCalculation calc = new MatrixCostCalculation(
                OptimizationType.LOCATION_ZONE, Map.of("Z1", List.of("Z2"), "Z2", List.of("Z1")));
        List<List<TestPlan>> groups = new ZoneSplitAlgorithm<>(calc, 1).groups(List.of(z1, z2, z3));

        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst()).containsExactlyInAnyOrder(z1, z2, z3);
    }

    @Test
    void aMaxSplitCountBelowOneIsRejectedUpFront() {
        // Regression: a non-positive cap used to surface as NoSuchElementException from deep inside group().
        MatrixCostCalculation calc = new MatrixCostCalculation();
        assertThatThrownBy(() -> new ZoneSplitAlgorithm<>(calc, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSplitCount");
        assertThatThrownBy(() -> new ZoneSplitAlgorithm<>(calc, -3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEmptyPlanListYieldsNoGroups() {
        assertThat(new ZoneSplitAlgorithm<>(new MatrixCostCalculation(), 4).groups(List.of())).isEmpty();
        assertThat(new ZoneSplitAlgorithm<>(new MatrixCostCalculation(), 4).split(List.of())).isEmpty();
    }

    @Test
    void surplusGroupsAreFoldedIntoTheCurrentlySmallestKeptGroup() {
        // Four isolated plans, sized by node count (origin included): a 4, b 3, c 2, d 2. A cap of 2 keeps a and b;
        // c goes to the smaller b (3 -> 5), then d to a, now the smaller one (4 < 5). Folding everything into the group
        // that was smallest at the start would have put c and d both into b.
        TestPlan a = plan("a", "Z1", 3);
        TestPlan b = plan("b", "Z2", 2);
        TestPlan c = plan("c", "Z3", 1);
        TestPlan d = plan("d", "Z4", 1);

        List<List<TestPlan>> groups = new ZoneSplitAlgorithm<>(isolated("Z1", "Z2", "Z3", "Z4"), 2)
                .groups(List.of(c, a, d, b));

        // each group in input order, the groups ordered by their first plan: c (position 0) before a (position 1)
        assertThat(groups).containsExactly(List.of(c, b), List.of(a, d));
    }

    @Test
    void theGroupsAreNewMutableListsAlsoWhenCapped() {
        MatrixCostCalculation calc = isolated("Z1", "Z2", "Z3");
        for (int cap : new int[] {1, 2, 10}) {
            List<List<TestPlan>> groups = new ZoneSplitAlgorithm<>(calc, cap).groups(List.of(z1, z2, z3));

            groups.getFirst().add(z1);
            groups.add(new ArrayList<>());
            assertThat(groups).as("cap %d", cap).hasSize(Math.min(cap, 3) + 1);
        }
    }

    @Test
    void nodeSplitFoldsSurplusGroupsIntoTheCurrentlySmallestKeptGroupAndReturnsAMutableList() {
        // Zone sizes 4, 3, 2, 1 nodes. A cap of 2 keeps Z1 (4) and Z2 (3); Z3 goes to Z2 (3 -> 5), then Z4 to Z1
        // (4 < 5).
        List<TestNode> nodes = new ArrayList<>();
        int[] sizes = {4, 3, 2, 1};
        for (int zone = 0; zone < sizes.length; zone++) {
            for (int i = 0; i < sizes[zone]; i++) {
                nodes.add(TestNode.customer("Z" + (zone + 1) + "-" + i, "Z" + (zone + 1), zone, i));
            }
        }

        List<List<TestNode>> groups = new ZoneSplitAlgorithm<>(isolated("Z1", "Z2", "Z3", "Z4"), 2).split(nodes);

        assertThat(groups).extracting(group -> group.stream().map(TestNode::getZone).distinct().sorted().toList())
                .containsExactlyInAnyOrder(List.of("Z1", "Z4"), List.of("Z2", "Z3"));
        assertThat(groups).extracting(List::size).containsExactlyInAnyOrder(5, 5);
        groups.add(new ArrayList<>());
        assertThat(groups).hasSize(3);
    }

    @Test
    void anUncappedNodeSplitIsAMutableListToo() {
        List<List<TestNode>> groups = new ZoneSplitAlgorithm<>(isolated("Z1", "Z2"), 5).split(List.of(
                TestNode.customer("A", "Z1", 0, 1), TestNode.customer("B", "Z2", 0, 2)));

        assertThat(groups).hasSize(2);
        groups.add(new ArrayList<>());
        assertThat(groups).hasSize(3);
    }

}
