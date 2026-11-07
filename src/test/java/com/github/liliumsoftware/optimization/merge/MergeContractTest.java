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

package com.github.liliumsoftware.optimization.merge;

import com.github.liliumsoftware.optimization.OptimizationType;
import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.domain.TrackableCategory;
import com.github.liliumsoftware.optimization.support.CapacityRules;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.PlanInvariants;
import com.github.liliumsoftware.optimization.support.RecostingLocalSearch;
import com.github.liliumsoftware.optimization.support.TestAssignment;
import com.github.liliumsoftware.optimization.support.TestDoubleUnMerge;
import com.github.liliumsoftware.optimization.support.TestMerge;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestSingleUnMerge;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the merge and un-merge contracts the broader tests leave open: the result does not depend on the plans'
 * equality contract, the caller's plan objects are never modified, {@code interrupt()} and {@code supports(plan)} keep
 * plans out of the search, a pair is never combined with itself, an un-merge falls back to the reverse orientation
 * when the first cannot be resourced, cascades of un-merges terminate, and the double un-merge's zone eligibility.
 */
class MergeContractTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode destination = TestNode.destination("D", 0, 0);
    private final TestNode a = TestNode.customer("A", 0, 10, 1);
    private final TestNode b = TestNode.customer("B", 0, 20, 1);
    private final TestNode c = TestNode.customer("C", 0, 30, 1);
    private final TestNode zone1First = TestNode.customer("A1", "Z1", 0, 10, 1);
    private final TestNode zone1Second = TestNode.customer("A2", "Z1", 0, 20, 1);
    private final TestNode zone1Third = TestNode.customer("A3", "Z1", 0, 30, 1);
    private final TestNode zone2First = TestNode.customer("B1", "Z2", 100, 10, 1);
    private final TestNode zone2Second = TestNode.customer("B2", "Z2", 100, 20, 1);
    private final TestNode zone2Third = TestNode.customer("B3", "Z2", 100, 30, 1);

    private static List<List<String>> routes(List<TestPlan> plans) {
        return plans.stream().map(plan -> {
            List<String> route = new ArrayList<>(List.of(plan.getCode()));
            plan.getNodes().forEach(node -> route.add(node.getCode()));
            return route;
        }).toList();
    }

    private static void assertUntouched(List<TestPlan> originals, List<TestPlan> snapshots) {
        for (int i = 0; i < originals.size(); i++) {
            assertThat(originals.get(i).getNodes()).as("caller's plan %d", i)
                    .containsExactlyElementsOf(snapshots.get(i).getNodes());
            assertThat(originals.get(i)).isEqualTo(snapshots.get(i));
        }
    }

    private MatrixCostCalculation lineCalculation() {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, a, b, c, destination));
        return calculation;
    }

    private MatrixCostCalculation zoneCalculation() {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, zone1First, zone1Second, zone1Third, zone2First, zone2Second,
                zone2Third));
        return calculation;
    }

    private TestAssignment fleet(int units) {
        List<TestTrackable> trackables = new ArrayList<>();
        for (int i = 1; i <= units; i++) {
            trackables.add(new TestTrackable("T" + i, 9));
        }
        return new TestAssignment(trackables);
    }

    private TestPlan closed(String code, TestNode... customers) {
        List<TestNode> nodes = new ArrayList<>(List.of(origin));
        nodes.addAll(List.of(customers));
        nodes.add(destination);
        return new TestPlan(code, nodes);
    }

    private TestPlan open(String code, TestNode... customers) {
        List<TestNode> nodes = new ArrayList<>(List.of(origin));
        nodes.addAll(List.of(customers));
        return new TestPlan(code, nodes, "Z1");
    }

    // ---------------------------------------------------------------- equality contract and the caller's plans

    @Test
    void mergingDoesNotDependOnThePlansEqualityContract() {
        // Three closed tours (20 + 40 + 60 = 120) collapse into O-A-B-C-D (10 + 10 + 10 + 30 = 60) in every mode.
        MatrixCostCalculation calculation = lineCalculation();
        for (TestPlan.Equality equality : TestPlan.Equality.values()) {
            List<TestPlan> plans = new ArrayList<>(List.of(closed("p1", a), closed("p2", b), closed("p3", c)));
            plans.forEach(plan -> plan.setEquality(equality));
            TestAssignment assignment = fleet(3);
            assertThat(assignment.assign(plans)).isTrue();

            List<TestPlan> result = new TestMerge(calculation, assignment, new RecostingLocalSearch(calculation))
                    .apply(plans);

            assertThat(routes(result)).as("%s", equality).containsExactly(List.of("p1", "O", "A", "B", "C", "D"));
            assertThat(PlanInvariants.referenceTotalCost(result)).isEqualTo(60);
        }
    }

    @Test
    void theCallersPlanObjectsAreNeverModifiedByMergeOrUnMerge() {
        MatrixCostCalculation calculation = lineCalculation();
        List<TestPlan> mergePlans = new ArrayList<>(List.of(closed("p1", a), closed("p2", b)));
        TestAssignment mergeFleet = fleet(2);
        assertThat(mergeFleet.assign(mergePlans)).isTrue();
        List<TestPlan> mergeOriginals = List.copyOf(mergePlans);
        List<TestPlan> mergeSnapshots = PlanInvariants.deepCopy(mergePlans);

        assertThat(new TestMerge(calculation, mergeFleet, new RecostingLocalSearch(calculation)).apply(mergePlans))
                .hasSize(1);
        assertUntouched(mergeOriginals, mergeSnapshots);

        MatrixCostCalculation zones = zoneCalculation();
        List<TestPlan> singlePlans = new ArrayList<>(List.of(open("single", zone1First),
                open("mixed", zone1Second, zone2First)));
        TestAssignment singleFleet = fleet(2);
        assertThat(singleFleet.assign(singlePlans)).isTrue();
        List<TestPlan> singleOriginals = List.copyOf(singlePlans);
        List<TestPlan> singleSnapshots = PlanInvariants.deepCopy(singlePlans);

        assertThat(routes(new TestSingleUnMerge(singleFleet, new RecostingLocalSearch(zones)).apply(singlePlans)))
                .containsExactly(List.of("single", "O", "A1", "A2"), List.of("mixed", "O", "B1"));
        assertUntouched(singleOriginals, singleSnapshots);

        List<TestPlan> doublePlans = new ArrayList<>(List.of(open("p1", zone1First, zone2First),
                open("p2", zone1Second, zone2Second)));
        TestAssignment doubleFleet = fleet(2);
        assertThat(doubleFleet.assign(doublePlans)).isTrue();
        List<TestPlan> doubleOriginals = List.copyOf(doublePlans);
        List<TestPlan> doubleSnapshots = PlanInvariants.deepCopy(doublePlans);

        assertThat(routes(new TestDoubleUnMerge(doubleFleet, new RecostingLocalSearch(zones)).apply(doublePlans)))
                .containsExactly(List.of("p1", "O", "B1", "B2"), List.of("p2", "O", "A2", "A1"));
        assertUntouched(doubleOriginals, doubleSnapshots);
    }

    // ---------------------------------------------------------------- interrupt and supports(plan)

    @Test
    void anInstanceInterruptedBeforeTheRunReturnsThePlansUnchanged() {
        MatrixCostCalculation calculation = lineCalculation();
        List<TestPlan> mergePlans = new ArrayList<>(List.of(closed("p1", a), closed("p2", b)));
        TestAssignment mergeFleet = fleet(2);
        assertThat(mergeFleet.assign(mergePlans)).isTrue();
        List<TestPlan> mergeOriginals = List.copyOf(mergePlans);
        TestMerge merge = new TestMerge(calculation, mergeFleet, new RecostingLocalSearch(calculation));
        merge.interrupt();

        assertThat(merge.apply(mergePlans)).isSameAs(mergePlans).containsExactlyElementsOf(mergeOriginals);

        MatrixCostCalculation zones = zoneCalculation();
        TestSingleUnMerge single = new TestSingleUnMerge(fleet(2), new RecostingLocalSearch(zones));
        TestDoubleUnMerge dual = new TestDoubleUnMerge(fleet(2), new RecostingLocalSearch(zones));
        single.interrupt();
        dual.interrupt();
        List<TestPlan> singlePlans = new ArrayList<>(List.of(open("single", zone1First),
                open("mixed", zone1Second, zone2First)));
        List<TestPlan> doublePlans = new ArrayList<>(List.of(open("p1", zone1First, zone2First),
                open("p2", zone1Second, zone2Second)));
        List<TestPlan> singleOriginals = List.copyOf(singlePlans);
        List<TestPlan> doubleOriginals = List.copyOf(doublePlans);

        assertThat(single.apply(singlePlans)).containsExactlyElementsOf(singleOriginals);
        assertThat(dual.apply(doublePlans)).containsExactlyElementsOf(doubleOriginals);
    }

    @Test
    void anUnsupportedPlanIsNeverMerged() {
        // p2 is excluded: p1 (20) and p3 (60) still merge into O-A-C-D (10 + 20 + 30 = 60), p2 (40) stays alone.
        MatrixCostCalculation calculation = lineCalculation();
        List<TestPlan> plans = new ArrayList<>(List.of(closed("p1", a), closed("p2", b), closed("p3", c)));
        TestAssignment assignment = fleet(3);
        assertThat(assignment.assign(plans)).isTrue();
        TestMerge merge = new TestMerge(calculation, assignment, new RecostingLocalSearch(calculation)) {
            @Override
            protected boolean supports(TestPlan plan) {
                return !plan.getCode().equals("p2");
            }
        };

        List<TestPlan> result = merge.apply(plans);

        assertThat(routes(result)).containsExactly(List.of("p1", "O", "A", "C", "D"), List.of("p2", "O", "B", "D"));
        assertThat(PlanInvariants.referenceTotalCost(result)).isEqualTo(100);
        List<TestPlan> pair = new ArrayList<>(List.of(closed("p1", a), closed("p2", b)));
        assertThat(merge.applyPair(pair, pair.get(0), pair.get(1))).as("pairwise entry point").isFalse();
        assertThat(merge.applyPair(pair, pair.get(1), pair.get(0))).isFalse();
    }

    @Test
    void anUnsupportedPlanIsNeverUnMerged() {
        // The locked plan comes last, so the scan meets it as the partner of every other plan; the pairwise entry
        // point refuses it in either position as well.
        MatrixCostCalculation zones = zoneCalculation();
        Function<List<TestPlan>, List<TestPlan>> single = plans -> new AbstractSingleUnMergeAlgorithm<TestPlan,
                TestNode>(fleet(3), new RecostingLocalSearch(zones)) {
            @Override
            protected boolean supports(TestPlan plan) {
                return !plan.getCode().equals("locked");
            }

            List<TestPlan> run() {
                assertThat(apply(plans, plans.getLast(), plans.getFirst())).isFalse();
                assertThat(apply(plans, plans.getFirst(), plans.getLast())).isFalse();
                return apply(plans);
            }
        }.run();
        Function<List<TestPlan>, List<TestPlan>> dual = plans -> new AbstractDoubleUnMergeAlgorithm<TestPlan,
                TestNode>(fleet(3), new RecostingLocalSearch(zones)) {
            @Override
            protected boolean supports(TestPlan plan) {
                return !plan.getCode().equals("locked");
            }

            List<TestPlan> run() {
                assertThat(apply(plans, plans.getLast(), plans.getFirst())).isFalse();
                assertThat(apply(plans, plans.getFirst(), plans.getLast())).isFalse();
                return apply(plans);
            }
        }.run();

        // Unlocked, [A1] would pull A2 out of "mixed"; locked, it keeps its single customer, while "other" (Z2 only)
        // still pulls B1 out of "mixed".
        TestPlan lockedSingle = open("locked", zone1First);
        List<TestPlan> singlePlans = new ArrayList<>(List.of(open("mixed", zone1Second, zone2First),
                open("other", zone2Second), lockedSingle));
        assertThat(fleet(3).assign(singlePlans)).isTrue();
        List<TestPlan> singleResult = single.apply(singlePlans);
        assertThat(routes(singleResult)).containsExactly(List.of("mixed", "O", "A2"), List.of("other", "O", "B2", "B1"),
                List.of("locked", "O", "A1"));
        assertThat(lockedSingle.getNodes()).containsExactly(origin, zone1First);

        // The locked plan is the only partner "mixed" could un-merge with.
        List<TestPlan> doublePlans = new ArrayList<>(List.of(open("mixed", zone1Second, zone2Second),
                open("locked", zone1First, zone2First)));
        assertThat(fleet(3).assign(doublePlans)).isTrue();
        List<TestPlan> doubleSnapshot = PlanInvariants.deepCopy(doublePlans);
        assertThat(dual.apply(doublePlans)).isEqualTo(doubleSnapshot);
    }

    @Test
    void aPlanIsNeverUnMergedWithItself() {
        MatrixCostCalculation zones = zoneCalculation();
        TestPlan mixed = open("mixed", zone1First, zone2First);
        List<TestPlan> plans = new ArrayList<>(List.of(mixed));

        assertThat(new TestSingleUnMerge(fleet(1), new RecostingLocalSearch(zones)).apply(plans, mixed, mixed))
                .isFalse();
        assertThat(new TestDoubleUnMerge(fleet(1), new RecostingLocalSearch(zones)).apply(plans, mixed, mixed))
                .isFalse();
        assertThat(plans).containsExactly(mixed);
        assertThat(mixed.getNodes()).containsExactly(origin, zone1First, zone2First);
    }

    // ---------------------------------------------------------------- orientation

    @Test
    void aSingleUnMergeFallsBackToTheReverseOrientationWhenTheFirstCannotBeResourced() {
        // Both plans serve only Z1. p1 is pinned to a one-seat vehicle, so pulling A2 onto p1 cannot be resourced; the
        // reverse orientation pulls A1 onto p2 (two seats) and drops the emptied p1.
        MatrixCostCalculation zones = zoneCalculation();
        PinnedAssignment assignment = new PinnedAssignment(List.of(new TestTrackable("T1", 1),
                new TestTrackable("T2", 2)), Map.of("p1", "T1", "p2", "T2"));
        List<TestPlan> plans = new ArrayList<>(List.of(open("p1", zone1First), open("p2", zone1Second)));
        assertThat(assignment.assign(plans)).isTrue();

        List<TestPlan> result = new TestSingleUnMerge(assignment, new RecostingLocalSearch(zones)).apply(plans);

        assertThat(routes(result)).containsExactly(List.of("p2", "O", "A2", "A1"));
        assertThat(result.getFirst().getTrackable().getCode()).isEqualTo("T2");
    }

    @Test
    void aDoubleUnMergeFallsBackToTheReverseOrientationWhenTheFirstCannotBeResourced() {
        // p1 = [A1, B1] on a two-seat vehicle, p2 = [A2, B2, B3] on a three-seat one. The first orientation gives p1
        // all of Z2 (three customers), which its vehicle cannot carry; the reverse gives p1 all of Z1 (two) and p2 all
        // of Z2 (three).
        MatrixCostCalculation zones = zoneCalculation();
        PinnedAssignment assignment = new PinnedAssignment(List.of(new TestTrackable("T1", 2),
                new TestTrackable("T2", 3)), Map.of("p1", "T1", "p2", "T2"));
        List<TestPlan> plans = new ArrayList<>(List.of(open("p1", zone1First, zone2First),
                open("p2", zone1Second, zone2Second, zone2Third)));
        assertThat(assignment.assign(plans)).isTrue();

        List<TestPlan> result = new TestDoubleUnMerge(assignment, new RecostingLocalSearch(zones)).apply(plans);

        assertThat(routes(result)).containsExactly(List.of("p1", "O", "A1", "A2"),
                List.of("p2", "O", "B2", "B3", "B1"));
        PlanInvariants.assertCustomerCountWithinCapacity(result);
    }

    // ---------------------------------------------------------------- cascades

    @Test
    void aCascadeOfSingleUnMergesTerminatesWithOneZonePerPlan() {
        // [A1] pulls A2 out of [A2, B1], then A3 out of [A3, B2]; the two Z2 leftovers [B1] and [B2] then combine and
        // the emptied plan is dropped.
        MatrixCostCalculation zones = zoneCalculation();
        List<TestPlan> plans = new ArrayList<>(List.of(open("p1", zone1First), open("p2", zone1Second, zone2First),
                open("p3", zone1Third, zone2Second)));
        TestAssignment assignment = fleet(3);
        assertThat(assignment.assign(plans)).isTrue();

        List<TestPlan> result = new TestSingleUnMerge(assignment, new RecostingLocalSearch(zones)).apply(plans);

        assertThat(routes(result)).containsExactly(List.of("p1", "O", "A1", "A2", "A3"),
                List.of("p2", "O", "B1", "B2"));
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(zone1First, zone1Second, zone1Third,
                zone2First, zone2Second));
    }

    @Test
    void aCascadeOfDoubleUnMergesTerminatesOnceNoPairSpansTheSameTwoZones() {
        // The first pair separates; the third plan still spans both zones but has no partner that does, so the scan
        // ends with it unchanged.
        MatrixCostCalculation zones = zoneCalculation();
        List<TestPlan> plans = new ArrayList<>(List.of(open("p1", zone1First, zone2First),
                open("p2", zone1Second, zone2Second), open("p3", zone1Third, zone2Third)));
        TestAssignment assignment = fleet(3);
        assertThat(assignment.assign(plans)).isTrue();

        List<TestPlan> result = new TestDoubleUnMerge(assignment, new RecostingLocalSearch(zones)).apply(plans);

        assertThat(routes(result)).containsExactly(List.of("p1", "O", "B1", "B2"), List.of("p2", "O", "A2", "A1"),
                List.of("p3", "O", "A3", "B3"));
    }

    // ---------------------------------------------------------------- double un-merge eligibility

    @Test
    void aDoubleUnMergeNeedsTheSameTwoRealZonesUnderZoneRestriction() {
        MatrixCostCalculation zones = zoneCalculation();
        TestNode zone3 = TestNode.customer("C1", "Z3", 50, 50, 1);
        TestNode noZone = TestNode.customer("N", 50, 60, 1);
        TestDoubleUnMerge restricted = new TestDoubleUnMerge(fleet(2), new RecostingLocalSearch(zones));
        TestDoubleUnMerge unrestricted = new TestDoubleUnMerge(fleet(2), new RecostingLocalSearch(zones),
                OptimizationType.NO_RESTRICTION);
        TestPlan z1z2 = open("p1", zone1First, zone2First);
        TestPlan alsoZ1z2 = open("p2", zone1Second, zone2Second);

        assertThat(restricted.supportsPair(z1z2, alsoZ1z2)).isTrue();
        assertThat(unrestricted.supportsPair(z1z2, alsoZ1z2)).as("zones restrict nothing").isFalse();
        assertThat(restricted.supportsPair(z1z2, open("p3", zone1Second, zone3))).as("Z1+Z3 is another pair")
                .isFalse();
        assertThat(restricted.supportsPair(z1z2, open("p4", zone1Second, zone2Second, zone3)))
                .as("three zones").isFalse();
        assertThat(restricted.supportsPair(open("p5", zone1First, noZone), open("p6", zone1Second, noZone)))
                .as("the default zone is no real zone").isFalse();
    }

    /**
     * Assignment where every plan may only use the unit pinned to its code, and only within that unit's capacity.
     */
    private static final class PinnedAssignment extends TrackableAssignment<TestPlan, TestTrackable> {

        private final Map<String, String> pins;

        private PinnedAssignment(List<TestTrackable> trackables, Map<String, String> pins) {
            super(trackables);
            this.pins = pins;
        }

        @Override
        protected boolean assign(TestPlan plan, TestTrackable trackable) {
            if (trackable.getCode().equals(pins.get(plan.getCode())) && CapacityRules.fits(plan, trackable)) {
                plan.setTrackable(trackable);
                return true;
            }
            plan.setTrackable(null);
            return false;
        }

        @Override
        protected boolean assign(TestPlan plan, List<TestTrackable> trackables) {
            for (TestTrackable trackable : trackables) {
                if (assign(plan, trackable)) {
                    return true;
                }
            }
            plan.setTrackable(null);
            return false;
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
