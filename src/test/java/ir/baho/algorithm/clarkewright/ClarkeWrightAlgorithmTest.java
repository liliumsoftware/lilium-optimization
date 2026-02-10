package ir.baho.algorithm.clarkewright;

import ir.baho.algorithm.OptimizationType;
import ir.baho.algorithm.calculation.PathCostCalculation;
import ir.baho.algorithm.exception.CancelException;
import ir.baho.algorithm.support.CancelSpyLocalSearch;
import ir.baho.algorithm.support.MatrixCostCalculation;
import ir.baho.algorithm.support.NoOpLocalSearch;
import ir.baho.algorithm.support.PlanInvariants;
import ir.baho.algorithm.support.Problems;
import ir.baho.algorithm.support.RecostingLocalSearch;
import ir.baho.algorithm.support.TestAssignment;
import ir.baho.algorithm.support.TestClarkeWright;
import ir.baho.algorithm.support.TestNode;
import ir.baho.algorithm.support.TestPlan;
import ir.baho.algorithm.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Clarke-Wright savings construction is how this library builds plans from nothing, so its merge decisions are
 * the most consequential behaviour in the repository.
 *
 * <p>The examples below are deliberately collinear: on the line {@code O(0,0), A(0,10), B(0,20), C(0,30)} with
 * Manhattan distance every route cost and every saving can be computed by hand, so the expectations are
 * derived on paper rather than from the implementation. The savings for that instance at λ=1 are
 * {@code s(B,C)=s(C,B)=40} and {@code s(A,B)=s(A,C)=s(B,A)=s(C,A)=20}, which is why B-C merges first and A is
 * chained onto its head afterwards.
 */
class ClarkeWrightAlgorithmTest {

    private final Problems.Problem closed = Problems.collinearClosed();
    private final Problems.Problem open = Problems.collinearOpen();

    /**
     * Runs a NO_RESTRICTION construction over {@code customers} (open when {@code destination} is null) and returns
     * the visiting order as coordinates, so a test can compare two runs whose customers only differ in their codes.
     */
    private static List<String> route(TestNode destination, List<TestNode> customers, List<TestTrackable> fleet) {
        TestNode origin = TestNode.origin("O", 0, 0);
        List<TestNode> all = new ArrayList<>();
        all.add(origin);
        all.addAll(customers);
        if (destination != null) {
            all.add(destination);
        }
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(all);
        List<TestPlan> plans = new TestClarkeWright(calculation, new TestAssignment(fleet),
                new RecostingLocalSearch(calculation), destination, OptimizationType.NO_RESTRICTION)
                .plan(origin, customers);
        assertThat(plans).hasSize(1);
        return plans.getFirst().getNodes().stream().map(n -> n.getX() + "," + n.getY()).toList();
    }

    private static TestClarkeWright seedJudge(MatrixCostCalculation calculation, boolean firstReturns,
            boolean secondReturns) {
        return new TestClarkeWright(calculation, new TestAssignment(List.of(new TestTrackable("R1", 5, 1, firstReturns),
                new TestTrackable("R2", 5, 1, secondReturns))), new NoOpLocalSearch(), null,
                OptimizationType.NO_RESTRICTION);
    }

    // ---------------------------------------------------------------- known small examples

    private TestClarkeWright clarkeWright(Problems.Problem problem, MatrixCostCalculation calculation,
            List<TestTrackable> fleet) {
        return new TestClarkeWright(calculation, new TestAssignment(fleet),
                new RecostingLocalSearch(calculation), problem.destination(), OptimizationType.NO_RESTRICTION);
    }

    @Test
    void buildsTheSingleOptimalRouteWhenOneVehicleCanServeEveryone() {
        MatrixCostCalculation calculation = closed.calculation();
        TestClarkeWright cw = clarkeWright(closed, calculation, List.of(new TestTrackable("T1", 10)));

        List<TestPlan> plans = cw.plan(closed.origin(), closed.customers());

        // The unique optimum: O -> A -> B -> C -> D, i.e. 10 + 10 + 10 + 30 = 60. Any other permutation of the
        // three customers costs strictly more (e.g. O-B-A-C-D = 20 + 10 + 20 + 30 = 80).
        assertThat(plans).hasSize(1);
        assertThat(plans.getFirst().getNodes()).containsExactly(
                closed.origin(), closed.customer("A"), closed.customer("B"), closed.customer("C"),
                closed.destination());
        assertThat(PlanInvariants.referenceCost(plans.getFirst())).isEqualTo(60);
        assertThat(plans.getFirst().getCost()).isEqualTo(60);
        PlanInvariants.assertValidSolution(plans, closed.origin(), closed.destination(), closed.customers());
    }

