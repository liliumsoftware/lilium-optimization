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
import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.domain.TrackableCategory;
import com.github.liliumsoftware.optimization.support.CapacityRules;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.PlanInvariants;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that the parallel phase of {@link ZoneGroups} really runs the groups at the same time when the optimizer calls
 * the repairing {@link TrackableAssignment#assign(List, List)}: that entry point holds no lock, so two groups can be
 * inside it at once.
 */
class ZoneGroupsParallelismTest {

    private static final long BARRIER_TIMEOUT_SECONDS = 10;

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode z1a = TestNode.customer("A", "Z1", 10, 0, 1);
    private final TestNode z2c = TestNode.customer("C", "Z2", 0, 10, 1);

    private static TestPlan plan(String code, String zone, TestNode... nodes) {
        return new TestPlan(code, List.of(nodes), zone);
    }

    @Test
    void twoGroupsAreInsideTheRepairingAssignAtTheSameTime() {
        // Each group's single plan reaches the list hook once in phase 1. The first two hook calls wait for each other
        // at a barrier: they can only meet if both groups run assign(otherPlans, plans) concurrently. A lock on that
        // entry point would keep the second group out until the first one's wait timed out.
        MatrixCostCalculation calculation = new MatrixCostCalculation(OptimizationType.LOCATION_ZONE,
                Map.of("Z1", List.of("Z1"), "Z2", List.of("Z2")));
        calculation.initializeCostMatrix(List.of(origin, z1a, z2c));
        RendezvousAssignment assignment = new RendezvousAssignment(List.of(new TestTrackable("X", 5),
                new TestTrackable("Y", 5)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p1", "Z1", origin, z1a), plan("p2", "Z2", origin, z2c)));
        assertThat(assignment.assign(plans)).isTrue();
        assignment.armed.set(true);

        List<TestPlan> result = ZoneGroups.apply(calculation, assignment, 2, plans, (others, group) -> {
            List<TestPlan> candidate = new ArrayList<>(group.stream().map(TestPlan::copy).toList());
            return assignment.assign(others, candidate) ? candidate : group;
        });

        assertThat(assignment.met.get()).as("both groups reached the barrier together").isEqualTo(2);
        assertThat(assignment.missed).as("no group waited in vain").isFalse();
        assertThat(result).extracting(p -> p.getCode() + "=" + p.getTrackable())
                .containsExactly("p1=X#0", "p2=Y#0");
        PlanInvariants.assertEveryPlanHasTrackable(result);
        PlanInvariants.assertTrackablesAreDistinct(result);
    }

    /**
     * Capacity-rule assignment whose list hook, once armed, makes its first two calls meet at a barrier (with a
     * timeout, so a serialized run fails instead of hanging) and records whether they met.
     */
    private static final class RendezvousAssignment extends TrackableAssignment<TestPlan, TestTrackable> {

        private final CyclicBarrier barrier = new CyclicBarrier(2);
        private final AtomicBoolean armed = new AtomicBoolean();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger met = new AtomicInteger();
        private volatile boolean missed;

        private RendezvousAssignment(List<TestTrackable> trackables) {
            super(trackables);
        }

        @Override
        protected boolean assign(TestPlan plan, TestTrackable trackable) {
            return CapacityRules.assign(plan, trackable);
        }

        @Override
        protected boolean assign(TestPlan plan, List<TestTrackable> trackables) {
            if (armed.get() && calls.incrementAndGet() <= 2) {
                try {
                    barrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    met.incrementAndGet();
                } catch (TimeoutException | BrokenBarrierException e) {
                    missed = true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    missed = true;
                }
            }
            return CapacityRules.assign(plan, trackables);
        }

        @Override
        protected Comparator<TestTrackable> comparator(TestPlan plan) {
            return CapacityRules.comparator();
        }

        @Override
        protected TrackableCategory getCategory(TestTrackable trackable) {
            return CapacityRules.category(trackable);
        }

    }

}
