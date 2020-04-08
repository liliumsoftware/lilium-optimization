package ir.baho.algorithm.localsearch;

import ir.baho.algorithm.support.AlwaysFeasibleChecker;
import ir.baho.algorithm.support.MatrixCostCalculation;
import ir.baho.algorithm.support.TestNode;
import ir.baho.algorithm.support.TestPlan;
import ir.baho.algorithm.support.TestTwoOpt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2-opt is validated by invariants rather than fixed expected tours: it must never worsen a tour, must return
 * a valid permutation, must be a local optimum (idempotent), and must never beat the brute-force optimum.
 */
class TwoOptTest {

    private MatrixCostCalculation calc(List<TestNode> nodes) {
        MatrixCostCalculation calc = new MatrixCostCalculation();
        calc.initializeCostMatrix(nodes);
        return calc;
    }

    private long tourCost(MatrixCostCalculation calc, List<TestNode> nodes) {
        long cost = 0;
        for (int i = 0; i < nodes.size() - 1; i++) {
            cost += calc.calculateCost(nodes.get(i), nodes.get(i + 1));
        }
        return cost;
    }

    /**
     * Brute-force optimal open-path cost over all customer orderings, origin fixed first.
     */
    private long optimal(MatrixCostCalculation calc, TestNode origin, List<TestNode> customers) {
        return permutations(customers).stream()
                .mapToLong(perm -> {
                    List<TestNode> path = new ArrayList<>();
                    path.add(origin);
                    path.addAll(perm);
                    return tourCost(calc, path);
                }).min().orElseThrow();
    }

    private <E> List<List<E>> permutations(List<E> items) {
        if (items.isEmpty()) {
            return List.of(List.of());
        }
        List<List<E>> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<E> rest = new ArrayList<>(items);
            E head = rest.remove(i);
            for (List<E> tail : permutations(rest)) {
                List<E> perm = new ArrayList<>();
                perm.add(head);
                perm.addAll(tail);
                result.add(perm);
            }
        }
        return result;
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 2L, 7L, 42L, 123L, 2024L, 99999L})
    void neverWorsensAndNeverBeatsOptimum(long seed) {
        Random random = new Random(seed);
        for (int trial = 0; trial < 40; trial++) {
            int n = 3 + random.nextInt(5);   // 3..7 customers (brute force stays cheap)
            TestNode origin = TestNode.origin("O", 0, 0);
            List<TestNode> customers = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                customers.add(TestNode.customer("C" + i, random.nextInt(50), random.nextInt(50)));
            }
            List<TestNode> all = new ArrayList<>();
            all.add(origin);
            all.addAll(customers);

            List<TestNode> shuffled = new ArrayList<>(customers);
            java.util.Collections.shuffle(shuffled, random);
            List<TestNode> initial = new ArrayList<>();
            initial.add(origin);
            initial.addAll(shuffled);

            TestPlan plan = new TestPlan("p", initial);
            MatrixCostCalculation calc = calc(all);
            long before = calc.calculateCost(plan);
            TestTwoOpt twoOpt = new TestTwoOpt(calc, new AlwaysFeasibleChecker());
            TestPlan result = twoOpt.apply(plan.copy());
            long after = tourCost(calc, result.getNodes());

            assertThat(after).as("never worsens (seed %d)", seed).isLessThanOrEqualTo(before);
            assertThat(after).as("never beats optimum (seed %d)", seed)
                    .isGreaterThanOrEqualTo(optimal(calc, origin, customers));
            assertThat(result.getNodes()).as("valid permutation (seed %d)", seed)
                    .containsExactlyInAnyOrderElementsOf(initial);
            assertThat(result.getNodes().get(0)).isEqualTo(origin);   // endpoint preserved

            // Local optimum: a second pass cannot improve further.
            TestPlan again = twoOpt.apply(result.copy());
            assertThat(tourCost(calc, again.getNodes())).isEqualTo(after);
        }
    }

    @Test
    void isDeterministicAcrossRuns() {
        TestNode origin = TestNode.origin("O", 0, 0);
        List<TestNode> nodes = List.of(origin,
                TestNode.customer("A", 5, 9), TestNode.customer("B", 30, 1),
                TestNode.customer("C", 12, 40), TestNode.customer("D", 3, 3));
        MatrixCostCalculation calc = calc(nodes);
        TestPlan plan = new TestPlan("p", nodes);

        TestPlan first = new TestTwoOpt(calc, new AlwaysFeasibleChecker()).apply(plan.copy());
        TestPlan second = new TestTwoOpt(calc, new AlwaysFeasibleChecker()).apply(plan.copy());
        assertThat(first.getNodes()).isEqualTo(second.getNodes());
    }

    @Test
    void singleCustomerTourIsUnchanged() {
        TestNode origin = TestNode.origin("O", 0, 0);
        TestNode a = TestNode.customer("A", 4, 4);
        MatrixCostCalculation calc = calc(List.of(origin, a));
        TestPlan plan = new TestPlan("p", List.of(origin, a));
        TestPlan result = new TestTwoOpt(calc, new AlwaysFeasibleChecker()).apply(plan);
        assertThat(result.getNodes()).containsExactly(origin, a);
    }

    @Test
    void reversalImprovesTourWhilePinningBothEndpoints() {
        // The reversal loop's index bounds keep the FIRST and LAST nodes fixed (origin and the return/
        // destination endpoint), reordering only the interior. This pins that endpoint-preserving behaviour.
        TestNode origin = TestNode.origin("O", 0, 0);
        TestNode a = TestNode.customer("A", 0, 10);
        TestNode b = TestNode.customer("B", 0, 20);
        TestNode c = TestNode.customer("C", 0, 30);
        MatrixCostCalculation calc = calc(List.of(origin, a, b, c));

        TestPlan plan = new TestPlan("p", List.of(origin, c, a, b));  // O->C->A->B = 30 + 20 + 10 = 60
        long before = calc.calculateCost(plan);
        TestPlan result = new TestTwoOpt(calc, new AlwaysFeasibleChecker()).apply(plan);

        assertThat(before).isEqualTo(60);
        assertThat(result.getNodes()).containsExactly(origin, a, c, b);  // B stays last
        assertThat(tourCost(calc, result.getNodes())).isEqualTo(40);
        assertThat(result.getNodes().get(0)).isEqualTo(origin);
        assertThat(result.getNodes().get(result.getNodes().size() - 1)).isEqualTo(b);
    }

}