    @Test
    void capacityStopsTheMergeAndProducesTwoFeasibleRoutes() {
        MatrixCostCalculation calculation = closed.calculation();
        // Two vehicles, each able to carry at most two customers: the three-customer merge must be refused.
        TestClarkeWright cw = clarkeWright(closed, calculation,
                List.of(new TestTrackable("T1", 2), new TestTrackable("T2", 2)));

        List<TestPlan> plans = cw.plan(closed.origin(), closed.customers());

        assertThat(plans).hasSize(2);
        assertThat(plans).allSatisfy(plan -> assertThat(plan.getCustomerCount()).isLessThanOrEqualTo(2));
        // B and C merged on the best saving (40); A stayed on its own route and was assigned the spare vehicle.
        assertThat(plans).anySatisfy(plan -> assertThat(plan.getCustomers())
                .containsExactly(closed.customer("B"), closed.customer("C")));
        assertThat(plans).anySatisfy(plan -> assertThat(plan.getCustomers())
                .containsExactly(closed.customer("A")));
        PlanInvariants.assertValidSolution(plans, closed.origin(), closed.destination(), closed.customers());
        PlanInvariants.assertEveryPlanHasTrackable(plans);
    }

    @Test
    void mergesTheHighestSavingPairFirst() {
        MatrixCostCalculation calculation = closed.calculation();
        TestClarkeWright cw = clarkeWright(closed, calculation, List.of(new TestTrackable("T1", 2)));

        // Only one two-customer vehicle exists, so exactly one merge can ever be committed: the best saving.
        List<TestPlan> plans = cw.plan(closed.origin(), closed.customers());

        assertThat(plans).hasSize(1);
        assertThat(plans.getFirst().getCustomers())
                .as("s(B,C) = 40 beats every saving involving A (20)")
                .containsExactly(closed.customer("B"), closed.customer("C"));
    }

    @Test
    void singleCustomerYieldsTheOnlyPossibleRoute() {
        Problems.Problem problem = Problems.singleCustomer();
        MatrixCostCalculation calculation = problem.calculation();
        TestClarkeWright cw = clarkeWright(problem, calculation, problem.trackables());

        List<TestPlan> plans = cw.plan(problem.origin(), problem.customers());

        assertThat(plans).hasSize(1);
        assertThat(plans.getFirst().getCustomers()).containsExactly(problem.customer("A"));
        assertThat(PlanInvariants.referenceCost(plans.getFirst())).isEqualTo(10);
    }

    @Test
    void twoCustomersAreChainedIntoTheCheaperOrder() {
        Problems.Problem problem = Problems.twoCustomers();
        MatrixCostCalculation calculation = problem.calculation();
        TestClarkeWright cw = clarkeWright(problem, calculation, List.of(new TestTrackable("T1", 5)));

        List<TestPlan> plans = cw.plan(problem.origin(), problem.customers());

        // O-A-B costs 10 + 20 = 30; O-B-A costs 30 + 20 = 50. Only the cheap orientation may survive.
        assertThat(plans).hasSize(1);
        assertThat(plans.getFirst().getCustomers())
                .containsExactly(problem.customer("A"), problem.customer("B"));
        assertThat(PlanInvariants.referenceCost(plans.getFirst())).isEqualTo(30);
        // The same geometry with the codes swapped must give the same route: the orientation used to be decided by
        // the code tie-break between two equal savings, which only happened to favour the near customer here.
        assertThat(route(null, List.of(TestNode.customer("A", 0, 30, 1), TestNode.customer("B", 0, 10, 1)),
                List.of(new TestTrackable("T1", 5))))
                .containsExactly("0,0", "0,10", "0,30");
    }

    @Test
    void anOpenRouteIsOrientedByGeometryNotByCustomerCodes() {
        // Open routes have no return leg, so chaining i before j saves c(O,j) - c(i,j): near-then-far wins. The old
        // formula always added the return leg c(i,O), which made both orientations equal and left the choice to the
        // codes (A(0,10), B(0,5) became O-A-B at cost 15 instead of O-B-A at cost 10).
        List<TestTrackable> fleet = List.of(new TestTrackable("T1", 5));

        assertThat(route(null, List.of(TestNode.customer("A", 0, 5, 1), TestNode.customer("B", 0, 10, 1)), fleet))
                .containsExactly("0,0", "0,5", "0,10");
        assertThat(route(null, List.of(TestNode.customer("A", 0, 10, 1), TestNode.customer("B", 0, 5, 1)), fleet))
                .containsExactly("0,0", "0,5", "0,10");
    }

    @Test
    void aSeparateDestinationDepotDecidesTheOrientation() {
        // The route ends at D(0,10), not back at O: chaining i before j saves c(i,D) + c(O,j) - c(i,j), so the
        // customer near O comes first (O-B-A-D = 10). The return-to-origin formula gave both orientations the same
        // saving and the codes picked O-A-B-D = 22.
        TestNode destination = TestNode.destination("D", 0, 10);
        List<TestTrackable> fleet = List.of(new TestTrackable("T1", 5));

        assertThat(route(destination, List.of(TestNode.customer("A", 0, 8, 1), TestNode.customer("B", 0, 2, 1)), fleet))
                .containsExactly("0,0", "0,2", "0,8", "0,10");
        assertThat(route(destination, List.of(TestNode.customer("A", 0, 2, 1), TestNode.customer("B", 0, 8, 1)), fleet))
                .containsExactly("0,0", "0,2", "0,8", "0,10");
    }

