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

package com.github.liliumsoftware.optimization.assignment;

import com.github.liliumsoftware.optimization.support.TestAlternativeFinder;
import com.github.liliumsoftware.optimization.support.TestAssignment;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the constructor's expansion of each resource's {@code getCount()} into that many indexed units, and how the
 * expanded pool looks to an alternative finder.
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
                TestNode.origin("O", 0, 0),
                TestNode.customer("A", 0, 1),
                TestNode.customer("B", 0, 2)));
        assertThat(assignment.assign(List.of(twoCustomers))).isFalse();   // capacity 1 < 2 customers
    }

    @Test
    void aResourceWithCountThreeBecomesUnitsZeroToTwoInFleetOrder() {
        TestTrackable many = new TestTrackable("A", 10, 3, false);
        TestAssignment assignment = new TestAssignment(List.of(many, new TestTrackable("B", 5)));

        assertThat(assignment.getTrackables()).extracting(TestTrackable::toString)
                .containsExactly("A#0", "A#1", "A#2", "B#0");
        assertThat(many.getIndex()).as("the units are copies; the caller's resource is not renumbered").isZero();
        assertThat(assignment.getTrackables().getFirst()).isNotSameAs(many);
        assertThatThrownBy(() -> assignment.getTrackables().add(many))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aResourceWithCountZeroContributesNoUnit() {
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("gone", 10, 0, false),
                new TestTrackable("B", 5)));

        assertThat(assignment.getTrackables()).extracting(TestTrackable::toString).containsExactly("B#0");
        TestPlan first = new TestPlan("p", List.of(TestNode.origin("O", 0, 0), TestNode.customer("A", 0, 1)));
        TestPlan second = new TestPlan("q", List.of(TestNode.origin("O", 0, 0), TestNode.customer("C", 0, 2)));
        assertThat(assignment.assign(List.of(first, second))).as("one unit for two plans").isFalse();
        assertThat(first.getTrackable()).hasToString("B#0");
    }

    @Test
    void anAlternativeFinderOverTheExpandedPoolListsEveryOtherCodeOnce() {
        // A and B stand for two units each, C for one of another capacity. The siblings of the asked-for unit share
        // its code and are excluded; B is accepted for both its units but listed once.
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("A", 5, 2, false),
                new TestTrackable("B", 5, 2, false), new TestTrackable("C", 9)));
        TestAlternativeFinder finder = new TestAlternativeFinder(assignment.getTrackables());

        assertThat(finder.getAlternatives(assignment.getTrackables().get(1))).containsExactly("B");
        assertThat(finder.getAlternatives(assignment.getTrackables().get(2))).containsExactly("A");
        assertThat(finder.getAlternatives(assignment.getTrackables().getLast())).isEmpty();
    }

    @Test
    void anAlternativeFinderListsDistinctCodesInFleetOrderOfTheirFirstUnit() {
        // Expanded fleet C#0 C#1 A#0 A#1 A#2 D#0 B#0 B#1, all of capacity 5 except D. Asked for a unit of a fourth
        // code E, the finder lists C, A, B once each, in the order their first unit appears.
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("C", 5, 2, false),
                new TestTrackable("A", 5, 3, false), new TestTrackable("D", 7),
                new TestTrackable("B", 5, 2, false)));
        TestAlternativeFinder finder = new TestAlternativeFinder(assignment.getTrackables());

        assertThat(finder.getAlternatives(new TestTrackable("E", 5))).containsExactly("C", "A", "B");
    }

}
