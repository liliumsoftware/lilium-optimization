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

package com.github.liliumsoftware.optimization;

import com.github.liliumsoftware.optimization.domain.PlanModifier;
import com.github.liliumsoftware.optimization.generator.DateTimePlanCodeGenerator;
import com.github.liliumsoftware.optimization.support.AlwaysFeasibleChecker;
import com.github.liliumsoftware.optimization.support.CancelSpyLocalSearch;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.TestInsertion;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestRunner;
import com.github.liliumsoftware.optimization.support.TestTwoOpt;
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
        assertThat(runner.getPlans().getFirst().getCost()).isEqualTo(20);
        assertThat(applied).containsExactly("first:2", "second:2");
    }

    @Test
    void generatedPlanCodesAreUsedForTheProducedPlans() {
        TestRunner runner = runner(List.of());

        runner.plan(origin, List.of(customerA));

        assertThat(runner.getPlans().getFirst().getCode()).hasSize(25).containsOnlyDigits();
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
                .isInstanceOf(com.github.liliumsoftware.optimization.exception.CancelException.class);
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

    // ---------------------------------------------------------------- unplanned nodes: matching by value

    @Test
    void aValueEqualCopyOfAPlannedCustomerCountsAsPlanned() {
        // Plans hold deep copies of the customers (moves are built on copies), so matching must be by equals, not by
        // identity: the caller's original instances must still be recognised as planned.
        TestRunner runner = runner(List.of());
        TestNode customerC = TestNode.customer("C", 0, 30, 1);
        TestPlan plan = new TestPlan("p", List.of(origin, customerB.copy(), customerA.copy()));

        assertThat(runner.unplannedNodes(List.of(plan), List.of(customerA, customerC, customerB)))
                .containsExactly(customerC);
    }

    @Test
    void unplannedNodesKeepTheInputOrderAcrossSeveralPlans() {
        TestRunner runner = runner(List.of());
        List<TestNode> customers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            customers.add(TestNode.customer("N" + i, 0, i + 1, 1));
        }
        TestPlan first = new TestPlan("p", List.of(origin, customers.get(6), customers.get(1)));
        TestPlan second = new TestPlan("q", List.of(origin, customers.get(3)));

        assertThat(runner.unplannedNodes(List.of(first, second), customers))
                .containsExactly(customers.get(0), customers.get(2), customers.get(4), customers.get(5),
                        customers.get(7));
    }

    @Test
    void everyDuplicateOfAPlannedCustomerIsRemovedAndDuplicatesOfAnUnplannedOneKept() {
        TestRunner runner = runner(List.of());
        TestPlan plan = new TestPlan("p", List.of(origin, customerA));

        assertThat(runner.unplannedNodes(List.of(plan), List.of(customerA, customerB, customerA.copy(), customerB)))
                .containsExactly(customerB, customerB);
    }

    @Test
    void onlyThePlansCustomersCountNotItsDepots() {
        // A node passed in the customer list that a plan only uses as its origin or destination is not served.
        TestRunner runner = runner(List.of());
        TestPlan plan = new TestPlan("p", List.of(origin, customerA, destination));

        assertThat(runner.unplannedNodes(List.of(plan), List.of(origin, customerA, destination)))
                .containsExactly(origin, destination);
    }

    // ---------------------------------------------------------------- constructors

    @Test
    void bothConstructorsRejectMissingCollaborators() {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        DateTimePlanCodeGenerator generator = new DateTimePlanCodeGenerator();
        PlanModifier<TestPlan> modifier = plan -> {};

        assertThatThrownBy(() -> new VarargsRunner(null, generator, modifier))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("costCalculation");
        assertThatThrownBy(() -> new VarargsRunner(calculation, null, modifier))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("planCodeGenerator");
        assertThatThrownBy(() -> new VarargsRunner(calculation, generator, modifier, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("planModifier");
        assertThatThrownBy(() -> new TestRunner(null, generator, List.of(), List.of()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("costCalculation");
        assertThatThrownBy(() -> new TestRunner(calculation, null, List.of(), List.of()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("planCodeGenerator");
        List<PlanModifier<TestPlan>> withNull = new ArrayList<>();
        withNull.add(modifier);
        withNull.add(null);
        assertThatThrownBy(() -> new TestRunner(calculation, generator, List.of(), withNull))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("planModifier");
    }

    @Test
    void theVarargsConstructorAppliesItsModifiersInOrder() {
        List<String> applied = new ArrayList<>();
        VarargsRunner runner = new VarargsRunner(new MatrixCostCalculation(), new DateTimePlanCodeGenerator(),
                plan -> applied.add("first"), plan -> applied.add("second"));

        runner.finalizePlans(new ArrayList<>(List.of(new TestPlan("p", List.of(origin, customerA)))));

        assertThat(applied).containsExactly("first", "second");
        assertThat(new VarargsRunner(new MatrixCostCalculation(), new DateTimePlanCodeGenerator()).planModifiers)
                .isEmpty();
    }

    @Test
    void theListConstructorCopiesTheModifiersSoLaterChangesHaveNoEffect() {
        List<String> applied = new ArrayList<>();
        List<PlanModifier<TestPlan>> modifiers = new ArrayList<>();
        modifiers.add(plan -> applied.add("kept"));
        TestRunner runner = new TestRunner(new MatrixCostCalculation(), new DateTimePlanCodeGenerator(), List.of(),
                modifiers);

        modifiers.clear();
        modifiers.add(plan -> applied.add("added later"));
        runner.finalizePlans(new ArrayList<>(List.of(new TestPlan("p", List.of(origin, customerA)))));

        assertThat(applied).containsExactly("kept");
    }

    @Test
    void theModifierListIsUnmodifiable() {
        VarargsRunner runner = new VarargsRunner(new MatrixCostCalculation(), new DateTimePlanCodeGenerator(),
                plan -> {});

        assertThatThrownBy(() -> runner.planModifiers.add(plan -> {}))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(runner.planModifiers::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * A runner built through the varargs constructor, which {@link TestRunner} does not use.
     */
    private static final class VarargsRunner extends BaseAlgorithmRunner<TestPlan, TestNode, TestNode> {

        @SafeVarargs
        // the array only reaches the superclass constructor, which copies it
        @SuppressWarnings("varargs")
        private VarargsRunner(MatrixCostCalculation costCalculation, DateTimePlanCodeGenerator generator,
                PlanModifier<TestPlan>... modifiers) {
            super(costCalculation, generator, modifiers);
        }

        @Override
        public void plan(TestNode node, List<TestNode> customers) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected List<BaseAlgorithm> algorithms() {
            return List.of();
        }
    }

}