    @Test
    void theBetaBlendKeepsTheBetterOrientationWhateverTheCodes() {
        // Travelling "down" (decreasing y) costs double. With beta = 1 both orientations get the same blended saving,
        // and the code tie-break used to pick the orientation; the better plain saving must win instead, giving the
        // open route O-P-Q (30) rather than O-Q-P (70) for either naming of the near customer.
        for (String nearCode : List.of("P", "Z")) {
            TestNode o = TestNode.origin("O", 0, 0);
            TestNode near = TestNode.customer(nearCode, 0, 10, 1);
            TestNode far = TestNode.customer("Q", 0, 30, 1);
            PathCostCalculation<TestPlan, TestNode, Long> calculation = new PathCostCalculation<>(
                    OptimizationType.NO_RESTRICTION, Map.of()) {
                @Override
                protected Long[][] getCostMatrix(List<TestNode> nodes) {
                    Long[][] matrix = new Long[nodes.size()][nodes.size()];
                    for (int r = 0; r < nodes.size(); r++) {
                        for (int c = 0; c < nodes.size(); c++) {
                            long base = nodes.get(r).manhattanTo(nodes.get(c));
                            matrix[r][c] = nodes.get(c).getY() < nodes.get(r).getY() ? 2 * base : base;
                        }
                    }
                    return matrix;
                }
            };
            calculation.initializeCostMatrix(List.of(o, near, far));
            TestClarkeWright cw = new TestClarkeWright(calculation, new TestAssignment(List.of(new TestTrackable("T",
                    9))),
                    new NoOpLocalSearch(), null, OptimizationType.NO_RESTRICTION, List.of(0.5), List.of(1.0));

            List<TestPlan> plans = cw.plan(o, List.of(near, far));

            assertThat(plans).as("near customer %s", nearCode).hasSize(1);
            assertThat(plans.getFirst().getNodes()).as("near customer %s", nearCode).containsExactly(o, near, far);
        }
    }

    // ---------------------------------------------------------------- open plans (no destination)

    @Test
    void noCustomersYieldsNoPlans() {
        Problems.Problem problem = Problems.noCustomers();
        MatrixCostCalculation calculation = problem.calculation();
        TestClarkeWright cw = clarkeWright(problem, calculation, problem.trackables());

        assertThat(cw.plan(problem.origin(), problem.customers())).isEmpty();
    }

    @Test
    void mergesOpenPlansThatHaveNoDestinationNode() {
        // Regression: Planable's contract explicitly allows an open plan to omit the destination, and the merge
        // core used to append firstPlan.getDestination() unconditionally — inserting a null node into the
        // merged route, which blew up on the very next feasibility/cost query.
        MatrixCostCalculation calculation = open.calculation();
        TestClarkeWright cw = clarkeWright(open, calculation, List.of(new TestTrackable("T1", 10)));

        List<TestPlan> plans = cw.plan(open.origin(), open.customers());

        assertThat(plans).hasSize(1);
        assertThat(plans.getFirst().getNodes()).doesNotContainNull();
        assertThat(plans.getFirst().getNodes()).containsExactly(
                open.origin(), open.customer("A"), open.customer("B"), open.customer("C"));
        assertThat(PlanInvariants.referenceCost(plans.getFirst())).isEqualTo(30);
        PlanInvariants.assertNodeLayout(plans);
        // Reversing the codes along the line must not change the route (it used to give O-C-B-A... orders whenever
        // the codes ran against the geometry, since the savings of both orientations were equal).
        assertThat(route(null, List.of(TestNode.customer("C", 0, 10, 1), TestNode.customer("B", 0, 20, 1),
                TestNode.customer("A", 0, 30, 1)), List.of(new TestTrackable("T1", 10))))
                .containsExactly("0,0", "0,10", "0,20", "0,30");
    }

    @Test
    void plansComparedByCodeKeepEveryCustomerThroughTheMerges() {
        // A consumer whose plans are equal by code alone (Planable#getCode() is documented as the plan's identity):
        // the merged plan carries the first plan's code, which used to make the merger discard it whenever a
        // customer was appended to the route that already held the vehicle. With Z sorting last, B-C merges
        // first and Z is then appended behind C.
        TestNode z = TestNode.customer("Z", 0, 10, 1);
        List<TestNode> customers = List.of(z, closed.customer("B"), closed.customer("C"));
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(closed.origin(), z, closed.customer("B"), closed.customer("C"),
                closed.destination()));
        TestClarkeWright cw = clarkeWright(closed, calculation, List.of(new TestTrackable("T1", 10)))
                .withPlanEquality(TestPlan.Equality.CODE);

        List<TestPlan> plans = cw.plan(closed.origin(), customers);

