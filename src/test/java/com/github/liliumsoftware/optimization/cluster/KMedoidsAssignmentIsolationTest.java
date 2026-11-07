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

package com.github.liliumsoftware.optimization.cluster;

import com.github.liliumsoftware.optimization.support.AlwaysFeasibleChecker;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.TestAssignment;
import com.github.liliumsoftware.optimization.support.TestInsertion;
import com.github.liliumsoftware.optimization.support.TestKMedoids;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test: a refused enlargement in the k-medoids insertion step must leave the plans already accepted in the
 * round exactly as they were assigned. The resource check used to run on the accepted plan objects themselves, so a
 * refusal could leave one of them on another unit, or on none.
 */
class KMedoidsAssignmentIsolationTest {

    @Test
    void aRefusedEnlargementLeavesTheAcceptedPlansOnTheirUnits() {
        TestNode origin = TestNode.origin("O", 0, 0);
        TestNode a = TestNode.customer("a", 10, 0);
        TestNode d = TestNode.customer("d", 13, 0);
        TestNode b = TestNode.customer("b", -10, 0);
        TestNode c = TestNode.customer("c", -11, 0);
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, a, d, b, c));
        // Capacity counts customers; the assignment takes the smallest fitting unit first.
        TestTrackable small = new TestTrackable("S", 1);
        TestTrackable large = new TestTrackable("L", 2);
        TestKMedoids kMedoids = new TestKMedoids(calculation, new TestAssignment(List.of(small, large)),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false));
        List<TestPlan> plans = new ArrayList<>(List.of(
                new TestPlan("p1", new ArrayList<>(List.of(origin, a, d))),
                new TestPlan("p2", new ArrayList<>(List.of(origin, b, c)))));

        // Medoids a and b (two-customer ties keep the first) are seeded as [a] on S and [b] on L. c (1 from b) joins
        // b first: [b, c] keeps L. Then d (3 from a) is tried on a: [a, d] needs L, which leaves S for [b, c], too
        // small, so the attempt is refused; on b, [b, c, d] fits no unit. d stays out, and [b, c] must keep L.
        List<TestPlan> result = kMedoids.apply(origin, List.of(), plans);

        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(plan -> assertThat(plan.getTrackable()).isNotNull());
        assertThat(result.stream().map(plan -> plan.getTrackable().getCode())).containsExactly("S", "L");
        assertThat(result.get(1).getCustomers()).extracting(TestNode::getCode).containsExactlyInAnyOrder("b", "c");
        assertThat(result.stream().flatMap(plan -> plan.getCustomers().stream()))
                .extracting(TestNode::getCode).doesNotContain("d");
    }

}
