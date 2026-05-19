package com.github.liliumsoftware.optimization.assignment;

import com.github.liliumsoftware.optimization.support.TestAssignment;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the constructor's expansion of each resource's {@code getCount()} into that many indexed units.
 */
class TrackableAssignmentExpansionTest {

    @Test
    void multiUnitTrackablesAreExpandedIntoIndexedCopies() {
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("A", 10, 3, false),
                new TestTrackable("B", 5, 2, false)));

        List<TestTrackable> units = assignment.getTrackables();
        assertThat(units).hasSize(5);
        assertThat(units.stream().filter(t -> t.getCode().equals("A")).map(TestTrackable::getIndex))
                .containsExactlyInAnyOrder(0, 1, 2);
        assertThat(units.stream().filter(t -> t.getCode().equals("B")).map(TestTrackable::getIndex))
                .containsExactlyInAnyOrder(0, 1);
    }

    @Test
    void singleUnitTrackableStaysSingle() {
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("solo", 10)));
        assertThat(assignment.getTrackables()).hasSize(1);
    }

    @Test
    void assignRespectsCapacity() {
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("small", 1)));
        TestPlan twoCustomers = new TestPlan("p", List.of(
                com.github.liliumsoftware.optimization.support.TestNode.origin("O", 0, 0),
                com.github.liliumsoftware.optimization.support.TestNode.customer("A", 0, 1),
                com.github.liliumsoftware.optimization.support.TestNode.customer("B", 0, 2)));
        assertThat(assignment.assign(List.of(twoCustomers))).isFalse();   // capacity 1 < 2 customers
    }

}
