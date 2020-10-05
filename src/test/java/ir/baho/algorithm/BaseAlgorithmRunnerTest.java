package ir.baho.algorithm;

import ir.baho.algorithm.domain.PlanModifier;
import ir.baho.algorithm.generator.DateTimePlanCodeGenerator;
import ir.baho.algorithm.support.AlwaysFeasibleChecker;
import ir.baho.algorithm.support.CancelSpyLocalSearch;
import ir.baho.algorithm.support.MatrixCostCalculation;
import ir.baho.algorithm.support.TestInsertion;
import ir.baho.algorithm.support.TestNode;
import ir.baho.algorithm.support.TestPlan;
import ir.baho.algorithm.support.TestRunner;
import ir.baho.algorithm.support.TestTwoOpt;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code BaseAlgorithmRunner} is the skeleton a host application subclasses. Its most failure-prone
 * responsibility is the {@code algorithms()} list: that enumeration is the <em>only</em> thing that makes
 * {@code cancel()} and {@code interrupt()} reach the owned algorithms, so a newly added algorithm that is
 * missing from it silently ignores both stop signals. These tests pin that wiring, the cost-matrix
 * initialization, the plan-modifier finalization, and the wall-clock interrupt budget.
 */
class BaseAlgorithmRunnerTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode customerA = TestNode.customer("A", 0, 10, 1);
    private final TestNode customerB = TestNode.customer("B", 0, 20, 1);
    private final TestNode destination = TestNode.destination("D", 0, 0);

    @SafeVarargs
    // Collections.addAll would trip javac's varargs lint
    @SuppressWarnings({"ManualArrayToCollectionCopy", "UseBulkOperation"})
    private TestRunner runner(List<BaseAlgorithm> algorithms, PlanModifier<TestPlan>... modifiers) {
        List<PlanModifier<TestPlan>> list = new ArrayList<>();
        for (PlanModifier<TestPlan> modifier : modifiers) {
            list.add(modifier);
        }
        return new TestRunner(new MatrixCostCalculation(), new DateTimePlanCodeGenerator(), algorithms, list);
    }

    // ---------------------------------------------------------------- initialization

    @Test
    void initializationCoversTheDepotEveryCustomerAndTheExtraNodes() {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        TestRunner runner = new TestRunner(calculation, new DateTimePlanCodeGenerator(), List.of(), List.of());

        runner.initializeMatrix(origin, List.of(customerA, customerB), destination);

        // Any pair queried afterwards must resolve, including the extra (destination) node.
        assertThat(calculation.calculateCost(origin, customerA)).isEqualTo(10);
        assertThat(calculation.calculateCost(customerB, destination)).isEqualTo(20);
        assertThat(calculation.getClosestNodes("A")).hasSize(4);
    }

    @Test
    void queryingCostsBeforeInitializationIsRejected() {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        new TestRunner(calculation, new DateTimePlanCodeGenerator(), List.of(), List.of());

        assertThatThrownBy(() -> calculation.calculateCost(origin, customerA))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------------------------------------------------------------- planning and finalization

    @Test
    void planningInitializesCostsAndAppliesEveryModifierInOrder() {
        List<String> applied = new ArrayList<>();
        PlanModifier<TestPlan> first = plan -> applied.add("first:" + plan.getCustomerCount());
        PlanModifier<TestPlan> second = plan -> applied.add("second:" + plan.getCustomerCount());
        TestRunner runner = runner(List.of(), first, second);

        runner.plan(origin, List.of(customerA, customerB));

        assertThat(runner.getPlans()).hasSize(1);
        assertThat(runner.getPlans().get(0).getCost()).isEqualTo(20);
        assertThat(applied).containsExactly("first:2", "second:2");
    }

    @Test
    void generatedPlanCodesAreUsedForTheProducedPlans() {
        TestRunner runner = runner(List.of());

        runner.plan(origin, List.of(customerA));

        assertThat(runner.getPlans().get(0).getCode()).hasSize(25).containsOnlyDigits();
    }

    @Test
    void finalizationToleratesAnEmptyPlanList() {
        List<String> applied = new ArrayList<>();
        TestRunner runner = runner(List.of(), plan -> applied.add(plan.getCode()));

        runner.finalizePlans(new ArrayList<>());

        assertThat(applied).isEmpty();
    }

    // ---------------------------------------------------------------- unplanned nodes

    @Test
    void unplannedNodesAreTheCustomersNoPlanServes() {
        TestRunner runner = runner(List.of());
        TestPlan plan = new TestPlan("p", List.of(origin, customerA));

        assertThat(runner.unplannedNodes(List.of(plan), List.of(customerA, customerB)))
                .containsExactly(customerB);
        assertThat(runner.unplannedNodes(List.of(plan), List.of(customerA))).isEmpty();
        assertThat(runner.unplannedNodes(List.of(), List.of(customerA, customerB)))
                .containsExactly(customerA, customerB);
    }

    @Test
    void computingUnplannedNodesDoesNotMutateTheCallersCustomerList() {
        TestRunner runner = runner(List.of());
        List<TestNode> customers = new ArrayList<>(List.of(customerA, customerB));
        TestPlan plan = new TestPlan("p", List.of(origin, customerA));

        runner.unplannedNodes(List.of(plan), customers);

        assertThat(customers).containsExactly(customerA, customerB);
    }

    // ---------------------------------------------------------------- stop signals

    @Test
    void cancelReachesEveryEnumeratedAlgorithm() {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        CancelSpyLocalSearch localSearch = new CancelSpyLocalSearch();
        TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), false);
        TestRunner runner = new TestRunner(calculation, new DateTimePlanCodeGenerator(),
                List.of(localSearch, insertion), List.of());

        runner.cancel();

        assertThat(localSearch.isCancelled()).isTrue();
        calculation.initializeCostMatrix(List.of(origin, customerA, customerB));
        assertThatThrownBy(() -> insertion.insert(
                new ArrayList<>(List.of(new TestPlan("p", List.of(origin, customerA)))), List.of(customerB)))
                .isInstanceOf(ir.baho.algorithm.exception.CancelException.class);
    }

    @Test
    void interruptReachesEveryEnumeratedAlgorithmAndIsObservable() {
        CancelSpyLocalSearch localSearch = new CancelSpyLocalSearch();
        TestRunner runner = runner(List.of(localSearch));

        assertThat(runner.interrupted()).isFalse();
        runner.interrupt();

        assertThat(localSearch.isInterrupted()).isTrue();
        assertThat(runner.interrupted()).isTrue();
    }

    @Test
    void anAlgorithmMissingFromTheEnumerationNeverSeesTheSignal() {
        // The failure mode the class Javadoc warns about, pinned so the wiring requirement stays visible.
        CancelSpyLocalSearch enumerated = new CancelSpyLocalSearch();
        TestRunner runner = runner(List.of(enumerated));

        runner.cancel();
        runner.interrupt();

        assertThat(enumerated.isCancelled()).isTrue();
        CancelSpyLocalSearch forgotten = new CancelSpyLocalSearch();
        assertThat(forgotten.isCancelled()).as("not listed in algorithms() means never cancelled").isFalse();
        assertThat(forgotten.isInterrupted()).isFalse();
    }

    @Test
    void aWallClockBudgetAlreadyExhaustedInterruptsImmediately() {
        CancelSpyLocalSearch localSearch = new CancelSpyLocalSearch();
        TestRunner runner = runner(List.of(localSearch));

        runner.interrupt(Duration.ZERO);

        assertThat(localSearch.isInterrupted()).isTrue();
        assertThat(runner.interrupted()).isTrue();
    }

    @Test
    void aBudgetStillRunningDoesNotInterrupt() {
        CancelSpyLocalSearch localSearch = new CancelSpyLocalSearch();
        TestRunner runner = runner(List.of(localSearch));

        runner.interrupt(Duration.ofHours(1));

        assertThat(localSearch.isInterrupted()).isFalse();
        assertThat(runner.interrupted()).isFalse();
    }

    @Test
    void interruptedAlgorithmsReturnTheirBestSoFarInsteadOfThrowing() {
        // interrupt() is a graceful stop, unlike cancel(): a 2-opt that is interrupted still hands back a plan.
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, customerA, customerB));
        TestTwoOpt twoOpt = new TestTwoOpt(calculation, new AlwaysFeasibleChecker());
        twoOpt.interrupt();

        TestPlan plan = new TestPlan("p", List.of(origin, customerB, customerA));
        assertThat(twoOpt.apply(plan).getNodes()).isNotEmpty();
    }

}