        PlanInvariants.assertValidSolution(plans, closed.origin(), closed.destination(), customers);
        assertThat(plans).hasSize(1);
    }

    @Test
    void routesWithoutAnOriginStopAreMergedWithoutAStartLegAndWithoutANullNode() {
        // Seed routes [customer, D] have no origin stop: the merge appended getOrigin() (null) to the merged route, and
        // the saving still credited a c(O,j) start leg that such a route never drives. With D(0,0), A(0,10) and
        // B(0,20) the right route is B-A-D (20), not A-B-D (30) — for either naming of the two customers.
        TestNode destination = TestNode.destination("D", 0, 0);
        for (List<TestNode> customers : List.of(
                List.of(TestNode.customer("A", 0, 10, 1), TestNode.customer("B", 0, 20, 1)),
                List.of(TestNode.customer("B", 0, 10, 1), TestNode.customer("A", 0, 20, 1)))) {
            MatrixCostCalculation calculation = new MatrixCostCalculation();
            calculation.initializeCostMatrix(List.of(closed.origin(), customers.get(0), customers.get(1), destination));
            for (OptimizationType type : OptimizationType.values()) {
                TestClarkeWright cw = new TestClarkeWright(calculation,
                        new TestAssignment(List.of(new TestTrackable("T1", 5))),
                        new RecostingLocalSearch(calculation), destination, type).withoutOriginStops();

                List<TestPlan> plans = cw.plan(closed.origin(), customers);

                assertThat(plans).as("%s", type).hasSize(1);
                assertThat(plans.getFirst().getNodes()).as("%s", type)
                        .containsExactly(customers.get(1), customers.get(0), destination);
            }
        }
    }

    @Test
    void mergingRoutesOfDifferentShapesKeepsEveryDepotStop() {
        // An open seed [O, c1] chained with a destination-only seed [c2, D] took the destination of the first route
        // (none), so D was dropped; in the other orientation the origin went too.
        TestNode origin = TestNode.origin("O", 0, 0);
        TestNode destination = TestNode.destination("D", 0, 30);
        TestNode c1 = TestNode.customer("c1", 0, 10, 1);
        TestNode c2 = TestNode.customer("c2", 0, 20, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, c1, c2, destination));
        TestClarkeWright cw = new TestClarkeWright(calculation, new TestAssignment(List.of(new TestTrackable("T", 9))),
                new NoOpLocalSearch(), null, OptimizationType.NO_RESTRICTION);

        List<TestPlan> forward = cw.merge(new ArrayList<>(List.of(new TestPlan("a", List.of(origin, c1)),
                new TestPlan("b", List.of(c2, destination)))), new ArrayList<>(List.of(new Saving(c1, c2, 1))));
        List<TestPlan> backward = cw.merge(new ArrayList<>(List.of(new TestPlan("a", List.of(origin, c1)),
                new TestPlan("b", List.of(c2, destination)))), new ArrayList<>(List.of(new Saving(c2, c1, 1))));

        assertThat(forward).extracting(TestPlan::getNodes).containsExactly(List.of(origin, c1, c2, destination));
        assertThat(backward).extracting(TestPlan::getNodes).containsExactly(List.of(origin, c2, c1, destination));
    }

    @Test
    void anUnassignedSeedWithoutADestinationIsAClosedTourUnlessNoVehicleReturns() {
        // Closedness comes from the resource (Planable#isClosed). Seeds are not assigned yet, so a returning fleet's
        // seeds without a destination stop got the open formula; the fleet decides for them: closed back to the
        // origin when any vehicle returns, open only when none does.
        TestNode origin = TestNode.origin("O", 0, 0);
        TestNode i = TestNode.customer("i", 10, 0, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, i));
        TestClarkeWright returning = seedJudge(calculation, true, true);
        List<TestPlan> seeds = returning.initialPlans(origin, List.of(i));

        assertThat(returning.routeEnds(origin, seeds).apply(i)).as("every vehicle returns").isEqualTo(origin);
        assertThat(seedJudge(calculation, true, false).routeEnds(origin, seeds).apply(i)).as("one vehicle returns")
                .isEqualTo(origin);
        assertThat(seedJudge(calculation, false, false).routeEnds(origin, seeds).apply(i)).as("no vehicle returns")
                .isNull();
    }

    // ---------------------------------------------------------------- previous plans

    @Test
    void customersAlreadyOnPreviousPlansAreNotPlannedTwice() {
        MatrixCostCalculation calculation = closed.calculation();
        TestClarkeWright cw = clarkeWright(closed, calculation,
                List.of(new TestTrackable("T1", 10), new TestTrackable("T2", 10)));

        TestPlan previous = closed.plan("previous", closed.customer("A"));
        previous.setTrackable(new TestTrackable("T2", 10));
        calculation.calculateCost(previous);

        List<TestPlan> plans = cw.plan(closed.origin(), closed.customers(), previous);

        // The previous plan comes back with its route untouched (it is carried through the sweep as a copy,
        // and its resource may be re-picked from the pool), and its customer is never planned a second time.
        assertThat(plans).anySatisfy(plan -> {
            assertThat(plan.getCode()).isEqualTo("previous");
            assertThat(plan.getNodes()).isEqualTo(previous.getNodes());
        });
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(plans, closed.customers());
        PlanInvariants.assertTrackablesAreDistinct(plans);
        assertThat(plans.stream().filter(p -> !p.getCode().equals("previous")))
                .as("A stays on the previous plan, so the new plans only cover B and C")
                .allSatisfy(plan -> assertThat(plan.getCustomers()).doesNotContain(closed.customer("A")));
    }

    @Test
    void aPreviousPlanThatCannotBeReassignedIsReturnedWithItsCustomers() {
        // The single vehicle is taken by the new customers; the previous plan came without a resource and none is
        // left for it. It used to be dropped in the final assignment pass together with its customer A.
        MatrixCostCalculation calculation = closed.calculation();
        TestClarkeWright cw = clarkeWright(closed, calculation, List.of(new TestTrackable("T1", 9)));
        TestPlan previous = closed.plan("previous", closed.customer("A"));

        List<TestPlan> plans = cw.plan(closed.origin(), List.of(closed.customer("B"), closed.customer("C")), previous);

        PlanInvariants.assertAllCustomersVisitedExactlyOnce(plans, closed.customers());
        assertThat(plans).anySatisfy(plan -> {
            assertThat(plan.getCode()).isEqualTo("previous");
            assertThat(plan.getNodes()).isEqualTo(previous.getNodes());
            assertThat(plan.getTrackable()).as("it keeps the resource it came with").isNull();
        });
        PlanInvariants.assertTrackablesAreDistinct(plans);
    }

    @Test
    void aPreviousPlanWithoutCustomersIsCarriedThrough() {
        // The merge core read the first customer of every plan and threw NoSuchElementException on an empty one.
        MatrixCostCalculation calculation = closed.calculation();
        TestClarkeWright cw = clarkeWright(closed, calculation,
                List.of(new TestTrackable("T1", 9), new TestTrackable("T2", 9)));
        TestPlan empty = new TestPlan("empty", List.of(closed.origin(), closed.destination()));
        empty.setTrackable(new TestTrackable("T2", 9));

        List<TestPlan> plans = cw.plan(closed.origin(), List.of(closed.customer("A"), closed.customer("B")), empty);

        assertThat(plans).extracting(TestPlan::getCode).contains("empty");
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(plans, List.of(closed.customer("A"), closed.customer("B")));
        PlanInvariants.assertTrackablesAreDistinct(plans);
    }

    @Test
    void aPreviousPlanWithoutCustomersNeverTakesAVehicleFromARealPlan() {
        // Two vehicles; i and j merge, k needs the second one. The empty previous plan (cost 0) sorted first in the
        // final assignment pass and took it, so k was dropped.
        TestNode origin = TestNode.origin("O", 0, 0);
        TestNode i = TestNode.customer("i", 10, 0, 1);
        TestNode j = TestNode.customer("j", 10, 2, 1);
        TestNode k = TestNode.customer("k", -5, 0, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, i, j, k));
        TestClarkeWright cw = new TestClarkeWright(calculation, new TestAssignment(List.of(
                new TestTrackable("T", 2, 2, false))), new NoOpLocalSearch(), null, OptimizationType.NO_RESTRICTION);
        TestPlan shell = new TestPlan("shell", List.of(origin));

        List<TestPlan> plans = cw.plan(origin, List.of(i, j, k), shell);

        PlanInvariants.assertAllCustomersVisitedExactlyOnce(plans, List.of(i, j, k));
        PlanInvariants.assertTrackablesAreDistinct(plans);
        assertThat(plans).filteredOn(plan -> plan.getCode().equals("shell")).extracting(TestPlan::getNodes)
                .containsExactly(List.of(origin));
    }

    // ---------------------------------------------------------------- fleet limits

    @Test
    void plansThatNoVehicleCanServeAreDropped() {
        Problems.Problem problem = Problems.infeasible();   // three customers, a single capacity-1 vehicle
        MatrixCostCalculation calculation = problem.calculation();
        TestClarkeWright cw = clarkeWright(problem, calculation, problem.trackables());

        List<TestPlan> plans = cw.plan(problem.origin(), problem.customers());

        // Exactly one single-customer plan can be resourced; the rest are silently dropped rather than
        // returned unassigned. Whatever survives must still be a well-formed, resourced plan.
        assertThat(plans).hasSize(1);
        PlanInvariants.assertEveryPlanHasTrackable(plans);
        PlanInvariants.assertNoDuplicateCustomers(plans);
        assertThat(PlanInvariants.customersOf(plans)).hasSize(1);
    }

    @Test
    void everyCustomerIsServedWhenTheFleetIsLargeEnough() {
        Problems.Problem problem = Problems.capacityConstrained();
        MatrixCostCalculation calculation = problem.calculation();
        TestClarkeWright cw = clarkeWright(problem, calculation, problem.trackables());

        List<TestPlan> plans = cw.plan(problem.origin(), problem.customers());

        PlanInvariants.assertValidSolution(plans, problem.origin(), problem.destination(), problem.customers());
        PlanInvariants.assertEveryPlanHasTrackable(plans);
        PlanInvariants.assertCustomerCountWithinCapacity(plans);
    }

    // ---------------------------------------------------------------- degenerate geometry

    @Test
    void handlesCustomersSharingOneLocationAndSittingOnTheDepot() {
        for (Problems.Problem problem : List.of(Problems.identicalLocations(), Problems.customersAtDepot())) {
            MatrixCostCalculation calculation = problem.calculation();
            TestClarkeWright cw = clarkeWright(problem, calculation, problem.trackables());

            List<TestPlan> plans = cw.plan(problem.origin(), problem.customers());

            PlanInvariants.assertValidSolution(plans, problem.origin(), problem.destination(), problem.customers());
            assertThat(PlanInvariants.referenceTotalCost(plans))
                    .as("%s: zero-length inter-customer edges cannot add cost", problem.name())
                    .isEqualTo(problem.customers().isEmpty() ? 0
                            : PlanInvariants.edgeCost(problem.origin(), problem.customers().getFirst()));
        }
    }

    @Test
    void veryLargeCoordinatesDoNotOverflowTheCostModel() {
        Problems.Problem problem = Problems.largeCoordinates();
        MatrixCostCalculation calculation = problem.calculation();
        TestClarkeWright cw = clarkeWright(problem, calculation, problem.trackables());

        List<TestPlan> plans = cw.plan(problem.origin(), problem.customers());

        PlanInvariants.assertValidSolution(plans, problem.origin(), problem.destination(), problem.customers());
        assertThat(calculation.isCompactStorage()).as("every single edge still fits an int cell").isTrue();
        long total = plans.stream().mapToLong(calculation::calculateCost).sum();
        assertThat(total)
                .as("the library's own route sums must equal the independent long sum, beyond Integer.MAX_VALUE")
                .isEqualTo(PlanInvariants.referenceTotalCost(plans))
                .isGreaterThan(Integer.MAX_VALUE);
    }

    @Test
    void negativeCoordinatesAreSupported() {
        Problems.Problem problem = Problems.negativeCoordinates();
        MatrixCostCalculation calculation = problem.calculation();
        TestClarkeWright cw = clarkeWright(problem, calculation, problem.trackables());

        List<TestPlan> plans = cw.plan(problem.origin(), problem.customers());

        PlanInvariants.assertValidSolution(plans, problem.origin(), problem.destination(), problem.customers());
        PlanInvariants.assertLibraryCostMatchesIndependentCost(plans, calculation::calculateCost);
    }

    // ---------------------------------------------------------------- parameter sweep

    @Test
    void lambdaSweepKeepsTheStrongestPlanSet() {
        MatrixCostCalculation calculation = closed.calculation();
        // λ < 1 discourages long merges, λ > 1 encourages them; sweeping must never return a worse set than
        // the best single λ, because getBestPlans picks the winner under plansComparator.
        TestClarkeWright swept = new TestClarkeWright(calculation, new TestAssignment(List.of(new TestTrackable("T1",
                10))),
                new RecostingLocalSearch(calculation), closed.destination(), OptimizationType.NO_RESTRICTION,
                List.of(0.1, 1.0, 2.0));

        List<TestPlan> plans = swept.plan(closed.origin(), closed.customers());

        PlanInvariants.assertValidSolution(plans, closed.origin(), closed.destination(), closed.customers());
        assertThat(PlanInvariants.referenceTotalCost(plans)).isEqualTo(60);
    }

    @Test
    void betaGridIsSweptAsWell() {
        MatrixCostCalculation calculation = closed.calculation();
        TestClarkeWright swept = new TestClarkeWright(calculation, new TestAssignment(List.of(new TestTrackable("T1",
                10))),
                new RecostingLocalSearch(calculation), closed.destination(), OptimizationType.NO_RESTRICTION,
                List.of(0.5, 1.0), List.of(0.25, 0.75));

        List<TestPlan> plans = swept.plan(closed.origin(), closed.customers());

        PlanInvariants.assertValidSolution(plans, closed.origin(), closed.destination(), closed.customers());
    }

    @Test
    void plansComparatorPrefersMoreCustomersThenLowerCost() {
        MatrixCostCalculation calculation = closed.calculation();
        TestClarkeWright cw = clarkeWright(closed, calculation, closed.trackables());

        // Real geometry rather than injected numbers: O-A-D costs 20 with one customer served, while the two
        // three-customer routes cost 60 (O-A-B-C-D) and 80 (O-B-A-C-D).
        TestPlan cheapButPartial = closed.plan("partial", closed.customer("A"));
        TestPlan expensiveButComplete = closed.plan("complete",
                closed.customer("B"), closed.customer("A"), closed.customer("C"));
        TestPlan sameCoverageCheaper = closed.plan("cheaper",
                closed.customer("A"), closed.customer("B"), closed.customer("C"));
        assertThat(cheapButPartial.getCost()).isEqualTo(20);
        assertThat(expensiveButComplete.getCost()).isEqualTo(80);
        assertThat(sameCoverageCheaper.getCost()).isEqualTo(60);

        // Negative means "the first argument wins".
        assertThat(cw.comparatorOfPlans().compare(List.of(expensiveButComplete), List.of(cheapButPartial)))
                .as("serving more customers beats being cheap").isNegative();
        assertThat(cw.comparatorOfPlans().compare(List.of(sameCoverageCheaper), List.of(expensiveButComplete)))
                .as("equal coverage is broken by cost").isNegative();
        List<TestPlan> left = List.of(sameCoverageCheaper);
        List<TestPlan> right = List.of(sameCoverageCheaper);
        assertThat(cw.comparatorOfPlans().compare(left, right))
                .isZero();
    }

    // ---------------------------------------------------------------- zones

    @Test
    void savingsNeverBridgeTwoZonesUnderLocationZone() {
        Problems.Problem problem = Problems.zoned();
        MatrixCostCalculation calculation = problem.calculation(OptimizationType.LOCATION_ZONE,
                Problems.zonedNeighbors());
        TestClarkeWright cw = new TestClarkeWright(calculation, new TestAssignment(problem.trackables()),
                new RecostingLocalSearch(calculation), problem.destination(), OptimizationType.LOCATION_ZONE);

        List<Saving> savings = cw.savings(problem.origin(), problem.customers(), 1.0, 0);

        assertThat(savings).as("A(Z1), B(Z2) and C(Z3) are each alone in their zone, so no pair is formed")
                .isEmpty();
    }

    @Test
    void locationZonePlansEachZoneIndependently() {
        // Two customers in Z1 and two in Z2: merges may happen inside a zone but never across.
        TestNode origin = TestNode.origin("O", 0, 0);
        List<TestNode> customers = List.of(
                TestNode.customer("A1", "Z1", 0, 10, 1), TestNode.customer("A2", "Z1", 0, 20, 1),
                TestNode.customer("B1", "Z2", 100, 10, 1), TestNode.customer("B2", "Z2", 100, 20, 1));
        List<TestNode> all = new ArrayList<>();
        all.add(origin);
        all.addAll(customers);
        MatrixCostCalculation calculation = new MatrixCostCalculation(OptimizationType.LOCATION_ZONE,
                Map.of("Z1", List.of("Z1"), "Z2", List.of("Z2")));
        calculation.initializeCostMatrix(all);
        TestClarkeWright cw = new TestClarkeWright(calculation,
                new TestAssignment(List.of(new TestTrackable("T1", 10), new TestTrackable("T2", 10))),
                new RecostingLocalSearch(calculation), null, OptimizationType.LOCATION_ZONE);

        List<TestPlan> plans = cw.plan(origin, customers);

        PlanInvariants.assertAllCustomersVisitedExactlyOnce(plans, customers);
        assertThat(plans).allSatisfy(plan -> assertThat(plan.getCustomers().stream()
                .map(TestNode::getZone).distinct())
                .as("plan %s never mixes zones", plan.getCode()).hasSize(1));
    }

    @Test
    void aNullZoneUnderLocationZoneIsPlannedLikeAnyOtherZone() {
        // Grouping customers (and previous plans) by zone threw NullPointerException for a null zone, which
        // PathCostCalculation otherwise treats as "unrestricted".
        TestNode noZone = new TestNode("N", TestNode.Type.CUSTOMER, null, 0, 5, 1);
        TestNode a = TestNode.customer("A", 0, 10, 1);
        TestNode b = TestNode.customer("B", 0, 20, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation(OptimizationType.LOCATION_ZONE, Map.of());
        calculation.initializeCostMatrix(List.of(closed.origin(), noZone, a, b, closed.destination()));
        TestClarkeWright cw = new TestClarkeWright(calculation,
                new TestAssignment(List.of(new TestTrackable("T1", 9), new TestTrackable("T2", 9),
                        new TestTrackable("T3", 9))),
                new RecostingLocalSearch(calculation), closed.destination(), OptimizationType.LOCATION_ZONE);
        TestPlan previous = new TestPlan("previous", List.of(closed.origin(), b, closed.destination()), null);
        previous.setTrackable(new TestTrackable("T2", 9));

        List<TestPlan> plans = cw.plan(closed.origin(), List.of(noZone, a), previous);

        PlanInvariants.assertAllCustomersVisitedExactlyOnce(plans, List.of(noZone, a, b));
        PlanInvariants.assertTrackablesAreDistinct(plans);
    }

    @Test
    void aCustomerListWithTwoCustomersSharingACodeIsRejected() {
        // Construction used to keep one of the two silently while insertion placed both.
        MatrixCostCalculation calculation = closed.calculation();
        TestClarkeWright cw = clarkeWright(closed, calculation, closed.trackables());
        List<TestNode> customers = List.of(closed.customer("A"), closed.customer("A").copy(), closed.customer("B"));

        assertThatThrownBy(() -> cw.plan(closed.origin(), customers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate customer code: A");
    }

    @Test
    void anInterruptedConstructionStillPlansEveryCustomer() {
        // Construction always completes: twelve customers and three vehicles. Stopping the merge walk on interrupt left
        // twelve single-customer plans for three vehicles, and the final assignment dropped nine customers.
        TestNode origin = TestNode.origin("O", 0, 0);
        List<TestNode> customers = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            customers.add(TestNode.customer("C" + i, 10 * (i % 4) + 10, 10 * (i / 4), 1));
        }
        List<TestNode> all = new ArrayList<>(List.of(origin));
        all.addAll(customers);
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(all);
        List<TestTrackable> fleet = List.of(new TestTrackable("T1", 4), new TestTrackable("T2", 4),
                new TestTrackable("T3", 4));
        TestClarkeWright cw = new TestClarkeWright(calculation, new TestAssignment(fleet),
                new RecostingLocalSearch(calculation), null, OptimizationType.NO_RESTRICTION);

        cw.interrupt();
        List<TestPlan> plans = cw.plan(origin, customers);

        PlanInvariants.assertAllCustomersVisitedExactlyOnce(plans, customers);
        PlanInvariants.assertEveryPlanHasTrackable(plans);
        PlanInvariants.assertTrackablesAreDistinct(plans);
    }

    @Test
    void cancelAndInterruptReachTheNestedLocalSearch() {
        MatrixCostCalculation calculation = closed.calculation();
        CancelSpyLocalSearch cancelled = new CancelSpyLocalSearch();
        CancelSpyLocalSearch interrupted = new CancelSpyLocalSearch();

        new TestClarkeWright(calculation, new TestAssignment(closed.trackables()), cancelled, closed.destination())
                .cancel();
        new TestClarkeWright(calculation, new TestAssignment(closed.trackables()), interrupted, closed.destination())
                .interrupt();

        assertThat(cancelled.isCancelled()).isTrue();
        assertThat(interrupted.isInterrupted()).isTrue();
    }

    // ---------------------------------------------------------------- contract / robustness

    @Test
    void doesNotMutateTheCallersCustomerList() {
        MatrixCostCalculation calculation = closed.calculation();
        TestClarkeWright cw = clarkeWright(closed, calculation, closed.trackables());
        List<TestNode> customers = new ArrayList<>(closed.customers());
        List<TestNode> snapshot = List.copyOf(customers);

        cw.plan(closed.origin(), customers);

        assertThat(customers).isEqualTo(snapshot);
    }

    @Test
    void isDeterministicAcrossRuns() {
        MatrixCostCalculation calculation = closed.calculation();
        List<TestTrackable> fleet = List.of(new TestTrackable("T1", 2), new TestTrackable("T2", 2));

        List<TestPlan> first = clarkeWright(closed, calculation, fleet).plan(closed.origin(), closed.customers());
        List<TestPlan> second = clarkeWright(closed, calculation, fleet).plan(closed.origin(), closed.customers());

        assertThat(first).isEqualTo(second);
    }

    @Test
    void seedRoutesWithoutADestinationStopAreClosedWhenTheFleetReturns() {
        // Seed plans have no resource yet when the savings are computed. Whether their routes close is then decided
        // by the fleet: a returning fleet gives the classic closed-tour saving c(A,O) + c(O,B) - c(A,B) = 20 in both
        // orientations; a fleet that never returns leaves the routes open, c(O,B) - c(A,B) = 10 and c(O,A) - c(B,A) =
        // 0.
        TestNode origin = TestNode.origin("O", 0, 0);
        TestNode a = TestNode.customer("A", 0, 10, 1);
        TestNode b = TestNode.customer("B", 0, 20, 1);
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, a, b));
        for (boolean returns : new boolean[] {true, false}) {
            TestClarkeWright cw = new TestClarkeWright(calculation,
                    new TestAssignment(List.of(new TestTrackable("T", 5, 1,
                            returns))), new RecostingLocalSearch(calculation),
                    null, OptimizationType.NO_RESTRICTION);
            List<TestPlan> seeds = cw.initialPlans(origin, List.of(a, b));

            assertThat(seeds).allSatisfy(seed -> assertThat(cw.closedRoute(seed)).isEqualTo(returns));
            List<Saving> savings = cw.savingsForPlans(origin, List.of(a, b), seeds, 1.0);
            assertThat(savings).extracting(s -> s.origin().getCode() + s.destination().getCode() + "=" + s.value())
                    .as("returns=%s", returns)
                    .containsExactly(returns ? new String[] {"AB=20.0", "BA=20.0"} : new String[] {"AB=10.0",
                            "BA=0.0"});
        }
    }

    @Test
    void cancelAbortsTheMergeLoop() {
        MatrixCostCalculation calculation = closed.calculation();
        TestClarkeWright cw = clarkeWright(closed, calculation, closed.trackables());
        List<TestPlan> initial = new ArrayList<>(cw.initialPlans(closed.origin(), closed.customers()));
        List<Saving> savings = cw.savings(closed.origin(), closed.customers(), 1.0, 0);

        cw.cancel();

        assertThatThrownBy(() -> cw.merge(initial, savings))
                .isInstanceOf(CancelException.class)
                .hasMessageContaining("cancelled");
    }

    @Test
    void initialPlansAreOneRoundTripPerCustomer() {
        MatrixCostCalculation calculation = closed.calculation();
        TestClarkeWright cw = clarkeWright(closed, calculation, closed.trackables());

        List<TestPlan> initial = cw.initialPlans(closed.origin(), closed.customers());

        assertThat(initial).hasSameSizeAs(closed.customers());
        assertThat(initial).allSatisfy(plan -> assertThat(plan.getCustomerCount()).isEqualTo(1));
        PlanInvariants.assertNodeLayout(initial);
        PlanInvariants.assertAllCustomersVisitedExactlyOnce(initial, closed.customers());
    }

    @Test
    void constructorRejectsAnEmptyOrNonFiniteParameterGrid() {
        MatrixCostCalculation calculation = closed.calculation();
        TestAssignment assignment = new TestAssignment(List.of(new TestTrackable("T1", 10)));

        assertThatThrownBy(() -> new TestClarkeWright(calculation, assignment, new RecostingLocalSearch(calculation),
                closed.destination(), OptimizationType.NO_RESTRICTION, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lambda");
        assertThatThrownBy(() -> new TestClarkeWright(calculation, assignment, new RecostingLocalSearch(calculation),
                closed.destination(), OptimizationType.NO_RESTRICTION, List.of(1.0), List.of(Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Beta");
        assertThatThrownBy(() -> new TestClarkeWright(calculation, null, new RecostingLocalSearch(calculation),
                closed.destination(), OptimizationType.NO_RESTRICTION))
                .isInstanceOf(NullPointerException.class);
    }

}
