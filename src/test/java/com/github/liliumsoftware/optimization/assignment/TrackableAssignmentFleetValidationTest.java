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

import com.github.liliumsoftware.optimization.domain.Planable;
import com.github.liliumsoftware.optimization.domain.Trackable;
import com.github.liliumsoftware.optimization.domain.TrackableCategory;
import com.github.liliumsoftware.optimization.support.TestAssignment;
import com.github.liliumsoftware.optimization.support.TestInfiniteAssignment;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestSecondDispatchAssignment;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the fleet validation of the {@link TrackableAssignment} constructor: a negative {@code getCount()} and an
 * expansion holding two equal units are rejected, while a count of {@code 0} stays a legal way to leave a resource out.
 */
class TrackableAssignmentFleetValidationTest {

    @Test
    void aNegativeCountIsRejectedNamingTheResource() {
        List<TestTrackable> fleet = List.of(new TestTrackable("ok", 5), new TestTrackable("broken", 5, -1, false));

        assertThatThrownBy(() -> new TestAssignment(fleet))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("broken")
                .hasMessageContaining("negative count -1");
    }

    @Test
    void everyVariantRejectsANegativeCount() {
        List<TestTrackable> fleet = List.of(new TestTrackable("broken", 5, -3, true));

        assertThatThrownBy(() -> new TestInfiniteAssignment(fleet)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestSecondDispatchAssignment(fleet)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCountOfZeroStillLeavesTheResourceOut() {
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("off", 5, 0, false),
                new TestTrackable("on", 5, 2, false)));

        assertThat(assignment.getTrackables()).extracting(TestTrackable::toString).containsExactly("on#0", "on#1");
    }

    @Test
    void aMultiUnitResourceWhoseSetIndexIsANoOpIsRejected() {
        // The default no-op setIndex leaves every copy at index 0, so the two units of "van" are equal.
        List<Bare> fleet = List.of(new Bare("car", 1), new Bare("van", 2));

        assertThatThrownBy(() -> new BareFleet(fleet))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("van (index 0) and van (index 0)")
                .hasMessageContaining("getIndex, setIndex and equals must tell the units");
    }

    @Test
    void singleUnitResourcesWithTheDefaultIndexHandlingAreAccepted() {
        BareFleet fleet = new BareFleet(List.of(new Bare("car", 1), new Bare("van", 1), new Bare("off", 0)));

        assertThat(fleet.getTrackables()).containsExactly(new Bare("car", 1), new Bare("van", 1));
    }

    @Test
    void twoResourcesExpandingIntoEqualUnitsAreRejected() {
        // Two single-unit resources with one code are one unit to the pool (equals is code and index).
        List<TestTrackable> fleet = List.of(new TestTrackable("A", 5), new TestTrackable("B", 5),
                new TestTrackable("A", 9));

        assertThatThrownBy(() -> new TestAssignment(fleet))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A (index 0) and A (index 0)");
        assertThatThrownBy(() -> new TestInfiniteAssignment(fleet)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void indexedUnitsOfOneCodeNextToAnotherResourceAreAccepted() {
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("A", 5, 2, false),
                new TestTrackable("B", 5, 3, false)));

        assertThat(assignment.getTrackables()).extracting(TestTrackable::toString)
                .containsExactly("A#0", "A#1", "B#0", "B#1", "B#2");
    }

    /**
     * A resource that keeps every {@link Trackable} default for the index: {@code setIndex} does nothing and the
     * record's {@code equals} compares code and count only.
     */
    private record Bare(String code, int count) implements Trackable<Bare> {

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public int getCount() {
            return count;
        }

        @Override
        public Bare copy() {
            return new Bare(code, count);
        }

    }

    /**
     * A node-less plan holding one {@link Bare} resource.
     */
    private record BarePlan(String code, Bare unit) implements Planable<BarePlan, TestNode, Bare> {

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public List<TestNode> getNodes() {
            return List.of();
        }

        @Override
        public Bare getTrackable() {
            return unit;
        }

        @Override
        public long getCost() {
            return 0;
        }

        @Override
        public BarePlan copy() {
            return new BarePlan(code, unit);
        }

    }

    /**
     * Assignment over {@link Bare} resources; only the constructor is exercised.
     */
    private static final class BareFleet extends TrackableAssignment<BarePlan, Bare> {

        private BareFleet(List<Bare> trackables) {
            super(trackables);
        }

        @Override
        protected boolean assign(BarePlan plan, Bare trackable) {
            return false;
        }

        @Override
        protected boolean assign(BarePlan plan, List<Bare> trackables) {
            return false;
        }

        @Override
        protected Comparator<Bare> comparator(BarePlan plan) {
            return Comparator.comparing(Bare::getCode);
        }

        @Override
        protected TrackableCategory getCategory(Bare trackable) {
            return new TrackableCategory() {
            };
        }

    }

}
