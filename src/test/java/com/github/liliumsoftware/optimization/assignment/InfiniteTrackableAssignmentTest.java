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

import com.github.liliumsoftware.optimization.support.TestInfiniteAssignment;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the unlimited-fleet variant overload by overload: every overload offers the pool in comparator order and never
 * withdraws a unit, the whole-set overloads stop at the first plan no unit fits while {@code assignAndExchange} tries
 * every plan, and units are shared.
 */
class InfiniteTrackableAssignmentTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);

    private TestPlan plan(String code, int customerCount) {
        List<TestNode> nodes = new ArrayList<>();
        nodes.add(origin);
        for (int i = 0; i < customerCount; i++) {
            nodes.add(TestNode.customer(code + "-c" + i, 0, 10 * (i + 1), 1));
        }
        return new TestPlan(code, nodes);
    }

    @ParameterizedTest
    @EnumSource(WholeSet.class)
    void theWholeSetOverloadsStopAtTheFirstPlanNoUnitFits(WholeSet overload) {
        // T carries 2: p1 gets it, p2 (3 customers) fits nothing, p3 is never tried.
        TestInfiniteAssignment assignment = new TestInfiniteAssignment(List.of(new TestTrackable("T", 2)));
        TestPlan p1 = plan("p1", 1);
        TestPlan p2 = plan("p2", 3);
        TestPlan p3 = plan("p3", 1);

        assertThat(overload.run(assignment, List.of(p1, p2, p3))).isFalse();

        assertThat(p1.getTrackable()).hasToString("T#0");
        assertThat(p2.getTrackable()).isNull();
        assertThat(p3.getTrackable()).isNull();
    }

    @Test
    void assignAndExchangeTriesEveryPlanAndReportsTheFailure() {
        TestInfiniteAssignment assignment = new TestInfiniteAssignment(List.of(new TestTrackable("T", 2)));
        TestPlan p1 = plan("p1", 1);
        TestPlan p2 = plan("p2", 3);
        TestPlan p3 = plan("p3", 1);

        assertThat(assignment.assignAndExchange(new ArrayList<>(List.of(p1, p2, p3)))).isFalse();

        assertThat(p1.getTrackable()).hasToString("T#0");
        assertThat(p2.getTrackable()).isNull();
        assertThat(p3.getTrackable()).as("the failure of p2 does not stop p3").hasToString("T#0");
    }

    @ParameterizedTest
    @EnumSource(Overload.class)
    void everyOverloadOffersThePoolInComparatorOrder(Overload overload) {
        // big comes first in the fleet, but smallest-fitting-first gives the 1-customer plan small (and the 2-customer
        // plan mid).
        TestInfiniteAssignment assignment = new TestInfiniteAssignment(List.of(new TestTrackable("big", 5),
                new TestTrackable("mid", 2), new TestTrackable("small", 1)));
        TestPlan single = plan("single", 1);
        TestPlan pair = plan("pair", 2);

        assertThat(overload.run(assignment, single)).isTrue();
        assertThat(overload.run(assignment, pair)).isTrue();

        assertThat(single.getTrackable()).hasToString("small#0");
        assertThat(pair.getTrackable()).hasToString("mid#0");
    }

    @ParameterizedTest
    @EnumSource(WholeSet.class)
    void theWholeSetOverloadsHandEveryPlanTheSameBestUnit(WholeSet overload) {
        TestInfiniteAssignment assignment = new TestInfiniteAssignment(List.of(new TestTrackable("big", 5),
                new TestTrackable("small", 1)));
        List<TestPlan> plans = List.of(plan("a", 1), plan("b", 1), plan("c", 1));

        assertThat(overload.run(assignment, plans)).isTrue();

        assertThat(plans).extracting(p -> p.getTrackable().toString()).containsExactly("small#0", "small#0",
                "small#0");
    }

    @Test
    void aSubPoolLimitsTheUnitsOnOffer() {
        TestInfiniteAssignment assignment = new TestInfiniteAssignment(List.of(new TestTrackable("big", 5),
                new TestTrackable("small", 1)));
        TestTrackable big = assignment.getTrackables().getFirst();
        TestPlan plan = plan("p", 1);

        assertThat(assignment.assignWithTrackables(List.of(), plan, List.of(big))).isTrue();
        assertThat(plan.getTrackable()).isSameAs(big);

        TestPlan tooBig = plan("q", 3);
        assertThat(assignment.assignWithTrackables(List.of(tooBig), List.of(assignment.getTrackables().getLast())))
                .isFalse();
        assertThat(tooBig.getTrackable()).isNull();
    }

    @Test
    void anUnlimitedFleetSharesUnits() {
        assertThat(new TestInfiniteAssignment(List.of(new TestTrackable("T", 5))).sharesUnits()).isTrue();
    }

    /**
     * The whole-set overloads; each assigns {@code plans} against no other plans, from the full fleet.
     */
    enum WholeSet {
        ASSIGN, ASSIGN_AGAINST_OTHERS, ASSIGN_GREEDY, ASSIGN_WITH_TRACKABLES;

        boolean run(TestInfiniteAssignment assignment, List<TestPlan> plans) {
            return switch (this) {
                case ASSIGN -> assignment.assign(plans);
                case ASSIGN_AGAINST_OTHERS -> assignment.assign(new ArrayList<>(), plans);
                case ASSIGN_GREEDY -> assignment.assignGreedy(new ArrayList<>(), plans);
                case ASSIGN_WITH_TRACKABLES -> assignment.assignWithTrackables(plans, assignment.getTrackables());
            };
        }
    }

    /**
     * Every overload that assigns a single plan, directly or as part of a set.
     */
    enum Overload {
        SINGLE, SINGLE_AGAINST_OTHERS, SINGLE_WITH_TRACKABLES, WHOLE_SET, AGAINST_OTHERS, GREEDY, WITH_TRACKABLES,
        ASSIGN_AND_EXCHANGE;

        boolean run(TestInfiniteAssignment assignment, TestPlan plan) {
            List<TestPlan> plans = new ArrayList<>(List.of(plan));
            List<TestTrackable> fleet = assignment.getTrackables();
            return switch (this) {
                case SINGLE -> assignment.assign(plan);
                case SINGLE_AGAINST_OTHERS -> assignment.assign(new ArrayList<>(), plan);
                case SINGLE_WITH_TRACKABLES -> assignment.assignWithTrackables(new ArrayList<>(), plan, fleet);
                case WHOLE_SET -> assignment.assign(plans);
                case AGAINST_OTHERS -> assignment.assign(new ArrayList<>(), plans);
                case GREEDY -> assignment.assignGreedy(new ArrayList<>(), plans);
                case WITH_TRACKABLES -> assignment.assignWithTrackables(plans, fleet);
                case ASSIGN_AND_EXCHANGE -> assignment.assignAndExchange(plans);
            };
        }
    }

}
