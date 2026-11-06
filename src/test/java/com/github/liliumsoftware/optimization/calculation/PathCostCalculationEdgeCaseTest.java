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

package com.github.liliumsoftware.optimization.calculation;

import com.github.liliumsoftware.optimization.OptimizationType;
import com.github.liliumsoftware.optimization.domain.Planable;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTrackable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Pins the edges of {@link PathCostCalculation} and the {@link CostCalculation} defaults: duplicate codes rejected
 * before the provider is asked, a failed rebuild leaving the previous matrix in place, the closest-node ranking against
 * a naive reference for both the {@code int} and the {@code long} storage, the medoid ignoring the diagonal and
 * keeping the first of tied customers and skipping only its own position, the empty node set, the scalar cell types
 * {@code getValue} accepts and its nesting bound, and the plan sum following the node list rather than
 * {@link Planable#getCount()}.
 */
class PathCostCalculationEdgeCaseTest {

    private static final TestNode O = TestNode.origin("O", 0, 0);
    private static final TestNode A = TestNode.customer("A", 0, 1);
    private static final TestNode B = TestNode.customer("B", 0, 2);
    private static final TestNode C = TestNode.customer("C", 0, 3);

    private static List<TestNode> customers(int n) {
        List<TestNode> nodes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            nodes.add(TestNode.customer("N" + i, i, 0));
        }
        return nodes;
    }

    /**
     * The documented ranking written out naively: the node itself, then every other index by ascending cost, ties by
     * index (a stable sort of the remaining indices in matrix order).
     */
    private static List<TestNode> referenceRanking(long[][] costs, List<TestNode> nodes, int row) {
        List<Integer> others = new ArrayList<>();
        for (int j = 0; j < nodes.size(); j++) {
            if (j != row) {
                others.add(j);
            }
        }
        others.sort(Comparator.comparingLong((Integer j) -> costs[row][j]));
        List<TestNode> ranking = new ArrayList<>();
        ranking.add(nodes.get(row));
        others.forEach(j -> ranking.add(nodes.get(j)));
        return ranking;
    }

    private static Object nested(int levels, Object value) {
        Object cell = value;
        for (int i = 0; i < levels; i++) {
            cell = new Object[] {cell};
        }
        return cell;
    }

    // ---------------------------------------------------------------- initialization

    @Test
    void duplicateCodesAreRejectedWithoutAskingTheProviderForAMatrix() {
        FixedMatrix calc = new FixedMatrix(new long[][] {{0, 1}, {1, 0}});
        TestNode copyOfA = TestNode.customer("A", 7, 7);

        assertThatThrownBy(() -> calc.initializeCostMatrix(List.of(A, O, copyOfA)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate node code")
                .hasMessageContaining("A");
        assertThat(calc.calls).as("the provider was never asked").isZero();
    }

    @Test
    void aFailedRebuildKeepsThePreviousMatrix() {
        FixedMatrix calc = new FixedMatrix(new long[][] {{0, 5}, {6, 0}});
        calc.initializeCostMatrix(List.of(O, A));

        assertThatThrownBy(() -> calc.initializeCostMatrix(List.of(O, A, B)))   // the 2 x 2 matrix no longer fits
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> calc.initializeCostMatrix(List.of(O, O)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(calc.calculateCost(O, A)).isEqualTo(5);
        assertThat(calc.calculateCost(A, O)).isEqualTo(6);
        assertThatThrownBy(() -> calc.calculateCost(O, B)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aNullMatrixOrANullRowIsRejected() {
        PathCostCalculation<TestPlan, TestNode, Long> noMatrix = new PathCostCalculation<>(
                OptimizationType.NO_RESTRICTION, Map.of()) {
            @Override
            protected Long[][] getCostMatrix(List<TestNode> coordinates) {
                return null;
            }
        };
        PathCostCalculation<TestPlan, TestNode, Long> nullRow = new PathCostCalculation<>(
                OptimizationType.NO_RESTRICTION, Map.of()) {
            @Override
            protected Long[][] getCostMatrix(List<TestNode> coordinates) {
                return new Long[][] {{0L, 1L}, null};
            }
        };

        assertThatThrownBy(() -> noMatrix.initializeCostMatrix(List.of(O, A)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("got null");
        assertThatThrownBy(() -> nullRow.initializeCostMatrix(List.of(O, A)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("row 1").hasMessageContaining("null");
    }

    @Test
    void anEmptyNodeSetInitializesAndKnowsNoCode() {
        FixedMatrix calc = new FixedMatrix(new long[0][]);

        calc.initializeCostMatrix(List.of());

        assertThat(calc.isCompactStorage()).isTrue();
        assertThatThrownBy(() -> calc.getClosestNodes("O"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not contain");
        assertThatThrownBy(() -> calc.calculateCost(O, O))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not contain");
        assertThatThrownBy(() -> calc.getClosestNodeCostRank("O", null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aCellBelowTheIntRangeAlsoSwitchesToLongStorage() {
        long low = Integer.MIN_VALUE - 1L;
        FixedMatrix calc = new FixedMatrix(new long[][] {{0, 4, 2}, {low, 0, 3}, {7, 8, 0}});

        calc.initializeCostMatrix(List.of(O, A, B));

        assertThat(calc.isCompactStorage()).isFalse();
        // cells before the widening cell were copied over, cells after it written directly
        assertThat(calc.calculateCost(O, A)).isEqualTo(4);
        assertThat(calc.calculateCost(O, B)).isEqualTo(2);
        assertThat(calc.calculateCost(A, O)).isEqualTo(low);
        assertThat(calc.calculateCost(B, A)).isEqualTo(8);
        assertThat(calc.getClosestNodes("A")).containsExactly(A, O, B);
    }

    // ---------------------------------------------------------------- ranking against a naive reference

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theRankingEqualsANaiveReferenceOverRandomMatricesWithNegativeCostsAndTies(boolean compact) {
        Random random = new Random(compact ? 20261031L : 31102026L);
        long[] extremes = compact ? new long[] {Integer.MIN_VALUE, Integer.MAX_VALUE}
                : new long[] {Long.MIN_VALUE, Long.MAX_VALUE};
        long scale = compact ? 1 : 1_000_000_000L;   // values -5..5 x 1e9 leave the int range
        for (int trial = 0; trial < 200; trial++) {
            int n = 1 + random.nextInt(14);
            long[][] costs = new long[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    // few distinct values, so ties are frequent; now and then an extreme
                    costs[i][j] = random.nextInt(20) == 0 ? extremes[random.nextInt(2)]
                            : (random.nextInt(11) - 5) * scale;
                }
            }
            if (!compact) {
                costs[0][0] = Integer.MAX_VALUE + 1L;   // guarantees long storage even if every other cell is small
            }
            List<TestNode> nodes = customers(n);
            FixedMatrix calc = new FixedMatrix(costs);
            calc.initializeCostMatrix(nodes);

            assertThat(calc.isCompactStorage()).isEqualTo(compact);
            for (int i = 0; i < n; i++) {
                assertThat(calc.getClosestNodes(nodes.get(i).getCode()))
                        .as("trial %d, row %d", trial, i)
                        .containsExactlyElementsOf(referenceRanking(costs, nodes, i));
                for (int j = 0; j < n; j++) {
                    assertThat(calc.calculateCost(nodes.get(i), nodes.get(j))).isEqualTo(costs[i][j]);
                }
            }
        }
    }

    @Test
    void theRankOfANeighbourUnknownToTheMatrixIsAnError() {
        MatrixCostCalculation calc = new MatrixCostCalculation();
        calc.initializeCostMatrix(List.of(O, A, B));
        TestNode stranger = TestNode.customer("Z", 0, 9);

        assertThatThrownBy(() -> calc.getClosestNodeRank("A", stranger, B))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Z");
        assertThatThrownBy(() -> calc.getClosestNodeRank("A", O, stranger))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Z");
        assertThatThrownBy(() -> calc.getClosestNodeRank("A", stranger, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Z");
        assertThatThrownBy(() -> calc.getClosestNodeCostRank("A", B, stranger))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Z");
    }

    // ---------------------------------------------------------------- medoid

    @Test
    void theMedoidSumSkipsTheMedoidByPositionNotByEquals() {
        // Customers A, A' (a copy of A, so equals to it and on the same matrix row) and B. The diagonal cost(A, A)
        // is 3, so the medoid sums are A: cost(A, A') + cost(A, B) = 3 + 4 = 7, A': 3 + 4 = 7, B: 6 + 6 = 12; the
        // medoid is A, the first of the tied ones. calculateMedoidToOthersCost used to skip every customer equals to
        // the medoid, dropping A' and reporting 4 instead of the 7 getMedoid minimised.
        long[][] costs = {
                {0, 1, 1},
                {1, 3, 4},
                {1, 6, 0}};
        FixedMatrix calc = new FixedMatrix(costs);
        calc.initializeCostMatrix(List.of(O, A, B));
        TestNode twin = A.copy();
        assertThat(twin).isEqualTo(A).isNotSameAs(A);
        TestPlan plan = new TestPlan("p", List.of(O, A, twin, B));

        assertThat(calc.getMedoid(plan)).isSameAs(A);
        assertThat(calc.calculateMedoidToOthersCost(plan)).isEqualTo(7);

        // The twin first: it is the medoid now, and A (equals to it) still counts.
        TestPlan swapped = new TestPlan("q", List.of(O, twin, A, B));
        assertThat(calc.getMedoid(swapped)).isSameAs(twin);
        assertThat(calc.calculateMedoidToOthersCost(swapped)).isEqualTo(7);
    }

    @Test
    void theMedoidIgnoresTheDiagonal() {
        // Rows/columns O, A, B, C. Off the diagonal A is clearly central (A->B + A->C = 1 + 1 = 2, B: 5 + 5 = 10,
        // C: 5 + 5 = 10), but cost(A, A) is huge: counting the diagonal would make A the worst candidate.
        long[][] costs = {
                {0, 1, 1, 1},
                {1, 1_000_000, 1, 1},
                {1, 5, 0, 5},
                {1, 5, 5, 0}};
        FixedMatrix calc = new FixedMatrix(costs);
        calc.initializeCostMatrix(List.of(O, A, B, C));
        TestPlan plan = new TestPlan("p", List.of(O, B, A, C));

        assertThat(calc.getMedoid(plan)).isEqualTo(A);
        assertThat(calc.calculateMedoidToOthersCost(plan)).isEqualTo(2);
    }

    @Test
    void tiedMedoidCandidatesKeepTheFirstCustomerOfThePlan() {
        // B and C both sum to 4 (B: 2 + 2, C: 2 + 2), A to 6 (3 + 3): the first of the tied pair in plan order wins.
        long[][] costs = {
                {0, 1, 1, 1},
                {1, 0, 3, 3},
                {1, 2, 0, 2},
                {1, 2, 2, 0}};
        FixedMatrix calc = new FixedMatrix(costs);
        calc.initializeCostMatrix(List.of(O, A, B, C));

        assertThat(calc.getMedoid(new TestPlan("p", List.of(O, A, C, B)))).isEqualTo(C);
        assertThat(calc.getMedoid(new TestPlan("q", List.of(O, A, B, C)))).isEqualTo(B);
        // all equal: the very first customer
        FixedMatrix flat = new FixedMatrix(new long[][] {{0, 1, 1, 1}, {1, 0, 1, 1}, {1, 1, 0, 1}, {1, 1, 1, 0}});
        flat.initializeCostMatrix(List.of(O, A, B, C));
        assertThat(flat.getMedoid(new TestPlan("r", List.of(O, C, B, A)))).isEqualTo(C);
        assertThat(flat.getMedoid(new TestPlan("s", List.of(O, B)))).as("a single customer").isEqualTo(B);
    }

    // ---------------------------------------------------------------- cell types

    @Test
    void getValueReadsEveryIntegralNumberTypeAndTruncatesFractionsTowardZero() {
        MatrixCostCalculation calc = new MatrixCostCalculation();

        assertThat(calc.exposeGetValue((short) -7)).isEqualTo(-7);
        assertThat(calc.exposeGetValue((byte) 12)).isEqualTo(12);
        assertThat(calc.exposeGetValue(new AtomicLong(5_000_000_000L))).isEqualTo(5_000_000_000L);
        assertThat(calc.exposeGetValue(-0.5)).isZero();
        assertThat(calc.exposeGetValue(-2.9f)).isEqualTo(-2);
        assertThat(calc.exposeGetValue(new BigDecimal("-12.9"))).isEqualTo(-12);
        assertThat(calc.exposeGetValue(new Short[] {3})).isEqualTo(3);
    }

    @Test
    void getValueRejectsNonFiniteFloatsAndDecimalsBelowTheLongRange() {
        MatrixCostCalculation calc = new MatrixCostCalculation();

        assertThatThrownBy(() -> calc.exposeGetValue(Float.NaN))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("finite");
        assertThatThrownBy(() -> calc.exposeGetValue(Float.NEGATIVE_INFINITY))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("finite");
        assertThatThrownBy(() -> calc.exposeGetValue(new BigDecimal("-1e30")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("long");
        assertThatThrownBy(() -> calc.exposeGetValue(new BigDecimal(Long.MIN_VALUE).subtract(BigDecimal.ONE)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(calc.exposeGetValue(new BigDecimal(Long.MIN_VALUE))).isEqualTo(Long.MIN_VALUE);
        assertThat(calc.exposeGetValue(-0x1p63)).as("-2^63 is Long.MIN_VALUE itself").isEqualTo(Long.MIN_VALUE);
    }

    @Test
    void getValueUnwrapsUpTo32LevelsAndRejectsDeeperOrCyclicCells() {
        MatrixCostCalculation calc = new MatrixCostCalculation();
        Object[] cyclic = new Object[1];
        cyclic[0] = cyclic;

        assertThat(calc.exposeGetValue(nested(32, 7L))).isEqualTo(7);
        assertThat(calc.exposeGetValue(nested(31, new long[] {9}))).as("a primitive array is a level too").isEqualTo(9);
        assertThatThrownBy(() -> calc.exposeGetValue(nested(33, 7L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("more than 32 arrays deep");
        // A cell that contains itself used to be followed forever.
        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThatThrownBy(() -> calc.exposeGetValue(cyclic))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("contains itself"));
    }

    @Test
    void thePlanSumFollowsTheNodeListNotAnOverriddenCount() {
        // O(0,0) -> A(0,1) -> B(0,2) -> C(0,3): three edges of Manhattan length 1. The loop used to run to
        // getCount(): an under-count dropped the last edge (2), an over-count read past the list.
        CostCalculation<SkewedPlan, TestNode> manhattan = new CostCalculation<>() {
            @Override
            public long calculateCost(TestNode start, TestNode end) {
                return start.manhattanTo(end);
            }
        };
        List<TestNode> nodes = List.of(O, A, B, C);

        assertThat(manhattan.calculateCost(new SkewedPlan(nodes, 0))).isEqualTo(3);
        assertThat(manhattan.calculateCost(new SkewedPlan(nodes, -1))).isEqualTo(3);
        assertThat(manhattan.calculateCost(new SkewedPlan(nodes, 2))).isEqualTo(3);
    }

    // ---------------------------------------------------------------- CostCalculation defaults

    @Test
    void theEdgeCostIsUnsupportedUnlessImplemented() {
        CostCalculation<TestPlan, TestNode> bare = new CostCalculation<>() {
        };

        assertThatThrownBy(() -> bare.calculateCost(O, A)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> bare.calculateCost(new TestPlan("p", List.of(O, A))))
                .as("the plan sum delegates to the edge cost")
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(bare.calculateCost(new TestPlan("s", List.of(O)))).as("no edge, nothing to ask").isZero();
    }

    @Test
    void thePlanSumUsesThePlanAwareEdgeCost() {
        // A plan-aware cost that charges 10 per edge plus the plan code's length: O-A-B-C has 3 edges.
        CostCalculation<TestPlan, TestNode> planAware = new CostCalculation<>() {
            @Override
            public long calculateCost(TestPlan plan, TestNode start, TestNode end) {
                return 10 + plan.getCode().length();
            }
        };

        assertThat(planAware.calculateCost(new TestPlan("abc", List.of(O, A, B, C)))).isEqualTo(3 * (10 + 3));
        assertThat(CostCalculation.totalCost(List.of())).isZero();
    }

    /**
     * Serves a fixed matrix (row/column aligned to the nodes it is initialized with) and counts how often it is asked.
     */
    private static final class FixedMatrix extends PathCostCalculation<TestPlan, TestNode, Long> {

        private final long[][] costs;
        private int calls;

        FixedMatrix(long[][] costs) {
            super(OptimizationType.NO_RESTRICTION, Map.of());
            this.costs = costs;
        }

        @Override
        protected Long[][] getCostMatrix(List<TestNode> coordinates) {
            calls++;
            Long[][] matrix = new Long[costs.length][];
            for (int i = 0; i < costs.length; i++) {
                matrix[i] = new Long[costs[i].length];
                for (int j = 0; j < costs[i].length; j++) {
                    matrix[i][j] = costs[i][j];
                }
            }
            return matrix;
        }
    }

    /**
     * A plan whose {@link #getCount()} is overridden to disagree with its node list by {@code skew}.
     */
    private record SkewedPlan(List<TestNode> getNodes, int skew)
            implements Planable<SkewedPlan, TestNode, TestTrackable> {

        @Override
        public String getCode() {
            return "skewed";
        }

        @Override
        public TestTrackable getTrackable() {
            return null;
        }

        @Override
        public long getCost() {
            return 0;
        }

        @Override
        public SkewedPlan copy() {
            return new SkewedPlan(new ArrayList<>(getNodes), skew);
        }

        @Override
        public int getCount() {
            return getNodes.size() + skew;
        }
    }

}
