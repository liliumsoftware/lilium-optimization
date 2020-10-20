package ir.baho.algorithm.insertion;

import ir.baho.algorithm.OptimizationType;
import ir.baho.algorithm.exception.CancelException;
import ir.baho.algorithm.feasibility.FeasibilityChecker;
import ir.baho.algorithm.feasibility.FeasibilityResult;
import ir.baho.algorithm.support.AlwaysFeasibleChecker;
import ir.baho.algorithm.support.MatrixCostCalculation;
import ir.baho.algorithm.support.MaxCustomersFeasibilityChecker;
import ir.baho.algorithm.support.PlanInvariants;
import ir.baho.algorithm.support.TestInsertion;
import ir.baho.algorithm.support.TestInsertionCandidate;
import ir.baho.algorithm.support.TestNode;
import ir.baho.algorithm.support.TestPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Insertion is the repair half of most of the library: destroy-repair, the INSERTION exchange/relocate
 * strategies, balancing and clustering all place customers through it. What matters is that it probes only
 * legal positions (never before the depot), that it charges the true detour cost, and that it refuses rather
 * than forces a customer when no feasible slot exists.
 *
 * <p>Costs are hand-computed on collinear Manhattan nodes: inserting {@code M(0,20)} between {@code A(0,10)}
 * and {@code B(0,30)} costs {@code 10 + 10 − 20 = 0}, while appending it after {@code B} costs {@code 10}.
 */
class InsertionAlgorithmTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode a = TestNode.customer("A", 0, 10, 1);
    private final TestNode b = TestNode.customer("B", 0, 30, 1);
    private final TestNode middle = TestNode.customer("M", 0, 20, 1);
    private final TestNode far = TestNode.customer("F", 0, 100, 1);

    private MatrixCostCalculation calculation(List<TestNode> nodes) {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(nodes);
        return calculation;
    }

    // ---------------------------------------------------------------- position choice

    @Test
    void picksTheCheapestDetourAmongAllInteriorPositions() {
        MatrixCostCalculation calculation = calculation(List.of(origin, a, b, middle));
        TestPlan plan = new TestPlan("p", new ArrayList<>(List.of(origin, a, b)));
        TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), false);

        TestInsertionCandidate candidate = insertion.getBestInsertionCandidate(List.of(plan), middle);

        assertThat(candidate.getDeltaCost()).isZero();
        assertThat(Objects.requireNonNull(candidate.getCandidatePlan(), "no slot found").getNodes())
                .containsExactly(origin, a, middle, b);
    }

    @Test
    void neverInsertsBeforeTheDepot() {
        TestNode behind = TestNode.customer("Z", 0, -10, 1);
        MatrixCostCalculation calculation = calculation(List.of(origin, a, behind));
        TestPlan plan = new TestPlan("p", new ArrayList<>(List.of(origin, a)));
        TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), false);

        // Z lies behind the depot: putting it first would add only 10, every legal slot adds 20. Position 0 would
        // win if it were allowed; it is structurally off-limits, which keeps the ORIGIN-first layout intact.
        TestPlan candidate = insertion.getBestInsertionCandidate(List.of(plan), behind).getCandidatePlan();

        assertThat(candidate.getNodes().get(0)).isEqualTo(origin);
        PlanInvariants.assertNodeLayout(List.of(candidate));
    }

    @Test
    void insertsBeforeADestinationRatherThanAfterIt() {
        // The depot sits beyond M, so the detour is unambiguous: O-A-M-D costs 10 + 10 + 10 = 30 (delta 0)
        // while O-M-A-D costs 20 + 10 + 20 = 50. A tie would make the winning slot arbitrary.
        TestNode farDestination = TestNode.destination("D", 0, 30);
        MatrixCostCalculation calculation = calculation(List.of(origin, a, middle, farDestination));
        TestPlan plan = new TestPlan("p", new ArrayList<>(List.of(origin, a, farDestination)));
        TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), false);

        TestInsertionCandidate candidate = insertion.getBestInsertionCandidate(List.of(plan), middle);

        assertThat(Objects.requireNonNull(candidate.getCandidatePlan(), "no slot found").getNodes())
                .containsExactly(origin, a, middle, farDestination);
        assertThat(candidate.getDeltaCost()).isZero();
        PlanInvariants.assertNodeLayout(List.of(candidate.getCandidatePlan()));
    }

    @Test
    void theDeltaCostIsTheRealDifferenceBetweenTheTwoRoutes() {
        MatrixCostCalculation calculation = calculation(List.of(origin, a, b, far));
        TestPlan plan = new TestPlan("p", new ArrayList<>(List.of(origin, a, b)));
        long before = PlanInvariants.referenceCost(plan);
        TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), false);

        TestInsertionCandidate candidate = insertion.getBestInsertionCandidate(List.of(plan), far);

        assertThat(candidate.getDeltaCost())
                .isEqualTo(PlanInvariants.referenceCost(Objects.requireNonNull(candidate.getCandidatePlan())) - before);
        assertThat(candidate.getOriginalPlan()).isEqualTo(plan);
    }

    @Test
    void theCheapestOfSeveralPlansWins() {
        MatrixCostCalculation calculation = calculation(List.of(origin, a, b, middle, far));
        TestPlan nearPlan = new TestPlan("near", new ArrayList<>(List.of(origin, a, b)));
        TestPlan farPlan = new TestPlan("far", new ArrayList<>(List.of(origin, far)));
        TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), false);

        TestInsertionCandidate candidate = insertion.getBestInsertionCandidate(
                List.of(nearPlan, farPlan), middle);

        // M slots into the near plan for free; hanging it off the far plan would cost 80 + 80.
        assertThat(candidate.getOriginalPlan().getCode()).isEqualTo("near");
        assertThat(candidate.getDeltaCost()).isZero();
    }

    @Test
    void theSearchCanBeRestrictedToSpecificPlans() {
        MatrixCostCalculation calculation = calculation(List.of(origin, a, b, middle, far));
        TestPlan nearPlan = new TestPlan("near", new ArrayList<>(List.of(origin, a, b)));
        TestPlan farPlan = new TestPlan("far", new ArrayList<>(List.of(origin, far)));
        List<TestPlan> plans = new ArrayList<>(List.of(nearPlan, farPlan));
        TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), false);

        // Index 1 restricts the search to the far plan even though the near one is cheaper.
        assertThat(insertion.insert(plans, middle, 1)).isTrue();
        assertThat(plans.get(1).getCustomers()).contains(middle);
        assertThat(plans.get(0).getCustomers()).doesNotContain(middle);
    }

    // ---------------------------------------------------------------- refusal

    @Test
    void aCustomerWithNoFeasibleSlotIsRefusedNotForced() {
        MatrixCostCalculation calculation = calculation(List.of(origin, a, b));
        TestPlan plan = new TestPlan("p", new ArrayList<>(List.of(origin, a)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan));
        TestInsertion insertion = new TestInsertion(calculation, new MaxCustomersFeasibilityChecker(1), false);

        assertThat(insertion.getBestInsertionCandidate(plans, b)).isNull();
        assertThat(insertion.insert(plans, b)).isFalse();
        assertThat(plans.get(0).getCustomers()).containsExactly(a);
    }

    @Test
    void insertingIntoNoPlansAtAllInsertsNothing() {
        MatrixCostCalculation calculation = calculation(List.of(origin, a));
        TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), false);

        assertThat(insertion.insert(new ArrayList<>(), List.of(a))).isEmpty();
        assertThat(insertion.getBestInsertionCandidate(new ArrayList<>(), a)).isNull();
    }

    @Test
    void insertingAnEmptyBatchIsANoOp() {
        MatrixCostCalculation calculation = calculation(List.of(origin, a));
        TestPlan plan = new TestPlan("p", new ArrayList<>(List.of(origin, a)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan));
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);
        TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), false);

        assertThat(insertion.insert(plans, List.of())).isEmpty();
        assertThat(plans).isEqualTo(snapshot);
    }

    @Test
    void theCannotInsertSentinelSortsLastWithoutOverflowing() {
        TestPlan plan = new TestPlan("p", List.of(origin, a));
        InsertionCandidate<TestPlan, TestNode> sentinel = new TestInsertionCandidate(plan, a);

        assertThat(sentinel.getCandidatePlan()).isNull();
        assertThat(sentinel.getDeltaCost()).isEqualTo(2_592_000_000L);
        // Summing a whole batch of sentinels must stay well inside long range (the regret matrix does exactly
        // that), which is why the sentinel is "a month in millis" rather than Long.MAX_VALUE.
        assertThat(sentinel.getDeltaCost() * 1_000).isPositive();
    }

    // ---------------------------------------------------------------- batch policies

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothPoliciesPlaceEveryFeasibleCustomerExactlyOnce(boolean regret) {
        MatrixCostCalculation calculation = calculation(List.of(origin, a, b, middle, far));
        TestPlan plan = new TestPlan("p", new ArrayList<>(List.of(origin, a)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan));
        TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), regret);

        List<TestNode> inserted = insertion.insert(plans, List.of(b, middle, far));

        assertThat(inserted).as("regret=%s", regret).containsExactlyInAnyOrder(b, middle, far);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(plans, List.of(a, b, middle, far));
        PlanInvariants.assertNodeLayout(plans);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothPoliciesSkipWhatTheyCannotPlaceAndKeepTheRest(boolean regret) {
        MatrixCostCalculation calculation = calculation(List.of(origin, a, b, middle));
        TestPlan plan = new TestPlan("p", new ArrayList<>(List.of(origin, a)));
        List<TestPlan> plans = new ArrayList<>(List.of(plan));
        // Room for exactly one more customer.
        TestInsertion insertion = new TestInsertion(calculation, new MaxCustomersFeasibilityChecker(2), regret);

        List<TestNode> inserted = insertion.insert(plans, List.of(b, middle));

        assertThat(inserted).as("regret=%s", regret).hasSize(1);
        assertThat(plans.get(0).getCustomerCount()).isEqualTo(2);
        PlanInvariants.assertNoDuplicateCustomers(plans);
    }

    @Test
    void bothPoliciesAreDeterministic() {
        MatrixCostCalculation calculation = calculation(List.of(origin, a, b, middle, far));

        for (boolean regret : new boolean[] {false, true}) {
            List<TestPlan> first = new ArrayList<>(List.of(new TestPlan("p", new ArrayList<>(List.of(origin, a)))));
            List<TestPlan> second = new ArrayList<>(List.of(new TestPlan("p", new ArrayList<>(List.of(origin, a)))));
            new TestInsertion(calculation, new AlwaysFeasibleChecker(), regret).insert(first, List.of(b, middle, far));
            new TestInsertion(calculation, new AlwaysFeasibleChecker(), regret).insert(second, List.of(b, middle, far));

            assertThat(first).as("regret=%s", regret).isEqualTo(second);
        }
    }

    @Test
    void regretModeConsumesItsBatchEvenWhenNothingCanBePlaced() {
        MatrixCostCalculation calculation = calculation(List.of(origin, a, b, middle));
        TestPlan full = new TestPlan("full", new ArrayList<>(List.of(origin, a)));
        List<TestPlan> plans = new ArrayList<>(List.of(full));
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);
        // Every plan is already at its limit, so each round produces only "cannot insert" sentinels. The batch
        // must still drain — a round that failed to drop the rejected node would loop forever.
        TestInsertion insertion = new TestInsertion(calculation, new MaxCustomersFeasibilityChecker(1), true);

        List<TestNode> inserted = insertion.insert(plans, List.of(b, middle));

        assertThat(inserted).isEmpty();
        assertThat(plans).isEqualTo(snapshot);
    }

    // ---------------------------------------------------------------- zone gate and cancellation

    @Test
    void theDefaultZoneGateOnlyAdmitsNeighbouringCustomers() {
        TestNode zone1 = TestNode.customer("Z1c", "Z1", 0, 10, 1);
        TestNode zone3 = TestNode.customer("Z3c", "Z3", 0, 12, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation(OptimizationType.LOCATION_ZONE,
                Map.of("Z1", List.of("Z1"), "Z3", List.of("Z3")));
        calculation.initializeCostMatrix(List.of(origin, zone1, zone3));
        TestPlan plan = new TestPlan("p", new ArrayList<>(List.of(origin, zone1)), "Z1");
        TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), false);

        assertThat(insertion.getBestInsertionCandidate(List.of(plan), zone3))
                .as("a customer from a non-neighbouring zone is not admitted").isNull();
    }

    @Test
    void cancelAbortsWhileProbingPositions() {
        MatrixCostCalculation calculation = calculation(List.of(origin, a, b));
        TestPlan plan = new TestPlan("p", new ArrayList<>(List.of(origin, a)));
        TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), false);

        insertion.cancel();

        assertThatThrownBy(() -> insertion.insert(new ArrayList<>(List.of(plan)), List.of(b)))
                .isInstanceOf(CancelException.class);
    }

    @Test
    void regretPrefersARealInsertionToTheCannotInsertSentinelHoweverExpensive() {
        // The only feasible slot costs a 3e9 round trip, more than the sentinel's MONTH (2.592e9) delta: the regret
        // round used to pick the cheaper-looking sentinel and drop the customer although it could be placed.
        TestNode destination = TestNode.destination("D", 0, 0);
        TestNode distant = TestNode.customer("X", 0, 1_500_000_000, 1);
        MatrixCostCalculation calculation = calculation(List.of(origin, distant, destination));
        FeasibilityChecker<TestPlan> onlyP1 = new FeasibilityChecker<>() {
            @Override
            public FeasibilityResult getResult(TestPlan plan) {
                return plan.getCode().equals("p1") ? FeasibilityResult.FEASIBLE : new MaxCustomersFeasibilityChecker(0)
                        .getResult(plan);
            }

            @Override
            public String getMessage(String key, Object... params) {
                return key;
            }
        };
        for (boolean regret : new boolean[] {false, true}) {
            List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", List.of(origin, destination)),
                    new TestPlan("p2", List.of(origin, destination))));

            List<TestNode> inserted = new TestInsertion(calculation, onlyP1, regret).insert(plans, List.of(distant));

            assertThat(inserted).as("regret=%s", regret).containsExactly(distant);
            assertThat(plans.get(0).getCustomers()).as("regret=%s", regret).containsExactly(distant);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothPoliciesRankACustomersCandidatesWithTheConsumersComparator(boolean regret) {
        // comparator() ranks the candidates of one customer; regret mode used to ignore it and always take the
        // smallest delta, so a consumer preference that cheapest mode honoured was lost.
        // M after A on "near" costs 10; the cheapest slot on "preferred" (between O and P) costs 20 + 70 - 50 = 40.
        TestNode side = TestNode.customer("P", 50, 0, 1);
        MatrixCostCalculation calculation = calculation(List.of(origin, a, side, middle));
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("near", List.of(origin, a)),
                new TestPlan("preferred", List.of(origin, side))));
        TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), regret) {
            @Override
            protected Comparator<TestInsertionCandidate> comparator() {
                return Comparator.comparing(c -> !c.getOriginalPlan().getCode().equals("preferred"));
            }
        };

        insertion.insert(plans, List.of(middle));

        assertThat(plans.get(1).getCustomers()).as("regret=%s", regret).contains(middle);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aBatchWithTwoCustomersSharingACodeIsRejected(boolean regret) {
        // Codes identify customers; cheapest/regret insertion used to place such a pair twice while Clarke-Wright
        // silently kept one, so both now refuse the batch up front.
        MatrixCostCalculation calculation = calculation(List.of(origin, a, b));
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p", List.of(origin, b))));
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);
        TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), regret);

        assertThatThrownBy(() -> insertion.insert(plans, List.of(a, a.copy())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate customer code")
                .hasMessageContaining("A");
        assertThat(plans).isEqualTo(snapshot);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anInterruptedBatchStillInsertsEveryInsertableCustomer(boolean regret) {
        // Insertion is construction: an interrupt (a spent time budget) must not leave customers out. It used to make
        // every later batch a silent no-op.
        MatrixCostCalculation calculation = calculation(List.of(origin, a, b, middle));
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p", List.of(origin, a))));
        TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), regret);

        insertion.interrupt();

        assertThat(insertion.insert(plans, List.of(b, middle))).as("regret=%s", regret)
                .containsExactlyInAnyOrder(b, middle);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(plans, List.of(a, b, middle));
    }

    @Test
    void theZoneGateIsAskedOncePerPlanNotOncePerSlot() {
        // supports(plan, customer) does not depend on the slot, so probing a plan must not re-ask it per position.
        MatrixCostCalculation calculation = calculation(List.of(origin, a, b, middle, far));
        AtomicInteger gateCalls = new AtomicInteger();
        TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), false) {
            @Override
            protected boolean supports(TestPlan plan, TestNode customer) {
                gateCalls.incrementAndGet();
                return super.supports(plan, customer);
            }
        };
        List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p", List.of(origin, a, b, far))));

        assertThat(insertion.insert(plans, middle)).isTrue();

        assertThat(gateCalls).hasValue(1);
    }

}
