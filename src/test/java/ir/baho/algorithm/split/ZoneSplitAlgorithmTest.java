package ir.baho.algorithm.split;

import ir.baho.algorithm.OptimizationType;
import ir.baho.algorithm.support.MatrixCostCalculation;
import ir.baho.algorithm.support.TestNode;
import ir.baho.algorithm.support.TestPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how plans are partitioned into independent, non-interacting zone groups for parallel optimization.
 */
class ZoneSplitAlgorithmTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestPlan z1 = new TestPlan("p1", List.of(origin, TestNode.customer("A", "Z1", 0, 1)));
    private final TestPlan z2 = new TestPlan("p2", List.of(origin, TestNode.customer("B", "Z2", 0, 2)));
    private final TestPlan z3 = new TestPlan("p3", List.of(origin, TestNode.customer("C", "Z3", 0, 3)));

    @Test
    void noRestrictionCollapsesEverythingIntoOneGroup() {
        MatrixCostCalculation calc = new MatrixCostCalculation();   // NO_RESTRICTION => every zone is a neighbour
        List<List<TestPlan>> groups = new ZoneSplitAlgorithm<>(calc, 10).groups(List.of(z1, z2, z3));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0)).containsExactly(z1, z2, z3);   // input order within the group
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
        assertThat(zoneSplit.split(List.of(z1.getCustomers().get(0), z2.getCustomers().get(0)))).hasSize(1);
        assertThat(zoneSplit.split(List.of(z2.getCustomers().get(0), z1.getCustomers().get(0)))).hasSize(1);
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
        assertThat(groups.get(0)).containsExactlyInAnyOrder(z1, z2, z3);
    }

}
