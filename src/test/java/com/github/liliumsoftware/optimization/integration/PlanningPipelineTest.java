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

package com.github.liliumsoftware.optimization.integration;

import com.github.liliumsoftware.optimization.OptimizationType;
import com.github.liliumsoftware.optimization.optimization.ExchangeRelocateType;
import com.github.liliumsoftware.optimization.support.AlwaysFeasibleChecker;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.PlanInvariants;
import com.github.liliumsoftware.optimization.support.Problems;
import com.github.liliumsoftware.optimization.support.RecostingLocalSearch;
import com.github.liliumsoftware.optimization.support.TestAssignment;
import com.github.liliumsoftware.optimization.support.TestClarkeWright;
import com.github.liliumsoftware.optimization.support.TestDestroyRepair;
import com.github.liliumsoftware.optimization.support.TestExchange;
import com.github.liliumsoftware.optimization.support.TestInsertion;
import com.github.liliumsoftware.optimization.support.TestKMedoids;
import com.github.liliumsoftware.optimization.support.TestMerge;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestNodeBalance;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestRelocate;
import com.github.liliumsoftware.optimization.support.TestTwoOpt;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end checks over the pipeline a host application actually runs: construct with Clarke-Wright, then
 * improve with the inter-plan algorithms, then finish.
 *
 * <p>The point is not that any particular route comes out — different heuristics legitimately disagree — but
 * that <em>every</em> stage hands on a solution that is still valid: the same customers, exactly once, on
 * well-formed routes, within capacity, on distinct vehicles, with truthful costs. Cost is compared only where
 * an algorithm actually promises monotone improvement.
 */
class PlanningPipelineTest {

    private final Problems.Problem problem = Problems.capacityConstrained();

    private MatrixCostCalculation calculation;
    private TestAssignment assignment;

    private List<TestPlan> construct() {
        calculation = problem.calculation();
        assignment = problem.assignment();
        return new ArrayList<>(new TestClarkeWright(calculation, assignment,
                new TestTwoOpt(calculation, new AlwaysFeasibleChecker()),
                problem.destination(), OptimizationType.NO_RESTRICTION)
                .plan(problem.origin(), problem.customers()));
    }

    private TestInsertion insertion() {
        return new TestInsertion(calculation, new AlwaysFeasibleChecker(), false);
    }

    // ---------------------------------------------------------------- construct then optimize

    @Test
    void constructionFollowedByEveryOptimizerKeepsTheSolutionValid() {
        List<TestPlan> plans = construct();
        PlanInvariants.assertValidSolution(plans, problem.origin(), problem.destination(), problem.customers());
        long constructionCost = PlanInvariants.referenceTotalCost(plans);

        List<UnaryOperator<List<TestPlan>>> stages = List.of(
                current -> new TestExchange(calculation, assignment, new RecostingLocalSearch(calculation),
                        insertion(), ExchangeRelocateType.SIMPLE, 4, 2, true).apply(current),
                current -> new TestRelocate(calculation, assignment, new RecostingLocalSearch(calculation),
                        insertion(), ExchangeRelocateType.TWO_OPT, 4, 2).apply(current),
                current -> new TestDestroyRepair(calculation, assignment, new RecostingLocalSearch(calculation),
                        insertion(), 2, 0).apply(current),
                current -> new TestMerge(calculation, assignment, new RecostingLocalSearch(calculation)).apply(current),
                current -> new TestNodeBalance(calculation, assignment, insertion(), 2).apply(current));

        for (UnaryOperator<List<TestPlan>> stage : stages) {
            plans = new ArrayList<>(stage.apply(plans));
            PlanInvariants.assertValidSolution(plans, problem.origin(), problem.destination(), problem.customers());
            PlanInvariants.assertEveryPlanHasTrackable(plans);
            PlanInvariants.assertNoEmptyPlans(plans);
            PlanInvariants.assertLibraryCostMatchesIndependentCost(plans, calculation::calculateCost);
        }

        // The optimizers never promise to beat construction outright (balancing may trade cost for evenness),
        // but the whole pipeline must not blow the cost up either.
        assertThat(PlanInvariants.referenceTotalCost(plans))
                .isLessThanOrEqualTo(constructionCost * 2);
    }

    @Test
    void costImprovingStagesNeverIncreaseTheTotal() {
        List<TestPlan> plans = construct();

        // These three are the stages whose acceptance test is "strictly cheaper", so monotonicity is a contract.
        plans = new ArrayList<>(new TestExchange(calculation, assignment, new RecostingLocalSearch(calculation),
                insertion(), ExchangeRelocateType.SIMPLE, 4, 2, true).apply(plans));
        plans = new ArrayList<>(new TestRelocate(calculation, assignment, new RecostingLocalSearch(calculation),
                insertion(), ExchangeRelocateType.SIMPLE, 4, 2).apply(plans));
        plans = new ArrayList<>(new TestDestroyRepair(calculation, assignment,
                new RecostingLocalSearch(calculation), insertion(), 2, 0).apply(plans));

        long before = PlanInvariants.referenceTotalCost(plans);
        assertThat(PlanInvariants.referenceTotalCost(plans)).isLessThanOrEqualTo(before);
        PlanInvariants.assertValidSolution(plans, problem.origin(), problem.destination(), problem.customers());
    }

