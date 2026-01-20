package ir.baho.algorithm.assignment;

import ir.baho.algorithm.domain.TrackableCategory;
import ir.baho.algorithm.support.CapacityRules;
import ir.baho.algorithm.support.PlanInvariants;
import ir.baho.algorithm.support.SpeedAwareAssignment;
import ir.baho.algorithm.support.TestAlternativeFinder;
import ir.baho.algorithm.support.TestAssignment;
import ir.baho.algorithm.support.TestInfiniteAssignment;
import ir.baho.algorithm.support.TestNode;
import ir.baho.algorithm.support.TestPlan;
import ir.baho.algorithm.support.TestSecondDispatchAssignment;
import ir.baho.algorithm.support.TestTrackable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The assignment layer is the gate every accepted move must pass: an inter-plan move is committed only if
 * resources can still be assigned afterwards. If it reports success for a plan set the fleet cannot execute,
 * every algorithm downstream silently produces infeasible solutions — so the fleet-size guarantee (one unit
 * serves one plan) is the property these tests hammer hardest.
 */
class TrackableAssignmentTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);

    /**
     * The pool unit printed as {@code name} (code#index): plans hold the assignment layer's own expanded units, so a
     * test addresses a specific unit of a multi-unit resource (a hand-made trackable is always unit 0).
     */
    private static TestTrackable unit(List<TestTrackable> pool, String name) {
        return pool.stream().filter(t -> t.toString().equals(name)).findFirst().orElseThrow();
    }

    private TestPlan plan(String code, int customerCount) {
        List<TestNode> nodes = new ArrayList<>();
        nodes.add(origin);
        for (int i = 0; i < customerCount; i++) {
            nodes.add(TestNode.customer(code + "-c" + i, 0, 10 * (i + 1), 1));
        }
        return new TestPlan(code, nodes);
    }

    /**
     * A plan of the given route length, so resource-dependent costs are predictable.
     */
    private TestPlan planOfLength(String code, int length) {
        return new TestPlan(code, List.of(origin, TestNode.customer(code + "-c", 0, length, 1)));
    }

    // ---------------------------------------------------------------- fleet-size guarantee

    @Test
    void oneResourceUnitIsNeverAssignedToTwoPlans() {
        // Regression: assign(List) used to hand the same unit to every plan it fitted, so two plans could
        // "successfully" share one vehicle and the caller would commit a move the fleet cannot execute.
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 5)));
        List<TestPlan> plans = List.of(plan("p1", 1), plan("p2", 1));

        boolean assigned = assignment.assign(plans);

        assertThat(assigned).as("a single unit cannot cover two plans").isFalse();
        PlanInvariants.assertTrackablesAreDistinct(plans);
    }

    @Test
    void everyPlanGetsItsOwnUnitWhenTheFleetIsBigEnough() {
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("T1", 5), new TestTrackable("T2", 5), new TestTrackable("T3", 5)));
        List<TestPlan> plans = List.of(plan("p1", 1), plan("p2", 2), plan("p3", 3));

        assertThat(assignment.assign(plans)).isTrue();
        PlanInvariants.assertEveryPlanHasTrackable(plans);
        PlanInvariants.assertTrackablesAreDistinct(plans);
        PlanInvariants.assertCustomerCountWithinCapacity(plans);
    }

    @Test
    void multiUnitResourcesProvideThatManyIndependentAssignments() {
        // One resource standing for three interchangeable units must cover three plans, and no more.
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T", 5, 3, false)));

        List<TestPlan> three = List.of(plan("p1", 1), plan("p2", 1), plan("p3", 1));
        assertThat(assignment.assign(three)).isTrue();
        PlanInvariants.assertTrackablesAreDistinct(three);

        List<TestPlan> four = List.of(plan("q1", 1), plan("q2", 1), plan("q3", 1), plan("q4", 1));
        assertThat(assignment.assign(four)).isFalse();
    }

    @Test
    void capacityIsRespectedWhenChoosingAUnit() {
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("small", 1), new TestTrackable("big", 5)));
        List<TestPlan> plans = List.of(plan("p", 4));

        assertThat(assignment.assign(plans)).isTrue();
        assertThat(plans.get(0).getTrackable().getCode()).isEqualTo("big");
    }

    @Test
    void aPlanNoUnitCanCarryIsLeftUnassigned() {
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("small", 1)));
        TestPlan tooBig = plan("p", 3);

        assertThat(assignment.assign(List.of(tooBig))).isFalse();
        assertThat(tooBig.getTrackable()).as("a failed assignment clears the resource").isNull();
    }

    @Test
    void emptyPlanListIsTriviallyAssignable() {
        assertThat(new TestAssignment(List.of(new TestTrackable("T1", 5))).assign(List.of())).isTrue();
    }

    @Test
    void noFleetMeansNothingCanBeAssigned() {
        TestAssignment assignment = new TestAssignment(List.of());

        assertThat(assignment.getTrackables()).isEmpty();
        assertThat(assignment.assign(List.of(plan("p", 1)))).isFalse();
    }

    // ---------------------------------------------------------------- single-plan overloads

    @Test
    void assigningOnePlanExcludesUnitsHeldByTheGivenPlans() {
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("T1", 5), new TestTrackable("T2", 5)));
        List<TestPlan> existing = new ArrayList<>(List.of(plan("p1", 1)));
        assignment.assign(existing);

        TestPlan newPlan = plan("p2", 1);
        assertThat(assignment.assign(existing, newPlan)).isTrue();
        assertThat(newPlan.getTrackable()).isNotEqualTo(existing.get(0).getTrackable());
    }

    @Test
    void assigningOnePlanFailsWhenNoUnitIsLeft() {
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 5)));
        List<TestPlan> existing = new ArrayList<>(List.of(plan("p1", 1)));
        assignment.assign(existing);

        assertThat(assignment.assign(existing, plan("p2", 1))).isFalse();
    }

    @Test
    void aPlanAlreadyInTheListIsRejected() {
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 5)));
        TestPlan existing = plan("p1", 1);
        List<TestPlan> plans = new ArrayList<>(List.of(existing));

        assertThat(assignment.assign(plans, existing)).as("no plan may be assigned twice").isFalse();
    }

    @Test
    void assignWithTrackablesDrawsOnlyFromTheGivenSubPool() {
        TestTrackable inPool = new TestTrackable("T1", 5);
        TestAssignment assignment = new TestAssignment(List.of(inPool, new TestTrackable("T2", 5)));
        List<TestPlan> plans = List.of(plan("p1", 1));

        assertThat(assignment.assignWithTrackables(plans, List.of(inPool))).isTrue();
        assertThat(plans.get(0).getTrackable()).isEqualTo(inPool);

        assertThat(assignment.assignWithTrackables(List.of(plan("a", 1), plan("b", 1)), List.of(inPool)))
                .as("a one-unit sub-pool cannot cover two plans").isFalse();
    }

    @Test
    void assigningOnePlanFromASubPoolExcludesUnitsTheOtherPlansHold() {
        TestTrackable first = new TestTrackable("T1", 5);
        TestTrackable second = new TestTrackable("T2", 5);
        TestAssignment assignment = new TestAssignment(List.of(first, second));
        List<TestPlan> existing = new ArrayList<>(List.of(plan("p1", 1)));
        assignment.assignWithTrackables(existing, List.of(first, second));

        TestPlan newPlan = plan("p2", 1);
        assertThat(assignment.assignWithTrackables(existing, newPlan, List.of(first, second))).isTrue();
        assertThat(newPlan.getTrackable()).isNotEqualTo(existing.get(0).getTrackable());

        // A sub-pool holding only the already-used unit leaves nothing to hand out.
        assertThat(assignment.assignWithTrackables(existing, plan("p3", 1),
                List.of(existing.get(0).getTrackable()))).isFalse();
        // And, as with the other single-plan overload, a plan already in the list is refused outright.
        assertThat(assignment.assignWithTrackables(existing, existing.get(0), List.of(first, second))).isFalse();
    }

    @Test
    void relocateWorksInBothPlanOrderings() {
        // The stranded plan may come before or after the resource holder in the list; both directions of the
        // hand-over must be tried, otherwise the outcome would depend on list order.
        for (boolean strandedFirst : new boolean[] {true, false}) {
            TestAssignment assignment = TestAssignment.preferringLargest(List.of(
                    new TestTrackable("small", 2), new TestTrackable("big", 3)));
            TestPlan small = plan("small-plan", 1);
            TestPlan big = plan("big-plan", 3);
            List<TestPlan> plans = new ArrayList<>(strandedFirst
                    ? List.of(big, small)
                    : List.of(small, big));

            assertThat(assignment.assign(new ArrayList<>(), plans))
                    .as("strandedFirst=%s", strandedFirst).isTrue();
            PlanInvariants.assertEveryPlanHasTrackable(plans);
            PlanInvariants.assertTrackablesAreDistinct(plans);
        }
    }

    @Test
    void assignWithTrackableProbesOnACopyBeforeCommitting() {
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 1)));
        TestPlan tooBig = plan("p", 3);

        assertThat(assignment.assignWithTrackable(tooBig, new TestTrackable("T1", 1))).isFalse();
        assertThat(tooBig.getTrackable()).as("a rejected probe leaves the plan untouched").isNull();

        TestPlan fits = plan("q", 1);
        assertThat(assignment.assignWithTrackable(fits, new TestTrackable("T1", 1))).isTrue();
        assertThat(fits.getTrackable()).isNotNull();
    }

    // ---------------------------------------------------------------- repair passes

    @Test
    void relocateRescuesAStrandedPlanByHandingOverItsResource() {
        // Largest-first preference makes the greedy pass wasteful: the one-customer plan grabs the big vehicle
        // and the three-customer plan is left with nothing. Relocation must hand the big vehicle over and push
        // the small plan onto the small vehicle.
        TestAssignment assignment = TestAssignment.preferringLargest(List.of(
                new TestTrackable("small", 2), new TestTrackable("big", 3)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("small-plan", 1), plan("big-plan", 3)));

        assertThat(assignment.assign(new ArrayList<>(), plans)).isTrue();

        PlanInvariants.assertEveryPlanHasTrackable(plans);
        PlanInvariants.assertTrackablesAreDistinct(plans);
        assertThat(plans.stream().filter(p -> p.getCustomerCount() == 3).findFirst().orElseThrow()
                .getTrackable().getCode()).isEqualTo("big");
    }

    @Test
    void relocateReportsFailureWhenTheFleetIsSimplyTooSmall() {
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("only", 5)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p1", 1), plan("p2", 1)));

        assertThat(assignment.assign(new ArrayList<>(), plans)).isFalse();
    }

    @Test
    void assigningAgainstOtherPlansCannotStealTheirResources() {
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("T1", 5), new TestTrackable("T2", 5)));
        List<TestPlan> otherPlans = new ArrayList<>(List.of(plan("other", 1)));
        assignment.assign(otherPlans);
        List<TestPlan> plans = new ArrayList<>(List.of(plan("mine", 1)));

        assertThat(assignment.assign(otherPlans, plans)).isTrue();
        assertThat(plans.get(0).getTrackable())
                .isNotEqualTo(otherPlans.get(0).getTrackable());
    }

    @Test
    void exchangeSwapsResourcesWhenThatLowersTotalCost() {
        // Cost = route length × vehicle slowness (capacity). Starting from the wasteful pairing
        // (long route on the slow big vehicle) a swap halves the total, so exchange must take it.
        SpeedAwareAssignment assignment = new SpeedAwareAssignment(List.of(
                new TestTrackable("fast", 2), new TestTrackable("slow", 5)));
        TestPlan longPlan = planOfLength("long", 100);
        TestPlan shortPlan = planOfLength("short", 10);
        assignment.assignWithTrackable(longPlan, new TestTrackable("slow", 5));
        assignment.assignWithTrackable(shortPlan, new TestTrackable("fast", 2));
        List<TestPlan> plans = new ArrayList<>(List.of(longPlan, shortPlan));

        assertThat(plans.stream().mapToLong(TestPlan::getCost).sum()).isEqualTo(520);

        assignment.repairExchange(plans);

        // 100×5 + 10×2 = 520 before; 100×2 + 10×5 = 250 after.
        assertThat(plans.stream().mapToLong(TestPlan::getCost).sum()).isEqualTo(250);
        assertThat(plans.stream().filter(p -> p.getCode().equals("long")).findFirst().orElseThrow()
                .getTrackable().getCode()).isEqualTo("fast");
        // Regression: the swap used to read the first plan's trackable *after* overwriting it, handing the same
        // unit to both plans (cost 220 for a fleet-violating configuration) instead of trading them.
        PlanInvariants.assertTrackablesAreDistinct(plans);
    }

    @Test
    void exchangeLeavesAssignmentsAloneWhenCostIsResourceIndependent() {
        // With TestAssignment's resource-independent cost model no swap can ever improve the objective, so the
        // repair pass must be a no-op rather than churning assignments.
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("T1", 5), new TestTrackable("T2", 5)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p1", 1), plan("p2", 2)));
        assignment.assign(plans);
        List<TestTrackable> before = plans.stream().map(TestPlan::getTrackable).toList();

        assignment.repairExchange(plans);

        assertThat(plans.stream().map(TestPlan::getTrackable)).isEqualTo(before);
    }

    @Test
    void reassignMovesAPlanToAnIdleCheaperResource() {
        SpeedAwareAssignment assignment = new SpeedAwareAssignment(List.of(
                new TestTrackable("fast", 2), new TestTrackable("slow", 5)));
        TestPlan onlyPlan = planOfLength("p", 100);
        assignment.assignWithTrackable(onlyPlan, new TestTrackable("slow", 5));
        assertThat(onlyPlan.getCost()).isEqualTo(500);
        List<TestPlan> plans = new ArrayList<>(List.of(onlyPlan));

        assignment.repairReassign(plans);

        assertThat(plans.get(0).getTrackable().getCode()).isEqualTo("fast");
        assertThat(plans.get(0).getCost()).isEqualTo(200);
    }

    @Test
    void assignAndExchangeReportsFailureWhenAPlanCannotBeResourced() {
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("only", 5)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p1", 1), plan("p2", 1)));

        assertThat(assignment.assignAndExchange(plans)).isFalse();
    }

    @Test
    void assignAndExchangeSucceedsAndKeepsUnitsDistinct() {
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("T1", 5), new TestTrackable("T2", 5)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p1", 1), plan("p2", 1)));

        assertThat(assignment.assignAndExchange(plans)).isTrue();
        PlanInvariants.assertEveryPlanHasTrackable(plans);
        PlanInvariants.assertTrackablesAreDistinct(plans);
    }

    // ---------------------------------------------------------------- variants

    @Test
    void infiniteAssignmentNeverRunsOutOfResources() {
        TestInfiniteAssignment assignment = new TestInfiniteAssignment(List.of(new TestTrackable("T1", 5)));
        List<TestPlan> plans = List.of(plan("p1", 1), plan("p2", 1), plan("p3", 1));

        assertThat(assignment.assign(plans)).as("an unlimited fleet has no contention").isTrue();
        PlanInvariants.assertEveryPlanHasTrackable(plans);
        assertThat(plans.stream().map(p -> p.getTrackable().getCode()).distinct())
                .as("the same resource legitimately backs every plan").hasSize(1);
    }

    @Test
    void infiniteAssignmentStillRespectsCapacity() {
        TestInfiniteAssignment assignment = new TestInfiniteAssignment(List.of(new TestTrackable("T1", 1)));

        assertThat(assignment.assign(List.of(plan("p", 3)))).isFalse();
        assertThat(assignment.assign(plan(plan("q", 1).getCode(), 1))).isTrue();
    }

    @Test
    void infiniteAssignAndExchangeNeverRunsOutOfResources() {
        // The merge algorithm validates every candidate through assignAndExchange; with an unlimited fleet the
        // inherited version still withdrew each unit and failed as soon as plans outnumbered the units.
        TestInfiniteAssignment assignment = new TestInfiniteAssignment(List.of(new TestTrackable("T1", 5)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p1", 1), plan("p2", 1), plan("p3", 1)));

        assertThat(assignment.assignAndExchange(plans)).isTrue();
        PlanInvariants.assertEveryPlanHasTrackable(plans);
        assertThat(assignment.assignAndExchange(new ArrayList<>(List.of(plan("big", 6))))).isFalse();
    }

    @Test
    void infiniteAssignmentFromASubPoolNeverWithdrawsUnits() {
        TestInfiniteAssignment assignment = new TestInfiniteAssignment(List.of(new TestTrackable("T1", 5)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p1", 1), plan("p2", 1)));

        assertThat(assignment.assignWithTrackables(plans, assignment.getTrackables())).isTrue();
        PlanInvariants.assertEveryPlanHasTrackable(plans);

        TestPlan extra = plan("p3", 1);
        assertThat(assignment.assignWithTrackables(plans, extra, assignment.getTrackables()))
                .as("units held by the other plans stay available").isTrue();
        assertThat(extra.getTrackable()).isEqualTo(plans.get(0).getTrackable());
        assertThat(assignment.assignWithTrackables(plans, plans.get(0), assignment.getTrackables()))
                .as("a plan already in the list is still refused").isFalse();
    }

    @Test
    void infiniteAssignmentAgainstOtherPlansMayReuseTheirUnits() {
        TestInfiniteAssignment assignment = new TestInfiniteAssignment(List.of(new TestTrackable("T1", 5)));
        TestPlan other = plan("other", 1);
        assertThat(assignment.assign(other)).isTrue();
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p1", 1), plan("p2", 1)));

        assertThat(assignment.assign(List.of(other), plans)).isTrue();
        PlanInvariants.assertEveryPlanHasTrackable(plans);
    }

    @Test
    void infiniteAssignmentStillRefusesAPlanAlreadyInTheList() {
        TestInfiniteAssignment assignment = new TestInfiniteAssignment(List.of(new TestTrackable("T1", 5)));
        TestPlan existing = plan("p", 1);

        assertThat(assignment.assign(List.of(existing), existing)).isFalse();
    }

    @Test
    void secondDispatchPoolKeepsOnlyResourcesThatMayRunTwice() {
        TestTrackable returning = new TestTrackable("returning", 5, 1, true);
        TestTrackable oneWay = new TestTrackable("one-way", 5, 1, false);
        TestSecondDispatchAssignment assignment = new TestSecondDispatchAssignment(List.of(returning, oneWay));

        TestPlan firstRun = plan("first", 1);
        firstRun.setTrackable(unit(assignment.currentTrackables(), "one-way#0"));
        TestPlan returningRun = plan("second", 1);
        returningRun.setTrackable(unit(assignment.currentTrackables(), "returning#0"));

        assignment.secondDispatchOnly(List.of(firstRun, returningRun));

        // A resource already used by a plan may only stay in the pool when it supports a second dispatch,
        // which requires isReturns(); the one-way vehicle is therefore dropped.
        assertThat(assignment.currentTrackables()).extracting(TestTrackable::getCode)
                .contains("returning").doesNotContain("one-way");
    }

    @Test
    void secondDispatchSeparatesEachResourceFirstRunFromItsSecond() {
        TestTrackable vehicle = new TestTrackable("V", 5, 1, true);
        TestSecondDispatchAssignment assignment = new TestSecondDispatchAssignment(List.of(vehicle));
        TestTrackable unit = unit(assignment.currentTrackables(), "V#0");

        TestPlan firstRun = plan("first", 1);
        firstRun.setTrackable(unit);
        TestPlan secondRun = plan("second", 1);
        secondRun.setTrackable(unit.copy());
        secondRun.setSecondDispatch(true);
        TestPlan extra = plan("extra", 1);

        List<TestPlan> toPlan = assignment.secondDispatchAndOthers(
                new ArrayList<>(List.of(firstRun, secondRun)), List.of(extra));

        // The vehicle's first run is withheld (it is already committed); its second run plus the newly supplied
        // plans are what remains to be planned.
        assertThat(toPlan).contains(secondRun, extra).doesNotContain(firstRun);
        assertThat(assignment.currentTrackables()).extracting(TestTrackable::getCode).contains("V");
    }

    @Test
    void secondDispatchOnlyKeepsTheUnusedUnitsOfAMultiUnitResource() {
        // Expanded units share their code; only the unit a plan actually used may be replaced by its re-dispatch
        // copy. Matching by code alone used to drop the idle siblings from the pool.
        TestSecondDispatchAssignment assignment = new TestSecondDispatchAssignment(
                List.of(new TestTrackable("V", 5, 3, true)));
        TestPlan firstRun = plan("first", 1);
        firstRun.setTrackable(unit(assignment.currentTrackables(), "V#0"));

        assignment.secondDispatchOnly(List.of(firstRun));

        assertThat(assignment.currentTrackables()).extracting(TestTrackable::toString)
                .containsExactlyInAnyOrder("V#0", "V#1", "V#2");
    }

    @Test
    void secondDispatchAndOthersKeepsTheUnusedUnitsOfAMultiUnitResource() {
        TestSecondDispatchAssignment assignment = new TestSecondDispatchAssignment(
                List.of(new TestTrackable("V", 5, 3, true)));
        TestTrackable used = unit(assignment.currentTrackables(), "V#0");
        TestPlan firstRun = plan("first", 1);
        firstRun.setTrackable(used);
        TestPlan secondRun = plan("second", 1);
        secondRun.setTrackable(used.copy());
        secondRun.setSecondDispatch(true);

        List<TestPlan> toPlan = assignment.secondDispatchAndOthers(new ArrayList<>(List.of(firstRun, secondRun)),
                List.of());

        assertThat(toPlan).containsExactly(secondRun);
        assertThat(assignment.currentTrackables()).extracting(TestTrackable::toString)
                .containsExactlyInAnyOrder("V#0", "V#1", "V#2");
    }

    @Test
    void exchangeRepeatsForPlansEqualByCodeToo() {
        // The repair loops also stopped when the plan list equalled an earlier snapshot; plans equal by code made
        // every snapshot equal, so exchange ended after one pass at 580 instead of the optimum 520.
        SpeedAwareAssignment assignment = new SpeedAwareAssignment(List.of(
                new TestTrackable("v2", 2), new TestTrackable("v3", 3),
                new TestTrackable("v5", 5), new TestTrackable("v7", 7)));
        List<TestPlan> plans = new ArrayList<>(List.of(planOfLength("p10", 10), planOfLength("p50", 50),
                planOfLength("p20", 20), planOfLength("p100", 100)));
        String[] units = {"v2", "v3", "v5", "v7"};
        for (int i = 0; i < plans.size(); i++) {
            plans.get(i).setEquality(TestPlan.Equality.CODE);
            assignment.assignWithTrackable(plans.get(i), unit(assignment.getTrackables(), units[i] + "#0"));
        }

        assignment.repairExchange(plans);

        assertThat(plans.stream().mapToLong(TestPlan::getCost).sum()).isEqualTo(520);
        PlanInvariants.assertTrackablesAreDistinct(plans);
    }

    @Test
    void infiniteAssignmentPrefersTheUnitTheComparatorRanksFirst() {
        // Smallest-fitting-first: a one-customer plan takes the small unit even though the big one comes first in the
        // fleet, in every overload, as the finite variant does.
        TestInfiniteAssignment assignment = new TestInfiniteAssignment(List.of(
                new TestTrackable("big", 5), new TestTrackable("small", 1)));
        TestPlan single = plan("single", 1);
        TestPlan pair = plan("pair", 2);

        assertThat(assignment.assignAndExchange(new ArrayList<>(List.of(single, pair)))).isTrue();
        assertThat(single.getTrackable().getCode()).isEqualTo("small");
        assertThat(pair.getTrackable().getCode()).isEqualTo("big");

        TestPlan another = plan("another", 1);
        assertThat(assignment.assign(List.of(another))).isTrue();
        assertThat(another.getTrackable().getCode()).isEqualTo("small");
        assertThat(assignment.assignWithTrackables(List.of(single), another, assignment.getTrackables())).isTrue();
        assertThat(another.getTrackable().getCode()).isEqualTo("small");
    }

    @Test
    void reassignProbesEveryCandidateOnAFreshCopyOfThePlan() {
        // B refuses the 2-customer plan; that refusal must not leak into the probe of C (it used to leave the
        // shared probe copy without a resource and fail on the next category lookup).
        SpeedAwareAssignment assignment = new SpeedAwareAssignment(List.of(
                new TestTrackable("A", 5), new TestTrackable("B", 1), new TestTrackable("C", 3)));
        TestPlan plan = plan("p", 2);
        plan.setTrackable(unit(assignment.getTrackables(), "A#0"));
        plan.setCostMultiplier(5);
        List<TestPlan> plans = new ArrayList<>(List.of(plan));

        assignment.repairReassign(plans);

        assertThat(plans.get(0).getTrackable()).hasToString("C#0");
    }

    @Test
    void assignAgainstOtherPlansNeverHandsOutTheirUnits() {
        // The cheaper C is held by a plan of another zone group; the repair passes must not move P onto it.
        SpeedAwareAssignment assignment = new SpeedAwareAssignment(List.of(
                new TestTrackable("A", 5), new TestTrackable("C", 2)));
        TestPlan other = plan("other", 1);
        other.setTrackable(unit(assignment.getTrackables(), "C#0"));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p", 2)));

        assertThat(assignment.assign(List.of(other), plans)).isTrue();

        assertThat(plans.get(0).getTrackable()).hasToString("A#0");
    }

    @Test
    void aCallerBuiltResourceThatWasNeverExpandedIsTheSameUnitAsItsPoolCopy() {
        // The caller's plan runs on a T2 it built itself (never expanded by the assignment layer). The pool numbers
        // the only unit of a count-1 resource 0, so the never-expanded default index must be 0 as well; with the old
        // default of 1 the two never matched and T2 was handed out a second time.
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T2", 5)));
        TestPlan other = plan("other", 1);
        other.setTrackable(new TestTrackable("T2", 5));
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p", 1)));

        assertThat(other.getTrackable()).isEqualTo(unit(assignment.getTrackables(), "T2#0"));
        assertThat(assignment.assign(List.of(other), plans)).as("the only unit is already in use").isFalse();
        assertThat(plans.get(0).getTrackable()).isNull();
    }

    @Test
    void alternativeFinderListsInterchangeableResourcesExcludingItself() {
        TestTrackable target = new TestTrackable("A", 5);
        TestAlternativeFinder finder = new TestAlternativeFinder(List.of(
                target, new TestTrackable("B", 5), new TestTrackable("C", 9)));

        assertThat(finder.getAlternatives(target)).containsExactly("B");
        assertThat(finder.getAlternatives(new TestTrackable("D", 1))).isEmpty();
    }

    @Test
    void alternativeFinderExcludesEveryResourceSharingTheGivenCode() {
        TestAlternativeFinder finder = new TestAlternativeFinder(List.of(
                new TestTrackable("A", 5), new TestTrackable("A", 5)));

        assertThat(finder.getAlternatives(new TestTrackable("A", 5)))
                .as("identity is by code, so same-code units are never their own alternative").isEmpty();
    }

    @Test
    void exchangeRepeatsUntilNoSwapImprovesTheTotal() {
        // Regression: the repair loops stored the *live* plan list in their history, so the fixed-point test was
        // trivially true and every loop stopped after a single pass. Four plans of lengths 10/50/20/100 on vehicles
        // of slowness 2/3/5/7 (assigned in that order) end the first pairwise pass at 580; the optimum - longest
        // route on the fastest vehicle, 100x2 + 50x3 + 20x5 + 10x7 - is 520 and needs a second pass.
        SpeedAwareAssignment assignment = new SpeedAwareAssignment(List.of(
                new TestTrackable("v2", 2), new TestTrackable("v3", 3),
                new TestTrackable("v5", 5), new TestTrackable("v7", 7)));
        TestPlan p10 = planOfLength("p10", 10);
        TestPlan p50 = planOfLength("p50", 50);
        TestPlan p20 = planOfLength("p20", 20);
        TestPlan p100 = planOfLength("p100", 100);
        assignment.assignWithTrackable(p10, new TestTrackable("v2", 2));
        assignment.assignWithTrackable(p50, new TestTrackable("v3", 3));
        assignment.assignWithTrackable(p20, new TestTrackable("v5", 5));
        assignment.assignWithTrackable(p100, new TestTrackable("v7", 7));
        List<TestPlan> plans = new ArrayList<>(List.of(p10, p50, p20, p100));
        assertThat(plans.stream().mapToLong(TestPlan::getCost).sum()).isEqualTo(20 + 150 + 100 + 700);

        assignment.repairExchange(plans);

        assertThat(plans.stream().mapToLong(TestPlan::getCost).sum()).isEqualTo(520);
        assertThat(plans.stream().filter(p -> p.getCode().equals("p100")).findFirst().orElseThrow()
                .getTrackable().getCode()).isEqualTo("v2");
        assertThat(plans.stream().filter(p -> p.getCode().equals("p10")).findFirst().orElseThrow()
                .getTrackable().getCode()).isEqualTo("v7");
        PlanInvariants.assertTrackablesAreDistinct(plans);
    }

    @Test
    void relocateRepeatsUntilEveryRescuablePlanIsAssigned() {
        // Two stranded plans and two donors holding the only big units: relocation must hand both big units over
        // and find the donors small ones, without booking any unit twice.
        TestAssignment assignment = new TestAssignment(List.of(
                new TestTrackable("big1", 5), new TestTrackable("big2", 5),
                new TestTrackable("small1", 1), new TestTrackable("small2", 1)));
        TestPlan a = plan("a", 1);
        TestPlan b = plan("b", 1);
        TestPlan c = plan("c", 3);
        TestPlan d = plan("d", 3);
        a.setTrackable(unit(assignment.pool(), "big1#0"));
        b.setTrackable(unit(assignment.pool(), "big2#0"));
        List<TestPlan> plans = new ArrayList<>(List.of(a, b, c, d));

        boolean rescued = assignment.repairRelocate(plans, assignment.pool());

        assertThat(rescued).isTrue();
        PlanInvariants.assertEveryPlanHasTrackable(plans);
        PlanInvariants.assertTrackablesAreDistinct(plans);
        PlanInvariants.assertCustomerCountWithinCapacity(plans);
    }

    @ParameterizedTest
    @EnumSource(TestPlan.Equality.class)
    void theRepairPassesRepeatWhateverThePlansEquality(TestPlan.Equality equality) {
        // The passes ended when the plan list "contained" an earlier snapshot. Plans equal by code made every copy
        // equal to the plan it replaced, so the exchange stopped after one pass at 580 instead of 520.
        SpeedAwareAssignment assignment = new SpeedAwareAssignment(List.of(
                new TestTrackable("v2", 2), new TestTrackable("v3", 3),
                new TestTrackable("v5", 5), new TestTrackable("v7", 7)));
        List<TestPlan> plans = new ArrayList<>();
        int[] lengths = {10, 50, 20, 100};
        String[] units = {"v2", "v3", "v5", "v7"};
        int[] slowness = {2, 3, 5, 7};
        for (int i = 0; i < lengths.length; i++) {
            TestPlan plan = planOfLength("p" + lengths[i], lengths[i]);
            plan.setEquality(equality);
            assignment.assignWithTrackable(plan, new TestTrackable(units[i], slowness[i]));
            plans.add(plan);
        }

        assignment.repairExchange(plans);

        assertThat(plans.stream().mapToLong(TestPlan::getCost).sum()).as("%s", equality).isEqualTo(520);
        PlanInvariants.assertTrackablesAreDistinct(plans);

        TestAssignment relocating = new TestAssignment(List.of(
                new TestTrackable("big1", 5), new TestTrackable("big2", 5),
                new TestTrackable("small1", 1), new TestTrackable("small2", 1)));
        List<TestPlan> stranded = new ArrayList<>(List.of(plan("a", 1), plan("b", 1), plan("c", 3), plan("d", 3)));
        stranded.forEach(plan -> plan.setEquality(equality));
        stranded.get(0).setTrackable(unit(relocating.pool(), "big1#0"));
        stranded.get(1).setTrackable(unit(relocating.pool(), "big2#0"));

        assertThat(relocating.repairRelocate(stranded, relocating.pool())).as("%s", equality).isTrue();
        PlanInvariants.assertEveryPlanHasTrackable(stranded);
        PlanInvariants.assertTrackablesAreDistinct(stranded);
    }

    @Test
    void aReassignHookThatAcceptsTiesStillTerminates() {
        // An accepted reassign restarts the scan inside the same pass, so the pass cap never triggered: a consumer rule
        // "not worse" (<=) swapped one plan between two idle units forever.
        TrackableAssignment<TestPlan, TestTrackable> notWorse = new TrackableAssignment<>(List.of(
                new TestTrackable("T1", 10), new TestTrackable("T2", 20))) {
            @Override
            protected boolean assign(TestPlan plan, TestTrackable trackable) {
                return CapacityRules.assign(plan, trackable);
            }

            @Override
            protected boolean assign(TestPlan plan, List<TestTrackable> trackables) {
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

            @Override
            protected boolean compareReassign(TestPlan plan, TestPlan newPlan) {
                return newPlan.getCost() <= plan.getCost();
            }
        };
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p", 1)));
        assertThat(notWorse.assign(plans)).isTrue();

        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> notWorse.reassign(plans));

        assertThat(plans.get(0).getTrackable()).isNotNull();
    }

    @Test
    void aUnitHeldByAFirstAndASecondRunIsOfferedOnceForTheNextRound() {
        // Both runs of T contributed T to the narrowed pool, so it held T twice and two new plans both got it.
        TestSecondDispatchAssignment assignment = new TestSecondDispatchAssignment(List.of(
                new TestTrackable("T", 10, 1, true)));
        TestPlan firstRun = plan("first", 1);
        assertThat(assignment.assign(List.of(firstRun))).isTrue();
        TestPlan secondRun = plan("second", 1);
        secondRun.setSecondDispatch(true);
        secondRun.setTrackable(firstRun.getTrackable().copy());

        assignment.secondDispatchOnly(List.of(firstRun, secondRun));

        assertThat(assignment.currentTrackables()).extracting(TestTrackable::toString).containsExactly("T#0");
        List<TestPlan> next = List.of(plan("x", 1), plan("y", 1));
        assertThat(assignment.assign(next)).as("one vehicle cannot run both new plans").isFalse();
        PlanInvariants.assertTrackablesAreDistinct(next);
    }

    @Test
    void theInfiniteFleetHandsTheAssignmentHookAPrivateModifiableListInEveryOverload() {
        // Some overloads passed the unmodifiable fleet itself, others a copy: a hook that orders the list it is given
        // (as the finite variant's lists allow) failed with UnsupportedOperationException in the first group only.
        InfiniteTrackableAssignment<TestPlan, TestTrackable> sorting = new InfiniteTrackableAssignment<>(List.of(
                new TestTrackable("big", 9), new TestTrackable("small", 2))) {
            @Override
            protected boolean assign(TestPlan plan, TestTrackable trackable) {
                return CapacityRules.assign(plan, trackable);
            }

            @Override
            protected boolean assign(TestPlan plan, List<TestTrackable> trackables) {
                trackables.sort(CapacityRules.comparator());
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
        };
        List<TestPlan> plans = new ArrayList<>(List.of(plan("p1", 1), plan("p2", 1)));

        assertThat(sorting.assign(plan("single", 1))).isTrue();
        assertThat(sorting.assign(plans)).isTrue();
        assertThat(sorting.assign(List.of(), plan("other", 1))).isTrue();
        assertThat(sorting.assign(List.of(), plans)).isTrue();
        assertThat(sorting.assignAndExchange(plans)).isTrue();
        assertThat(sorting.assignGreedy(List.of(), plans)).isTrue();
        assertThat(sorting.assignWithTrackables(plans, sorting.getTrackables())).isTrue();
        assertThat(plans).allSatisfy(plan -> assertThat(plan.getTrackable()).hasToString("small#0"));
    }

}
