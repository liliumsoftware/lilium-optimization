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

package com.github.liliumsoftware.optimization.clarkewright;

import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PlanMerger} decides <em>where</em> a merged plan lands in the plan list. That position is not
 * cosmetic: the assignment layer walks plans in order and hands out resources greedily, so putting the merged
 * plan where the resource-holding input sat is what keeps an already-assigned resource with its work.
 */
class PlanMergerTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);

    private TestPlan plan(String code, String customerCode) {
        return new TestPlan(code, List.of(origin, TestNode.customer(customerCode, 0, 10, 1)));
    }

    @Test
    void replacesBothSourcePlansWithTheMergeResult() {
        TestPlan first = plan("p1", "A");
        TestPlan second = plan("p2", "B");
        TestPlan other = plan("p3", "C");
        TestPlan merged = new TestPlan("merged", List.of(origin,
                TestNode.customer("A", 0, 10, 1), TestNode.customer("B", 0, 10, 1)));

        List<TestPlan> result = new PlanMerger<>(List.of(first, second, other), first, second, merged).merge();

        assertThat(result).containsExactly(other, merged);
    }

    @Test
    void worksOnCopiesSoTheInputListAndItsPlansAreUntouched() {
        TestPlan first = plan("p1", "A");
        TestPlan second = plan("p2", "B");
        TestPlan other = plan("p3", "C");
        List<TestPlan> input = List.of(first, second, other);
        TestPlan merged = plan("merged", "A");

        List<TestPlan> result = new PlanMerger<>(input, first, second, merged).merge();

        assertThat(input).containsExactly(first, second, other);
        assertThat(result).doesNotContain(first, second);
        assertThat(result.stream().filter(p -> p.getCode().equals("p3")).findFirst().orElseThrow())
                .as("surviving plans are copies, not the caller's instances").isNotSameAs(other);
    }

    @Test
    void insertsTheMergeAtThePositionOfTheResourceHoldingPlan() {
        TestPlan unassigned = plan("unassigned", "A");
        TestPlan assigned = plan("assigned", "B");
        assigned.setTrackable(new TestTrackable("T1", 5));
        TestPlan filler = plan("filler", "C");
        TestPlan merged = plan("merged", "A");

        // The merge is spliced in at the resource holder's index and only then are the two sources removed, so
        // it inherits the holder's place among the survivors — here ahead of the filler rather than appended.
        List<TestPlan> result = new PlanMerger<>(List.of(unassigned, assigned, filler), unassigned, assigned, merged)
                .merge();

        assertThat(result).containsExactly(merged, filler);
    }

    @Test
    void appendsTheMergeWhenNeitherSourceHoldsAResource() {
        TestPlan first = plan("p1", "A");
        TestPlan second = plan("p2", "B");
        TestPlan filler = plan("p3", "C");
        TestPlan merged = plan("merged", "A");

        // Contrast with the resource-holder case: with nothing to keep in place the merge goes to the end.
        List<TestPlan> result = new PlanMerger<>(List.of(first, second, filler), first, second, merged).merge();

        assertThat(result).containsExactly(filler, merged);
    }

    @Test
    void takesTheFirstPlansSlotWhenItIsTheResourceHolder() {
        TestPlan assigned = plan("assigned", "A");
        assigned.setTrackable(new TestTrackable("T1", 5));
        TestPlan unassigned = plan("unassigned", "B");
        TestPlan filler = plan("filler", "C");
        TestPlan merged = plan("merged", "A");

        List<TestPlan> result = new PlanMerger<>(List.of(filler, assigned, unassigned), assigned, unassigned, merged)
                .merge();

        assertThat(result.indexOf(merged)).isEqualTo(1);
    }

    @Test
    void mergingPlansThatAreNotInTheListIsRejected() {
        TestPlan inList = plan("p1", "A");
        TestPlan stranger = plan("stranger", "Z");
        TestPlan merged = plan("merged", "A");

        assertThatThrownBy(() -> new PlanMerger<>(List.of(inList), inList, stranger, merged).merge())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void aMergeResultThatEqualsASourcePlanByCodeIsKept() {
        // Planable#getCode() is documented as the plan's identity, so a consumer may compare plans by code alone.
        // The merge result keeps the first plan's code; removing the stale copies by equals() used to remove the
        // freshly inserted result instead and lose the customers of both sources.
        TestPlan first = plan("p1", "A");
        first.setEquality(TestPlan.Equality.CODE);
        first.setTrackable(new TestTrackable("T1", 5));
        TestPlan second = plan("p2", "B");
        second.setEquality(TestPlan.Equality.CODE);
        TestPlan merged = new TestPlan("p1", List.of(origin,
                TestNode.customer("A", 0, 10, 1), TestNode.customer("B", 0, 10, 1)));
        merged.setEquality(TestPlan.Equality.CODE);

        List<TestPlan> result = new PlanMerger<>(List.of(second, first), first, second, merged).merge();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).isSameAs(merged);
    }

    @Test
    void mergingTheOnlyTwoPlansLeavesJustTheResult() {
        TestPlan first = plan("p1", "A");
        TestPlan second = plan("p2", "B");
        TestPlan merged = plan("merged", "A");

        assertThat(new PlanMerger<>(List.of(first, second), first, second, merged).merge())
                .containsExactly(merged);
    }

}
