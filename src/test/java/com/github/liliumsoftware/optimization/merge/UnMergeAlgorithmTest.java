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
import com.github.liliumsoftware.optimization.exception.CancelException;
import com.github.liliumsoftware.optimization.support.CancelSpyLocalSearch;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.PlanInvariants;
import com.github.liliumsoftware.optimization.support.RecostingLocalSearch;
import com.github.liliumsoftware.optimization.support.TestAssignment;
import com.github.liliumsoftware.optimization.support.TestDoubleUnMerge;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestSingleUnMerge;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The un-merge algorithms are the inverse of merging: they pull plans apart along zone boundaries so a plan
 * ends up serving a single zone instead of straddling several. Note that neither checks cost — the acceptance
 * criterion is purely "can the resulting plans still be resourced" — so these tests assert zone purity and
 * customer conservation rather than an improvement, and pin the eligibility rules that decide when the moves
 * apply at all.
 */
class UnMergeAlgorithmTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode destination = TestNode.destination("D", 0, 0);
    private final TestNode zone1First = TestNode.customer("A1", "Z1", 0, 10, 1);
    private final TestNode zone1Second = TestNode.customer("A2", "Z1", 0, 20, 1);
    private final TestNode zone2First = TestNode.customer("B1", "Z2", 100, 10, 1);
    private final TestNode zone2Second = TestNode.customer("B2", "Z2", 100, 20, 1);

    private MatrixCostCalculation calculation() {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, zone1First, zone1Second, zone2First, zone2Second,
                destination));
        return calculation;
    }

    private TestPlan plan(String code, String zone, TestTrackable trackable, TestNode... customers) {
        List<TestNode> nodes = new ArrayList<>();
        nodes.add(origin);
        nodes.addAll(List.of(customers));
        TestPlan plan = new TestPlan(code, nodes, zone);
        plan.setTrackable(trackable);
        return plan;
    }

    private TestAssignment fleet() {
        return new TestAssignment(List.of(new TestTrackable("T1", 9), new TestTrackable("T2", 9)));
    }

    // ---------------------------------------------------------------- single-zone un-merge

    @Test
    void pullsASharedZonesCustomersOntoTheSingleZonePlan() {
        MatrixCostCalculation calculation = calculation();
        TestPlan singleZone = plan("z1-only", "Z1", new TestTrackable("T1", 9), zone1First);
        TestPlan mixed = plan("mixed", "Z1", new TestTrackable("T2", 9), zone1Second, zone2First);
        List<TestPlan> plans = new ArrayList<>(List.of(singleZone, mixed));

        List<TestSingleUnMerge> unMerge = List.of(new TestSingleUnMerge(fleet(),
                new RecostingLocalSearch(calculation)));
        List<TestPlan> result = unMerge.getFirst().apply(plans);

        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result,
                List.of(zone1First, zone1Second, zone2First));
        assertThat(result).allSatisfy(plan -> assertThat(plan.getCustomers().stream()
                .map(TestNode::getZone).distinct())
                .as("plan %s now serves one zone", plan.getCode()).hasSize(1));
    }

    @Test
    void aPlanEmptiedByTheTransferIsRemoved() {
        MatrixCostCalculation calculation = calculation();
        // Both plans live entirely in Z1, so everything moves onto the first and the second is left empty.
        TestPlan first = plan("first", "Z1", new TestTrackable("T1", 9), zone1First);
        TestPlan second = plan("second", "Z1", new TestTrackable("T2", 9), zone1Second);
        List<TestPlan> plans = new ArrayList<>(List.of(first, second));

        List<TestPlan> result = new TestSingleUnMerge(fleet(), new RecostingLocalSearch(calculation)).apply(plans);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getCustomers()).containsExactlyInAnyOrder(zone1First, zone1Second);
        PlanInvariants.assertNoEmptyPlans(result);
    }

    @Test
    void aDestinationStaysLastAfterTheTransfer() {
        MatrixCostCalculation calculation = calculation();
        TestPlan singleZone = new TestPlan("z1-only",
                List.of(origin, zone1First, destination), "Z1");
        singleZone.setTrackable(new TestTrackable("T1", 9));
        TestPlan mixed = new TestPlan("mixed",
                List.of(origin, zone1Second, zone2First, destination), "Z1");
        mixed.setTrackable(new TestTrackable("T2", 9));
        List<TestPlan> plans = new ArrayList<>(List.of(singleZone, mixed));

        List<TestPlan> result = new TestSingleUnMerge(fleet(), new RecostingLocalSearch(calculation)).apply(plans);

        PlanInvariants.assertNodeLayout(result);
        assertThat(result).allSatisfy(plan -> assertThat(plan.getNodes().getLast()).isEqualTo(destination));
    }

    @Test
    void eligibilityRequiresAOneZonePlanAndASharedZone() {
        MatrixCostCalculation calculation = calculation();
        TestSingleUnMerge unMerge = new TestSingleUnMerge(fleet(), new RecostingLocalSearch(calculation));
        TestPlan oneZone = plan("one", "Z1", new TestTrackable("T1", 9), zone1First);
        TestPlan twoZones = plan("two", "Z1", new TestTrackable("T2", 9), zone1Second, zone2First);
        TestPlan otherZoneOnly = plan("other", "Z2", new TestTrackable("T2", 9), zone2Second);

        assertThat(unMerge.supportsPair(oneZone, twoZones)).as("shared Z1").isTrue();
        assertThat(unMerge.supportsPair(twoZones, oneZone)).as("first plan spans two zones").isFalse();
        assertThat(unMerge.supportsPair(oneZone, otherZoneOnly)).as("no shared zone").isFalse();
    }

    @Test
    void anUnMergeTheFleetCannotResourceIsRefused() {
        MatrixCostCalculation calculation = calculation();
        TestAssignment tightFleet = new TestAssignment(List.of(
                new TestTrackable("T1", 1), new TestTrackable("T2", 1)));
        TestPlan singleZone = plan("z1-only", "Z1", new TestTrackable("T1", 1), zone1First);
        TestPlan mixed = plan("mixed", "Z1", new TestTrackable("T2", 1), zone1Second, zone2First);
        List<TestPlan> plans = new ArrayList<>(List.of(singleZone, mixed));
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);

        // Moving A2 across would give the first plan two customers, which no vehicle can carry.
        assertThat(new TestSingleUnMerge(tightFleet, new RecostingLocalSearch(calculation)).apply(plans))
                .isEqualTo(snapshot);
    }

    // ---------------------------------------------------------------- double-zone un-merge

    @Test
    void twoPlansSpanningTheSameZonePairSeparateAlongTheBoundary() {
        MatrixCostCalculation calculation = calculation();
        TestPlan first = plan("p1", "Z1", new TestTrackable("T1", 9), zone1First, zone2First);
        TestPlan second = plan("p2", "Z1", new TestTrackable("T2", 9), zone1Second, zone2Second);
        List<TestPlan> plans = new ArrayList<>(List.of(first, second));

        List<TestPlan> result = new TestDoubleUnMerge(fleet(), new RecostingLocalSearch(calculation)).apply(plans);

        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result,
                List.of(zone1First, zone1Second, zone2First, zone2Second));
        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(plan -> assertThat(plan.getCustomers().stream()
                .map(TestNode::getZone).distinct())
                .as("plan %s ends up zone-pure", plan.getCode()).hasSize(1));
        assertThat(result.stream().flatMap(plan -> plan.getCustomers().stream().map(TestNode::getZone)).distinct())
                .as("the two plans cover different zones").hasSize(2);
    }

    @Test
    void eligibilityRequiresBothPlansToSpanTheSameTwoZones() {
        MatrixCostCalculation calculation = calculation();
        TestDoubleUnMerge unMerge = new TestDoubleUnMerge(fleet(), new RecostingLocalSearch(calculation));
        TestPlan bothZones = plan("both", "Z1", new TestTrackable("T1", 9), zone1First, zone2First);
        TestPlan alsoBothZones = plan("also", "Z1", new TestTrackable("T2", 9), zone1Second, zone2Second);
        TestPlan oneZone = plan("one", "Z1", new TestTrackable("T2", 9), zone1Second);

        assertThat(unMerge.supportsPair(bothZones, alsoBothZones)).isTrue();
        assertThat(unMerge.supportsPair(bothZones, oneZone)).as("second plan spans one zone").isFalse();
        assertThat(unMerge.supportsPair(oneZone, bothZones)).as("first plan spans one zone").isFalse();
    }

    @Test
    void aDestinationStaysLastOnBothPlans() {
        MatrixCostCalculation calculation = calculation();
        TestPlan first = new TestPlan("p1", List.of(origin, zone1First, zone2First, destination), "Z1");
        first.setTrackable(new TestTrackable("T1", 9));
        TestPlan second = new TestPlan("p2", List.of(origin, zone1Second, zone2Second, destination), "Z1");
        second.setTrackable(new TestTrackable("T2", 9));
        List<TestPlan> plans = new ArrayList<>(List.of(first, second));

        List<TestPlan> result = new TestDoubleUnMerge(fleet(), new RecostingLocalSearch(calculation)).apply(plans);

        PlanInvariants.assertNodeLayout(result);
        assertThat(result).allSatisfy(plan -> assertThat(plan.getNodes().getLast()).isEqualTo(destination));
    }

    @Test
    void plansWithoutRealZonesAreNeverUnMerged() {
        // Every node in the default "NoZone" (or without a zone) makes every pair look like "one zone, shared": the
        // single un-merge used to fold one route into the other with no cost check (here 2000 became 3000).
        TestNode west = TestNode.customer("W", -1000, 0, 1);
        TestNode east = TestNode.customer("E", 1000, 0, 1);
        TestNode westNoZone = new TestNode("WN", TestNode.Type.CUSTOMER, null, -1000, 0, 1);
        TestNode eastNoZone = new TestNode("EN", TestNode.Type.CUSTOMER, null, 1000, 0, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, west, east, westNoZone, eastNoZone));
        for (List<TestNode> pair : List.of(List.of(west, east), List.of(westNoZone, eastNoZone))) {
            List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("west", List.of(origin, pair.get(0))),
                    new TestPlan("east", List.of(origin, pair.get(1)))));
            TestAssignment assignment = fleet();
            assignment.assign(plans);
            List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);

            assertThat(new TestSingleUnMerge(assignment, new RecostingLocalSearch(calculation)).apply(plans))
                    .as("zone %s", pair.getFirst().getZone()).isEqualTo(snapshot);
            assertThat(new TestDoubleUnMerge(assignment, new RecostingLocalSearch(calculation)).apply(plans))
                    .as("zone %s", pair.getFirst().getZone()).isEqualTo(snapshot);
        }
    }

    @Test
    void unMergingIsOffWhenZonesDoNotRestrictTheOptimization() {
        MatrixCostCalculation calculation = calculation();
        TestPlan singleZone = plan("single", "Z1", new TestTrackable("T1", 9), zone1First);
        TestPlan mixed = plan("mixed", "Z1", new TestTrackable("T2", 9), zone1Second, zone2First);
        List<TestPlan> plans = new ArrayList<>(List.of(singleZone, mixed));
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);

        assertThat(new TestSingleUnMerge(fleet(), new RecostingLocalSearch(calculation),
                OptimizationType.NO_RESTRICTION)
                .apply(plans)).isEqualTo(snapshot);
        assertThat(new TestSingleUnMerge(fleet(), new RecostingLocalSearch(calculation), OptimizationType.LOCATION_ZONE)
                .apply(new ArrayList<>(snapshot)))
                .as("the zone-restricted default still un-merges").isNotEqualTo(snapshot);
    }

    @Test
    void cancelAbortsAndBothSignalsReachTheNestedLocalSearch() {
        CancelSpyLocalSearch single = new CancelSpyLocalSearch();
        CancelSpyLocalSearch dual = new CancelSpyLocalSearch();
        TestSingleUnMerge singleUnMerge = new TestSingleUnMerge(fleet(), single);
        TestDoubleUnMerge doubleUnMerge = new TestDoubleUnMerge(fleet(), dual);

        singleUnMerge.interrupt();
        doubleUnMerge.interrupt();
        singleUnMerge.cancel();
        doubleUnMerge.cancel();

        assertThat(single.isInterrupted()).isTrue();
        assertThat(dual.isInterrupted()).isTrue();
        assertThat(single.isCancelled()).isTrue();
        assertThat(dual.isCancelled()).isTrue();
        // The scans themselves never checked the flag, so a cancelled un-merge still ran to completion.
        MatrixCostCalculation calculation = calculation();
        TestSingleUnMerge cancelledSingle = new TestSingleUnMerge(fleet(), new RecostingLocalSearch(calculation));
        TestDoubleUnMerge cancelledDouble = new TestDoubleUnMerge(fleet(), new RecostingLocalSearch(calculation));
        cancelledSingle.cancel();
        cancelledDouble.cancel();
        List<TestPlan> plans = new ArrayList<>(List.of(plan("single", "Z1", new TestTrackable("T1", 9), zone1First),
                plan("mixed", "Z1", new TestTrackable("T2", 9), zone1Second, zone2First)));
        assertThatThrownBy(() -> cancelledSingle.apply(plans)).isInstanceOf(CancelException.class);
        assertThatThrownBy(() -> cancelledDouble.apply(plans)).isInstanceOf(CancelException.class);
    }

    @Test
    void plansWithoutAnOriginAreUnMergedIntoWellFormedRoutes() {
        // [A1, D] (zone Z1 only) and [A2, B1, D]: A2 moves onto the single-zone plan, in front of its destination.
        MatrixCostCalculation calculation = calculation();
        TestPlan single = new TestPlan("single", List.of(zone1First, destination), "Z1");
        TestPlan mixed = new TestPlan("mixed", List.of(zone1Second, zone2First, destination), "Z1");
        List<TestPlan> plans = new ArrayList<>(List.of(single, mixed));
        TestAssignment assignment = fleet();
        assignment.assign(plans);

        List<TestPlan> result = new TestSingleUnMerge(assignment, new RecostingLocalSearch(calculation)).apply(plans);

        assertThat(result).anySatisfy(plan -> assertThat(plan.getNodes()).containsExactly(zone1First, zone1Second,
                destination));
        assertThat(result).anySatisfy(plan -> assertThat(plan.getNodes()).containsExactly(zone2First, destination));
    }

    @Test
    void unrelatedPlanSetsAreLeftAlone() {
        MatrixCostCalculation calculation = calculation();
        TestPlan first = plan("p1", "Z1", new TestTrackable("T1", 9), zone1First);
        TestPlan second = plan("p2", "Z2", new TestTrackable("T2", 9), zone2First);
        List<TestPlan> plans = new ArrayList<>(List.of(first, second));
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);

        assertThat(new TestDoubleUnMerge(fleet(), new RecostingLocalSearch(calculation)).apply(plans))
                .isEqualTo(snapshot);
        assertThat(new TestDoubleUnMerge(fleet(), new RecostingLocalSearch(calculation))
                .apply(new ArrayList<>())).isEmpty();
    }

}
