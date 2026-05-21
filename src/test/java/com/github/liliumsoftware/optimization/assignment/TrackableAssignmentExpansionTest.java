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
