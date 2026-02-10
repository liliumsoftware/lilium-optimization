package ir.baho.algorithm.optimization;

import ir.baho.algorithm.OptimizationType;
import ir.baho.algorithm.domain.Nodeable;
import ir.baho.algorithm.support.AlwaysFeasibleChecker;
import ir.baho.algorithm.support.MatrixCostCalculation;
import ir.baho.algorithm.support.PlanInvariants;
import ir.baho.algorithm.support.Problems;
import ir.baho.algorithm.support.RecostingLocalSearch;
import ir.baho.algorithm.support.TestAssignment;
import ir.baho.algorithm.support.TestExchange;
import ir.baho.algorithm.support.TestInsertion;
import ir.baho.algorithm.support.TestNode;
import ir.baho.algorithm.support.TestPlan;
import ir.baho.algorithm.support.TestRelocate;
import ir.baho.algorithm.support.TestTrackable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exchange (swap segments between two plans) and relocate (move a segment one way) share the library's
 * reference move/accept loop: copy every plan, apply the move to the copies, keep it only if resources can
 * still be assigned <em>and</em> the summed cost strictly drops, then restart the scan.
 *
 * <p>The instance below is two tight clusters 1000 apart with one customer deliberately parked in the wrong
 * cluster. The repair is obvious on paper — moving/swapping that customer saves roughly two crossings of the
 * 1000-unit gap — which makes it possible to assert the outcome instead of merely "something improved". The
 * remaining tests are invariant-based, because <em>which</em> improving move a first-improvement search picks
 * is an implementation detail, while conserving customers and never worsening cost is a contract.
 */
class ExchangeRelocateTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode near1 = TestNode.customer("N1", 10, 0, 1);
    private final TestNode near2 = TestNode.customer("N2", 20, 0, 1);
    private final TestNode far1 = TestNode.customer("F1", 1000, 0, 1);
    private final TestNode far2 = TestNode.customer("F2", 1010, 0, 1);

    private List<TestNode> allNodes() {
        return List.of(origin, near1, near2, far1, far2);
    }

    private MatrixCostCalculation calculation() {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(allNodes());
        return calculation;
    }

    /**
     * Two plans, each holding one near and one far customer — the worst possible split, since both routes must
     * cross the 1000-unit gap.
     */
    private List<TestPlan> crossedPlans(MatrixCostCalculation calculation) {
        TestPlan first = new TestPlan("p1", List.of(origin, near1, far1));
        TestPlan second = new TestPlan("p2", List.of(origin, near2, far2));
        first.setTrackable(new TestTrackable("T1", 5));
        second.setTrackable(new TestTrackable("T2", 5));
        calculation.calculateCost(first);
        calculation.calculateCost(second);
        return new ArrayList<>(List.of(first, second));
    }

    private TestAssignment fleet() {
        return new TestAssignment(List.of(new TestTrackable("T1", 5), new TestTrackable("T2", 5)));
    }

    // ---------------------------------------------------------------- exchange

    @Test
    void exchangeSwapsTheMisplacedCustomersAndLowersTotalCost() {
        MatrixCostCalculation calculation = calculation();
        List<TestPlan> plans = crossedPlans(calculation);
        long before = PlanInvariants.referenceTotalCost(plans);   // (10 + 990) + (20 + 990) = 2010

        TestExchange exchange = new TestExchange(calculation, fleet(), new RecostingLocalSearch(calculation),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false),
                ExchangeRelocateType.SIMPLE, 4, 1, true);

        List<TestPlan> result = exchange.apply(plans);

        assertThat(PlanInvariants.referenceTotalCost(result)).isLessThan(before);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(near1, near2, far1, far2));
        PlanInvariants.assertNoDuplicateCustomers(result);
        PlanInvariants.assertTrackablesAreDistinct(result);
        // Grouping by cluster is what removes a gap crossing, so each plan must end up cluster-pure.
        assertThat(result).allSatisfy(plan -> assertThat(plan.getCustomers().stream()
                .map(customer -> customer.getX() < 500).distinct())
                .as("plan %s mixes clusters", plan.getCode()).hasSize(1));
    }

    @ParameterizedTest
    @EnumSource(ExchangeRelocateType.class)
    void everyExchangeStrategyConservesCustomersAndNeverWorsensCost(ExchangeRelocateType type) {
        MatrixCostCalculation calculation = calculation();
        List<TestPlan> plans = crossedPlans(calculation);
        long before = PlanInvariants.referenceTotalCost(plans);

        TestExchange exchange = new TestExchange(calculation, fleet(), new RecostingLocalSearch(calculation),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), type, 4, 2, true);

        List<TestPlan> result = exchange.apply(plans);

        assertThat(PlanInvariants.referenceTotalCost(result)).as("%s never worsens", type).isLessThanOrEqualTo(before);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(near1, near2, far1, far2));
        PlanInvariants.assertNodeLayout(result);
        PlanInvariants.assertTrackablesAreDistinct(result);
    }

    @Test
    void exchangeLeavesAnAlreadyGroupedSolutionAlone() {
        MatrixCostCalculation calculation = calculation();
        TestPlan nearPlan = new TestPlan("near", List.of(origin, near1, near2));
        TestPlan farPlan = new TestPlan("far", List.of(origin, far1, far2));
        nearPlan.setTrackable(new TestTrackable("T1", 5));
        farPlan.setTrackable(new TestTrackable("T2", 5));
        calculation.calculateCost(nearPlan);
        calculation.calculateCost(farPlan);
        List<TestPlan> plans = new ArrayList<>(List.of(nearPlan, farPlan));
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);

        TestExchange exchange = new TestExchange(calculation, fleet(), new RecostingLocalSearch(calculation),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false),
                ExchangeRelocateType.SIMPLE, 4, 2, true);

        assertThat(exchange.apply(plans)).isEqualTo(snapshot);
    }

    @Test
    void exchangeNeverMovesADepotNode() {
        MatrixCostCalculation calculation = calculation();
        List<TestPlan> plans = crossedPlans(calculation);

        TestExchange exchange = new TestExchange(calculation, fleet(), new RecostingLocalSearch(calculation),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false),
                ExchangeRelocateType.SIMPLE, 4, 3, true);

        List<TestPlan> result = exchange.apply(plans);

        PlanInvariants.assertNodeLayout(result);
        assertThat(result).allSatisfy(plan -> assertThat(plan.getNodes().getFirst()).isEqualTo(origin));
    }

    @Test
    void aNeighbourLimitOfZeroBlocksEveryMove() {
        // Candidate targets are drawn from the source node's N nearest neighbours; with N = 0 nothing qualifies.
        MatrixCostCalculation calculation = calculation();
        List<TestPlan> plans = crossedPlans(calculation);
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);

        TestExchange exchange = new TestExchange(calculation, fleet(), new RecostingLocalSearch(calculation),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false),
                ExchangeRelocateType.SIMPLE, 0, 2, true);

        assertThat(exchange.apply(plans)).isEqualTo(snapshot);
    }

    @Test
    void aChainLimitOfZeroBlocksEveryMove() {
        MatrixCostCalculation calculation = calculation();
        List<TestPlan> plans = crossedPlans(calculation);
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);

        TestExchange exchange = new TestExchange(calculation, fleet(), new RecostingLocalSearch(calculation),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false),
                ExchangeRelocateType.SIMPLE, 4, 0, true);

        assertThat(exchange.apply(plans)).isEqualTo(snapshot);
    }

    @Test
    void aSinglePlanHasNothingToExchangeWith() {
        MatrixCostCalculation calculation = calculation();
        TestPlan only = new TestPlan("only", List.of(origin, near1, far1));
        only.setTrackable(new TestTrackable("T1", 5));
        calculation.calculateCost(only);
        List<TestPlan> plans = new ArrayList<>(List.of(only));
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);

        TestExchange exchange = new TestExchange(calculation, fleet(), new RecostingLocalSearch(calculation),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false),
                ExchangeRelocateType.SIMPLE, 4, 2, true);

        assertThat(exchange.apply(plans)).isEqualTo(snapshot);
        assertThat(exchange.apply(new ArrayList<>())).isEmpty();
    }

    @Test
    void exchangeIsDeterministic() {
        MatrixCostCalculation calculation = calculation();
        TestExchange first = new TestExchange(calculation, fleet(), new RecostingLocalSearch(calculation),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false),
                ExchangeRelocateType.SIMPLE, 4, 2, true);
        TestExchange second = new TestExchange(calculation, fleet(), new RecostingLocalSearch(calculation),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false),
                ExchangeRelocateType.SIMPLE, 4, 2, true);

        assertThat(first.apply(crossedPlans(calculation)))
                .isEqualTo(second.apply(crossedPlans(calculation)));
    }

    // ---------------------------------------------------------------- relocate

    @Test
    void relocateMovesAMisplacedCustomerAndLowersTotalCost() {
        MatrixCostCalculation calculation = calculation();
        // p1 owns both near customers plus one far customer; p2 owns the other far one. Moving the stray far
        // customer over saves p1's 2×990 detour.
        TestPlan first = new TestPlan("p1", List.of(origin, near1, near2, far1));
        TestPlan second = new TestPlan("p2", List.of(origin, far2));
        first.setTrackable(new TestTrackable("T1", 5));
        second.setTrackable(new TestTrackable("T2", 5));
        calculation.calculateCost(first);
        calculation.calculateCost(second);
        List<TestPlan> plans = new ArrayList<>(List.of(first, second));
        long before = PlanInvariants.referenceTotalCost(plans);

        TestRelocate relocate = new TestRelocate(calculation, fleet(), new RecostingLocalSearch(calculation),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false),
                ExchangeRelocateType.SIMPLE, 4, 1);

        List<TestPlan> result = relocate.apply(plans);

        assertThat(PlanInvariants.referenceTotalCost(result)).isLessThan(before);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(near1, near2, far1, far2));
        PlanInvariants.assertNodeLayout(result);
    }

    @ParameterizedTest
    @EnumSource(ExchangeRelocateType.class)
    void everyRelocateStrategyConservesCustomersAndNeverWorsensCost(ExchangeRelocateType type) {
        MatrixCostCalculation calculation = calculation();
        List<TestPlan> plans = crossedPlans(calculation);
        long before = PlanInvariants.referenceTotalCost(plans);

        TestRelocate relocate = new TestRelocate(calculation, fleet(), new RecostingLocalSearch(calculation),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), type, 4, 2);

        List<TestPlan> result = relocate.apply(plans);

        assertThat(PlanInvariants.referenceTotalCost(result)).as("%s never worsens", type).isLessThanOrEqualTo(before);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(near1, near2, far1, far2));
        PlanInvariants.assertNodeLayout(result);
        PlanInvariants.assertTrackablesAreDistinct(result);
    }

    @Test
    void aPlanEmptiedByRelocationIsDropped() {
        MatrixCostCalculation calculation = calculation();
        // The lone far customer belongs with the other far one; moving it leaves p2 with no customers at all.
        TestPlan first = new TestPlan("p1", List.of(origin, far1));
        TestPlan second = new TestPlan("p2", List.of(origin, far2));
        first.setTrackable(new TestTrackable("T1", 5));
        second.setTrackable(new TestTrackable("T2", 5));
        calculation.calculateCost(first);
        calculation.calculateCost(second);
        List<TestPlan> plans = new ArrayList<>(List.of(first, second));

        TestRelocate relocate = new TestRelocate(calculation, fleet(), new RecostingLocalSearch(calculation),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false),
                ExchangeRelocateType.TWO_OPT, 4, 1);

        List<TestPlan> result = relocate.apply(plans);

        assertThat(result).hasSize(1);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(far1, far2));
        PlanInvariants.assertNoEmptyPlans(result);
    }

    @Test
    void relocateRefusesAMoveTheFleetCannotSupport() {
        MatrixCostCalculation calculation = calculation();
        // Both plans hold two customers and every vehicle tops out at two, so no transfer can be resourced.
        TestPlan first = new TestPlan("p1", List.of(origin, near1, far1));
        TestPlan second = new TestPlan("p2", List.of(origin, near2, far2));
        first.setTrackable(new TestTrackable("T1", 2));
        second.setTrackable(new TestTrackable("T2", 2));
        calculation.calculateCost(first);
        calculation.calculateCost(second);
        List<TestPlan> plans = new ArrayList<>(List.of(first, second));

        TestAssignment tightFleet = new TestAssignment(List.of(
                new TestTrackable("T1", 2), new TestTrackable("T2", 2)));
        TestRelocate relocate = new TestRelocate(calculation, tightFleet, new RecostingLocalSearch(calculation),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false),
                ExchangeRelocateType.SIMPLE, 4, 1);

        List<TestPlan> result = relocate.apply(plans);

        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(near1, near2, far1, far2));
        PlanInvariants.assertCustomerCountWithinCapacity(result);
        PlanInvariants.assertTrackablesAreDistinct(result);
    }

    // ---------------------------------------------------------------- zone splitting entry point

    @Test
    void zoneSplitEntryPointOptimizesEachGroupAndReturnsEveryPlan() {
        Problems.Problem problem = Problems.zoned();
        MatrixCostCalculation calculation = problem.calculation(OptimizationType.LOCATION_ZONE,
                Problems.zonedNeighbors());
        List<TestPlan> plans = new ArrayList<>(List.of(
                problem.plan("z1", problem.customer("A")),
                problem.plan("z2", problem.customer("B")),
                problem.plan("z3", problem.customer("C"))));
        plans.forEach(calculation::calculateCost);
        TestAssignment assignment = problem.assignment();
        assignment.assign(plans);

        TestExchange exchange = new TestExchange(calculation, assignment, new RecostingLocalSearch(calculation),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false),
                ExchangeRelocateType.SIMPLE, 4, 2, true);

        List<TestPlan> result = exchange.apply(plans, 2);

        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, problem.customers());
        PlanInvariants.assertNodeLayout(result);
    }

    @Test
    void parallelZoneGroupsNeverBookTheSameIdleResource() {
        // Two separate zones, each holding the crossed near/far pair this class is built on, and one idle small
        // vehicle both groups would prefer. The groups run in parallel and each re-assigns its plans when it accepts
        // its swap; without the commit protocol of ZoneGroups both would hand the small vehicle to one of their plans.
        TestNode n1 = TestNode.customer("N1", "Z1", 10, 0, 1);
        TestNode n2 = TestNode.customer("N2", "Z1", 20, 0, 1);
        TestNode f1 = TestNode.customer("F1", "Z1", 1000, 0, 1);
        TestNode f2 = TestNode.customer("F2", "Z1", 1010, 0, 1);
        TestNode n3 = TestNode.customer("N3", "Z2", 0, 10, 1);
        TestNode n4 = TestNode.customer("N4", "Z2", 0, 20, 1);
        TestNode f3 = TestNode.customer("F3", "Z2", 0, 1000, 1);
        TestNode f4 = TestNode.customer("F4", "Z2", 0, 1010, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation(OptimizationType.LOCATION_ZONE,
                Map.of("Z1", List.of("Z1"), "Z2", List.of("Z2")));
        calculation.initializeCostMatrix(List.of(origin, n1, n2, f1, f2, n3, n4, f3, f4));
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("b0", 5), new TestTrackable("b1", 5),
                new TestTrackable("b2", 5), new TestTrackable("b3", 5), new TestTrackable("small", 2)));
        List<TestPlan> plans = new ArrayList<>(List.of(
                new TestPlan("p1", new ArrayList<>(List.of(origin, n1, f1)), "Z1"),
                new TestPlan("p2", new ArrayList<>(List.of(origin, n2, f2)), "Z1"),
                new TestPlan("p3", new ArrayList<>(List.of(origin, n3, f3)), "Z2"),
                new TestPlan("p4", new ArrayList<>(List.of(origin, n4, f4)), "Z2")));
        for (int i = 0; i < plans.size(); i++) {
            String unit = "b" + i + "#0";
            plans.get(i).setTrackable(assignment.pool().stream()
                    .filter(t -> t.toString().equals(unit)).findFirst().orElseThrow());
        }
        long before = PlanInvariants.referenceTotalCost(plans);

        TestExchange exchange = new TestExchange(calculation, assignment, new RecostingLocalSearch(calculation),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false),
                ExchangeRelocateType.SIMPLE, 4, 1, true);
        List<TestPlan> result = exchange.apply(plans, 2);

        assertThat(PlanInvariants.referenceTotalCost(result)).as("both groups improved").isLessThan(before);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(n1, n2, f1, f2, n3, n4, f3, f4));
        PlanInvariants.assertEveryPlanHasTrackable(result);
        PlanInvariants.assertTrackablesAreDistinct(result);
    }

    @Test
    void zoneGroupsShareTheIdleFleetWithoutStarvingEachOther() {
        // Zone Z1 can only drop to one route if it gets the single four-seat vehicle. Dealing the idle units out
        // round-robin before the groups ran gave that vehicle to the Z2 group, which had no use for it: three routes
        // costing 310 instead of two costing 208.
        TestNode a1 = TestNode.customer("A1", "Z1", 100, 0, 1);
        TestNode a2 = TestNode.customer("A2", "Z1", 100, 2, 1);
        TestNode a3 = TestNode.customer("A3", "Z1", 100, 4, 1);
        TestNode a4 = TestNode.customer("A4", "Z1", 100, 6, 1);
        TestNode b1 = TestNode.customer("B1", "Z2", -100, 0, 1);
        TestNode b2 = TestNode.customer("B2", "Z2", -100, 2, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation(OptimizationType.LOCATION_ZONE,
                Map.of("Z1", List.of("Z1"), "Z2", List.of("Z2")));
        calculation.initializeCostMatrix(List.of(origin, a1, a2, a3, a4, b1, b2));
        List<TestNode> customers = List.of(a1, a2, a3, a4, b1, b2);

        for (boolean split : new boolean[] {false, true}) {
            TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 2), new TestTrackable("T2",
                            2),
                    new TestTrackable("T3", 2), new TestTrackable("BIG", 4), new TestTrackable("S", 1)));
            List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("a1", List.of(origin, a1, a2), "Z1"),
                    new TestPlan("a2", List.of(origin, a3, a4), "Z1"), new TestPlan("b1", List.of(origin, b1, b2),
                            "Z2")));
            assertThat(assignment.assign(plans)).isTrue();
            TestRelocate relocate = new TestRelocate(calculation, assignment, new RecostingLocalSearch(calculation),
                    new TestInsertion(calculation, new AlwaysFeasibleChecker(),
                            false), ExchangeRelocateType.SIMPLE, 4, 1);

            List<TestPlan> result = split ? relocate.apply(plans, 2) : relocate.apply(plans);

            assertThat(result).as("split=%s", split).hasSize(2);
            assertThat(PlanInvariants.referenceTotalCost(result)).as("split=%s", split).isEqualTo(208);
            PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, customers);
            PlanInvariants.assertTrackablesAreDistinct(result);
            PlanInvariants.assertCustomerCountWithinCapacity(result);
        }
    }

    @Test
    void zoneGroupResultsComeBackInTheOrderOfTheirFirstInputPlan() {
        Problems.Problem problem = Problems.zoned();
        MatrixCostCalculation calculation = problem.calculation(OptimizationType.LOCATION_ZONE,
                Problems.zonedNeighbors());
        List<TestPlan> plans = new ArrayList<>(List.of(
                problem.plan("pC", problem.customer("C")),
                problem.plan("pA", problem.customer("A")),
                problem.plan("pB", problem.customer("B"))));
        TestAssignment assignment = problem.assignment();
        assignment.assign(plans);
        TestRelocate relocate = new TestRelocate(calculation, assignment, new RecostingLocalSearch(calculation),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), ExchangeRelocateType.SIMPLE, 4, 1);

        // Z1 and Z2 neighbour each other and form one group, Z3 another. The hash order of the groups used to decide
        // the output order (here pA, pB, pC), which also varies between runs for plans without value-based hash codes.
        assertThat(relocate.apply(plans, 3)).extracting(TestPlan::getCode).containsExactly("pC", "pA", "pB");
    }

    @Test
    void zoneRestrictionKeepsNonNeighbouringPlansApart() {
        // A (Z1) and C (Z3) are not neighbours, so no move may mix them even though doing so would be cheap.
        TestNode zoneA = TestNode.customer("A", "Z1", 0, 10, 1);
        TestNode zoneC = TestNode.customer("C", "Z3", 0, 12, 1);
        List<TestNode> nodes = List.of(origin, zoneA, zoneC);
        MatrixCostCalculation calculation = new MatrixCostCalculation(OptimizationType.LOCATION_ZONE,
                Map.of("Z1", List.of("Z1"), "Z3", List.of("Z3")));
        calculation.initializeCostMatrix(nodes);

        TestPlan first = new TestPlan("p1", List.of(origin, zoneA), "Z1");
        TestPlan second = new TestPlan("p2", List.of(origin, zoneC), "Z3");
        first.setTrackable(new TestTrackable("T1", 5));
        second.setTrackable(new TestTrackable("T2", 5));
        calculation.calculateCost(first);
        calculation.calculateCost(second);
        List<TestPlan> plans = new ArrayList<>(List.of(first, second));
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);

        TestRelocate relocate = new TestRelocate(calculation, fleet(), new RecostingLocalSearch(calculation),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false),
                ExchangeRelocateType.SIMPLE, 4, 2);

        assertThat(relocate.apply(plans)).isEqualTo(snapshot);
    }

    @Test
    void simpleAndTwoOptRelocateCanAppendBehindTheTargetsLastCustomer() {
        // The fixed-position strategies only ever inserted *before* a customer of the target, so the one improving
        // move of each instance — behind the last customer of an open plan (-12), or right before the destination
        // of a closed one (-14) — was never tried: cost stayed at 171 / 182 while INSERTION found it.
        TestNode o = TestNode.origin("O", 0, 0);
        TestNode d = TestNode.destination("D", 0, 0);
        List<List<List<TestNode>>> instances = List.of(
                List.of(List.of(o, TestNode.customer("C0", -30, 9, 1), TestNode.customer("C1", -5, 25, 1)),
                        List.of(o, TestNode.customer("C2", 18, -24, 1), TestNode.customer("C3", 21, 22, 1))),
                List.of(List.of(o, TestNode.customer("C0", -12, -22, 1), TestNode.customer("C1", 21, -15, 1), d),
                        List.of(o, TestNode.customer("C2", 3, -3, 1), TestNode.customer("C3", 10, 23, 1), d)));
        List<Long> expected = List.of(171L - 12, 182L - 14);
        for (int k = 0; k < instances.size(); k++) {
            List<List<TestNode>> instance = instances.get(k);
            Set<TestNode> nodes = new LinkedHashSet<>(instance.get(0));
            nodes.addAll(instance.get(1));
            MatrixCostCalculation calculation = new MatrixCostCalculation();
            calculation.initializeCostMatrix(new ArrayList<>(nodes));
            for (ExchangeRelocateType type : List.of(ExchangeRelocateType.SIMPLE, ExchangeRelocateType.TWO_OPT)) {
                List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", instance.get(0)),
                        new TestPlan("p2", instance.get(1))));
                TestAssignment assignment = fleet();
                assignment.assign(plans);

                List<TestPlan> result = new TestRelocate(calculation, assignment, new RecostingLocalSearch(calculation),
                        new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), type, 10, 1).apply(plans);

                assertThat(PlanInvariants.referenceTotalCost(result)).as("instance %d, %s", k, type)
                        .isLessThanOrEqualTo(expected.get(k));
                PlanInvariants.assertNodeLayout(result);
                PlanInvariants.assertAllCustomersVisitedExactlyOnce(result,
                        nodes.stream().filter(n -> n.getType() == Nodeable.Type.CUSTOMER).toList());
            }
        }
    }

    @Test
    void simpleRelocateDropsAnEmptiedPlanBeforeJudgingTheMove() {
        // Closed plans to a separate destination D(100,0): an emptied plan [O, D] still costs 100. SIMPLE kept it in
        // the candidate set while comparing (204 vs 204, rejected), although the move removes that plan (104 < 204);
        // the other strategies drop it first and accept.
        TestNode o = TestNode.origin("O", 0, 0);
        TestNode d = TestNode.destination("D", 100, 0);
        TestNode a = TestNode.customer("A", 50, 1, 1);
        TestNode b = TestNode.customer("B", 50, -1, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(o, a, b, d));
        for (ExchangeRelocateType type : List.of(ExchangeRelocateType.SIMPLE, ExchangeRelocateType.TWO_OPT)) {
            List<TestPlan> plans = new ArrayList<>(List.of(new TestPlan("p1", List.of(o, a, d)),
                    new TestPlan("p2", List.of(o, b, d))));
            TestAssignment assignment = fleet();
            assignment.assign(plans);

            List<TestPlan> result = new TestRelocate(calculation, assignment, new RecostingLocalSearch(calculation),
                    new TestInsertion(calculation, new AlwaysFeasibleChecker(), false), type, 4, 1).apply(plans);

            assertThat(result).as("%s", type).hasSize(1);
            assertThat(PlanInvariants.referenceTotalCost(result)).as("%s", type).isEqualTo(104);
            PlanInvariants.assertEveryPlanHasTrackable(result);
        }
    }

    @Test
    void relocateByInsertionCanDonateFromTheTailOfTheSecondPlan() {
        // Regression: for INSERTION the reverse direction only ever offered the second plan's segment at index 1,
        // so a misplaced customer further down that plan could never move into the first plan. The fleet (two
        // and three seats) blocks pushing p1's customer into p2 (four stops), p2's first two stops are the far pair
        // whose transfer would not pay, and p2's only misplaced stop is its last one.
        MatrixCostCalculation calculation = calculation();
        TestPlan first = new TestPlan("p1", List.of(origin, near1));
        TestPlan second = new TestPlan("p2", List.of(origin, far1, far2, near2));
        first.setTrackable(new TestTrackable("T1", 2));
        second.setTrackable(new TestTrackable("T2", 3));
        calculation.calculateCost(first);
        calculation.calculateCost(second);
        List<TestPlan> plans = new ArrayList<>(List.of(first, second));
        assertThat(PlanInvariants.referenceTotalCost(plans)).isEqualTo(10 + 1000 + 10 + 990);
        TestAssignment fleet = new TestAssignment(List.of(new TestTrackable("T1", 2), new TestTrackable("T2", 3)));

        TestRelocate relocate = new TestRelocate(calculation, fleet, new RecostingLocalSearch(calculation),
                new TestInsertion(calculation, new AlwaysFeasibleChecker(), false),
                ExchangeRelocateType.INSERTION, 4, 1);

        List<TestPlan> result = relocate.apply(plans);

        // Moving near2 next to near1 alone gives 20 + 1010 = 1030; the restarted scan may then find the even cheaper
        // layout [O, near1] + [O, near2, far1, far2] = 1020. Either way the stranded 2010 must be left behind.
        assertThat(PlanInvariants.referenceTotalCost(result)).isLessThanOrEqualTo(1030);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(result, List.of(near1, near2, far1, far2));
        PlanInvariants.assertTrackablesAreDistinct(result);
        PlanInvariants.assertCustomerCountWithinCapacity(result);
    }

}