    @Test
    void runningTheWholePipelineTwiceGivesTheSameResult() {
        List<TestPlan> first = optimizeFully(construct());
        List<TestPlan> second = optimizeFully(construct());

        assertThat(first).isEqualTo(second);
    }

    private List<TestPlan> optimizeFully(List<TestPlan> plans) {
        plans = new ArrayList<>(new TestExchange(calculation, assignment, new RecostingLocalSearch(calculation),
                insertion(), ExchangeRelocateType.SIMPLE, 4, 2, true).apply(plans));
        plans = new ArrayList<>(new TestDestroyRepair(calculation, assignment,
                new RecostingLocalSearch(calculation), insertion(), 2, 0).apply(plans));
        return new ArrayList<>(new TestMerge(calculation, assignment,
                new RecostingLocalSearch(calculation)).apply(plans));
    }

    // ---------------------------------------------------------------- cross-algorithm agreement

    @Test
    void everyConstructionRouteThroughTheLibraryServesTheSameCustomerSet() {
        // Clarke-Wright and k-medoids are different heuristics and may return different routes and costs; what
        // they must agree on is which customers exist and that the fleet constraints hold.
        Problems.Problem instance = Problems.twoClusters();
        MatrixCostCalculation clarkeCalculation = instance.calculation();
        TestAssignment clarkeAssignment = instance.assignment();
        List<TestPlan> clarkeWright = new TestClarkeWright(clarkeCalculation, clarkeAssignment,
                new TestTwoOpt(clarkeCalculation, new AlwaysFeasibleChecker()),
                instance.destination(), OptimizationType.NO_RESTRICTION)
                .plan(instance.origin(), instance.customers());

        MatrixCostCalculation clusterCalculation = instance.calculation();
        TestAssignment clusterAssignment = instance.assignment();
        List<TestPlan> seeds = new ArrayList<>(List.of(
                instance.plan("seed1", instance.customer("A1"), instance.customer("B1")),
                instance.plan("seed2", instance.customer("A2"), instance.customer("B2"))));
        clusterAssignment.assign(seeds);
        List<TestPlan> clustered = new TestKMedoids(clusterCalculation, clusterAssignment,
                new TestInsertion(clusterCalculation, new AlwaysFeasibleChecker(), false))
                .apply(instance.origin(), List.of(), seeds);

        for (List<TestPlan> plans : List.of(clarkeWright, clustered)) {
            PlanInvariants.assertNodeLayout(plans);
            PlanInvariants.assertNoDuplicateCustomers(plans);
            PlanInvariants.assertEveryPlanHasTrackable(plans);
            PlanInvariants.assertTrackablesAreDistinct(plans);
            PlanInvariants.assertCustomerCountWithinCapacity(plans);
        }
        assertThat(PlanInvariants.customersOf(clarkeWright))
                .containsExactlyInAnyOrderElementsOf(instance.customers());
        assertThat(PlanInvariants.customersOf(clustered))
                .containsExactlyInAnyOrderElementsOf(PlanInvariants.customersOf(clarkeWright));
    }

    @Test
    void anOptimizerIsGivenTheSameCustomersItReturnsEvenWhenItCannotImprove() {
        // Degenerate geometry: every edge costs nothing, so no move can improve and nothing may be disturbed.
        Problems.Problem flat = Problems.customersAtDepot();
        calculation = flat.calculation();
        assignment = flat.assignment();
        List<TestPlan> plans = new ArrayList<>(List.of(flat.plan("p", flat.customers())));
        assignment.assign(plans);
        List<TestPlan> snapshot = PlanInvariants.deepCopy(plans);

        List<TestPlan> result = new TestDestroyRepair(calculation, assignment,
                new RecostingLocalSearch(calculation), insertion(), 2, 0).apply(plans);

        assertThat(result).isEqualTo(snapshot);
        assertThat(PlanInvariants.referenceTotalCost(result)).isZero();
    }

    @Test
    void theInputCustomerListIsNeverMutatedByTheConstructionStage() {
        List<TestNode> customers = new ArrayList<>(problem.customers());
        calculation = problem.calculation();
        assignment = problem.assignment();

        new TestClarkeWright(calculation, assignment, new TestTwoOpt(calculation, new AlwaysFeasibleChecker()),
                problem.destination(), OptimizationType.NO_RESTRICTION).plan(problem.origin(), customers);

        List<TestNode> snapshot = List.copyOf(customers);
        assertThat(customers).isEqualTo(snapshot);
    }

}
