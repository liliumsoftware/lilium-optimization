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
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the cost-matrix engine: lookups, closest-node ranking, zone predicates, medoids, and array unwrapping.
 */
class PathCostCalculationTest {

    // Collinear nodes on the y-axis with pairwise-distinct distances, so orderings are unambiguous.
    private static final TestNode ORIGIN = TestNode.origin("O", 0, 0);
    private static final TestNode C = TestNode.customer("C", 0, 3);
    private static final TestNode A = TestNode.customer("A", 0, 10);
    private static final TestNode B = TestNode.customer("B", 0, 25);

    private MatrixCostCalculation initialized() {
        MatrixCostCalculation calc = new MatrixCostCalculation();
        calc.initializeCostMatrix(List.of(ORIGIN, C, A, B));
        return calc;
    }

    @Test
    void calculateCostReturnsManhattanDistance() {
        MatrixCostCalculation calc = initialized();
        assertThat(calc.calculateCost(ORIGIN, A)).isEqualTo(10);
        assertThat(calc.calculateCost(A, B)).isEqualTo(15);
        assertThat(calc.calculateCost(A, ORIGIN)).isEqualTo(10);
        assertThat(calc.calculateCost(A, A)).isZero();
    }

    @Test
    void planCostIsSumOfConsecutiveEdgesAndStoredOnPlan() {
        MatrixCostCalculation calc = initialized();
        TestPlan plan = new TestPlan("p", List.of(ORIGIN, C, A, B));
        long cost = calc.calculateCost(plan);
        assertThat(cost).isEqualTo(3 + 7 + 15);
        assertThat(plan.getCost()).isEqualTo(cost);
    }

    @Test
    void singleNodeAndEmptyPlansCostZero() {
        MatrixCostCalculation calc = initialized();
        assertThat(calc.calculateCost(new TestPlan("s", List.of(ORIGIN)))).isZero();
        assertThat(calc.calculateCost(new TestPlan("e", List.of()))).isZero();
    }

    @Test
    void calculateCostBeforeInitializationThrows() {
        assertThatThrownBy(() -> new MatrixCostCalculation().calculateCost(ORIGIN, A))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");
    }

    @Test
    void calculateCostForUnknownNodeThrows() {
        MatrixCostCalculation calc = new MatrixCostCalculation();
        calc.initializeCostMatrix(List.of(ORIGIN, C));
        assertThatThrownBy(() -> calc.calculateCost(ORIGIN, B))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not contain");
    }

    @Test
    void closestNodesAreOrderedByAscendingCostWithSelfFirst() {
        MatrixCostCalculation calc = initialized();
        assertThat(calc.getClosestNodes("A")).containsExactly(A, C, ORIGIN, B);
        assertThat(calc.getClosestNodes("O")).containsExactly(ORIGIN, C, A, B);
    }

    @Test
    void aNodeComesFirstInItsOwnRankingEvenWhenAnotherNodeSharesItsLocation() {
        // P and Q sit on the same spot, so both are at cost 0 from Q; the documented "node itself first" used to
        // hold only for the one with the earlier matrix position.
        TestNode p = TestNode.customer("P", 5, 5);
        TestNode q = TestNode.customer("Q", 5, 5);
        MatrixCostCalculation calc = new MatrixCostCalculation();
        calc.initializeCostMatrix(List.of(ORIGIN, p, q));

        assertThat(calc.getClosestNodes("Q")).containsExactly(q, p, ORIGIN);
        assertThat(calc.getClosestNodes("P")).containsExactly(p, q, ORIGIN);
    }

    @Test
    void initializationIsDeterministic() {
        assertThat(initialized().getClosestNodes("A")).isEqualTo(initialized().getClosestNodes("A"));
    }

    @Test
    void medoidIsMostCentralCustomer() {
        MatrixCostCalculation calc = initialized();
        TestPlan plan = new TestPlan("p", List.of(ORIGIN, A, C, B));
        assertThat(calc.getMedoid(plan)).isEqualTo(A);
        assertThat(calc.calculateMedoidToOthersCost(plan)).isEqualTo(7 + 15);
    }

    @Test
    void closestNodeRankResolvesEndpointsAndWorstNeighbour() {
        MatrixCostCalculation calc = initialized();
        TestNode dest = TestNode.destination("D", 0, 100);
        // closest(A) = [A(0), C(1), O(2), B(3)]
        assertThat(calc.getClosestNodeRank("A", ORIGIN, null)).isZero();          // origin before, open end
        assertThat(calc.getClosestNodeRank("A", C, null)).isEqualTo(1);           // rank of C
        assertThat(calc.getClosestNodeRank("A", C, dest)).isEqualTo(1);           // destination after == open end
        assertThat(calc.getClosestNodeRank("A", ORIGIN, B)).isEqualTo(3);         // origin before -> rank of after
        assertThat(calc.getClosestNodeRank("A", C, B)).isEqualTo(3);              // max(rank C, rank B)
    }

    @Test
    void aStopWithoutAPredecessorIsRankedLikeOneRightAfterTheOrigin() {
        // The first customer of a plan without an origin has no node before it; ranking it dereferenced null.
        MatrixCostCalculation calc = initialized();
        TestNode dest = TestNode.destination("D", 0, 100);

        assertThat(calc.getClosestNodeRank("A", null, null)).isZero();
        assertThat(calc.getClosestNodeRank("A", null, dest)).isZero();
        assertThat(calc.getClosestNodeRank("A", null, B)).isEqualTo(3);
        assertThat(calc.getClosestNodeCostRank("A", null, null)).isZero();
        assertThat(calc.getClosestNodeCostRank("A", null, B)).isEqualTo(15);
    }

    @Test
    void closestNodeCostRankUsesRawCosts() {
        MatrixCostCalculation calc = initialized();
        assertThat(calc.getClosestNodeCostRank("A", ORIGIN, null)).isZero();
        assertThat(calc.getClosestNodeCostRank("A", C, null)).isEqualTo(7);
        assertThat(calc.getClosestNodeCostRank("A", C, B)).isEqualTo(15);         // max(7, 15)
    }

    @Test
    void zonePredicatesAreUnrestrictedUnderNoRestriction() {
        MatrixCostCalculation calc = new MatrixCostCalculation();
        assertThat(calc.isSameZone("Z1", "Z2")).isTrue();
        assertThat(calc.isNeighbor("Z1", "Z2")).isTrue();
    }

    @Test
    void zonePredicatesRespectZonesUnderLocationZone() {
        MatrixCostCalculation calc = new MatrixCostCalculation(
                OptimizationType.LOCATION_ZONE, Map.of("Z1", List.of("Z2")));
        assertThat(calc.isSameZone("Z1", "Z1")).isTrue();
        assertThat(calc.isSameZone("Z1", "Z2")).isFalse();
        assertThat(calc.isNeighbor("Z1", "Z2")).isTrue();
        assertThat(calc.isNeighbor("Z2", "Z1")).isFalse();   // neighbour relation is directed
        assertThat(calc.isNeighbor("Z1", "Z3")).isFalse();
        assertThat(calc.isSameZone(null, "Z2")).isTrue();    // null zone opts out of restriction
        assertThat(calc.isNeighbor(null, "Z2")).isTrue();
    }

    @Test
    void aNullZoneIsUnrestrictedOnEitherSideOfThePredicates() {
        // null opts out of zone restriction; that used to hold only when it was the first argument, and a
        // List.of(...) neighbour list threw NullPointerException when asked whether it contains null.
        MatrixCostCalculation calc = new MatrixCostCalculation(OptimizationType.LOCATION_ZONE, Map.of("Z1",
                List.of("Z1")));

        assertThat(calc.isSameZone("Z1", null)).isTrue();
        assertThat(calc.isSameZone(null, "Z1")).isTrue();
        assertThat(calc.isNeighbor("Z1", null)).isTrue();
        assertThat(calc.isNeighbor(null, "Z1")).isTrue();
        assertThat(calc.isNeighbor("Z2", "Z2")).as("a zone is not its own neighbour unless listed").isFalse();
    }

    @Test
    void aMissingNeighboursMatrixIsRejectedWhenTheCalculationIsBuilt() {
        assertThatThrownBy(() -> new MatrixCostCalculation(OptimizationType.LOCATION_ZONE, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("neighborsMatrix");
    }

    @Test
    void nonFiniteCellsAreRejectedNamingTheCell() {
        // NaN used to become 0 and +Infinity Long.MAX_VALUE, both silently: an unreachable pair looked free or
        // overflowed every sum it took part in.
        List<TestNode> nodes = List.of(ORIGIN, A, B);
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            PathCostCalculation<TestPlan, TestNode, Double> calc = new PathCostCalculation<>(
                    OptimizationType.NO_RESTRICTION, Map.of()) {
                @Override
                protected Double[][] getCostMatrix(List<TestNode> coordinates) {
                    return new Double[][] {{0d, 10d, 25d}, {10d, 0d, bad}, {25d, 15d, 0d}};
                }
            };

            assertThatThrownBy(() -> calc.initializeCostMatrix(nodes))
                    .as("cell %s", bad)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("finite")
                    .hasMessageContaining("[1][2]")
                    .hasMessageContaining("A -> B");
        }
        MatrixCostCalculation calc = new MatrixCostCalculation();
        assertThatThrownBy(() -> calc.exposeGetValue(Float.NaN)).isInstanceOf(IllegalStateException.class);
        assertThat(calc.exposeGetValue(-5)).as("negative cells are accepted as given").isEqualTo(-5);
    }

    @Test
    void cellsOutsideTheLongRangeAreRejectedNamingTheCell() {
        // A BigInteger of 2^64 + 1 wrapped to 1, a finite 1e300 saturated to Long.MAX_VALUE: both silently.
        List<TestNode> nodes = List.of(ORIGIN, A);
        for (Object bad : new Object[] {new BigInteger("18446744073709551617"), 1e300, -1e19, 1e19f,
                new BigDecimal("1e30")}) {
            PathCostCalculation<TestPlan, TestNode, Object> calc = new PathCostCalculation<>(
                    OptimizationType.NO_RESTRICTION, Map.of()) {
                @Override
                protected Object[][] getCostMatrix(List<TestNode> coordinates) {
                    return new Object[][] {{0, bad}, {10, 0}};
                }
            };

            assertThatThrownBy(() -> calc.initializeCostMatrix(nodes))
                    .as("cell %s", bad)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("[0][1]")
                    .hasMessageContaining("long");
        }
        MatrixCostCalculation calc = new MatrixCostCalculation();
        assertThat(calc.exposeGetValue(Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
        assertThat(calc.exposeGetValue(BigInteger.valueOf(Long.MIN_VALUE))).isEqualTo(Long.MIN_VALUE);
        assertThat(calc.exposeGetValue(new BigDecimal("12.9"))).as("fractions are still truncated").isEqualTo(12);
    }

    @Test
    void costSumsThatDoNotFitALongFailInsteadOfWrapping() {
        // Every edge costs 2^62: a plan over two edges, or a medoid sum over two other customers, is 2^63 and used to
        // wrap to Long.MIN_VALUE, which every "is it cheaper" comparison then treated as a huge saving.
        long big = 1L << 62;
        TestNode o = TestNode.origin("O", 0, 0);
        TestNode a = TestNode.customer("A", 0, 1);
        TestNode b = TestNode.customer("B", 0, 2);
        TestNode c = TestNode.customer("C", 0, 3);
        PathCostCalculation<TestPlan, TestNode, Long> calc = new PathCostCalculation<>(OptimizationType.NO_RESTRICTION,
                Map.of()) {
            @Override
            protected Long[][] getCostMatrix(List<TestNode> coordinates) {
                Long[][] matrix = new Long[coordinates.size()][coordinates.size()];
                for (int i = 0; i < matrix.length; i++) {
                    for (int j = 0; j < matrix.length; j++) {
                        matrix[i][j] = i == j ? 0L : big;
                    }
                }
                return matrix;
            }
        };
        calc.initializeCostMatrix(List.of(o, a, b, c));

        assertThat(calc.calculateCost(new TestPlan("p", List.of(o, a)))).isEqualTo(big);
        assertThatThrownBy(() -> calc.calculateCost(new TestPlan("p", List.of(o, a, b))))
                .isInstanceOf(ArithmeticException.class).hasMessageContaining("overflow").hasMessageContaining("p");
        assertThatThrownBy(() -> calc.getMedoid(new TestPlan("q", List.of(o, a, b, c))))
                .isInstanceOf(ArithmeticException.class).hasMessageContaining("q");

        List<TestPlan> plans = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            TestPlan plan = new TestPlan("p" + i, List.of(TestNode.origin("O", 0, 0), TestNode.customer("X", 0, 1)));
            plan.setCostMultiplier(1L << 60);
            plans.add(plan);
        }
        assertThat(CostCalculation.totalCost(plans.subList(0, 7))).isEqualTo(7L << 60);
        assertThatThrownBy(() -> CostCalculation.totalCost(plans)).isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("overflow");
    }

    @Test
    void aNodeSetWhoseMatrixCannotFitInAnArrayIsRejectedBeforeTheProviderIsAsked() {
        // 46 341² exceeds the largest Java array; the flat matrix used to overflow int and fail with
        // NegativeArraySizeException after the provider had already built its huge matrix.
        List<TestNode> nodes = new ArrayList<>();
        for (int i = 0; i < 46_341; i++) {
            nodes.add(TestNode.customer("N" + i, i, 0));
        }
        PathCostCalculation<TestPlan, TestNode, Integer> calc = new PathCostCalculation<>(
                OptimizationType.NO_RESTRICTION, Map.of()) {
            @Override
            protected Integer[][] getCostMatrix(List<TestNode> coordinates) {
                throw new AssertionError("the provider must not be asked for an impossible matrix");
            }
        };

        assertThatThrownBy(() -> calc.initializeCostMatrix(nodes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("46341");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {1L, 13L, 555L, 8080L})
    void closestNodesRankingIsSortedAndCompleteForRandomSets(long seed) {
        java.util.Random random = new java.util.Random(seed);
        for (int trial = 0; trial < 25; trial++) {
            int n = 2 + random.nextInt(12);
            java.util.List<TestNode> nodes = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                nodes.add(TestNode.customer("N" + i, random.nextInt(200), random.nextInt(200)));
            }
            MatrixCostCalculation calc = new MatrixCostCalculation();
            calc.initializeCostMatrix(nodes);

            for (TestNode node : nodes) {
                List<TestNode> ranking = calc.getClosestNodes(node.getCode());
                assertThat(ranking).as("contains every node once").containsExactlyInAnyOrderElementsOf(nodes);
                assertThat(ranking.getFirst()).as("self ranks first at cost 0").isEqualTo(node);
                for (int k = 0; k < ranking.size() - 1; k++) {
                    assertThat(calc.calculateCost(node, ranking.get(k)))
                            .isLessThanOrEqualTo(calc.calculateCost(node, ranking.get(k + 1)));
                }
            }
        }
    }

    @Test
    void getValueUnwrapsNestedArrays() {
        MatrixCostCalculation calc = new MatrixCostCalculation();
        assertThat(calc.exposeGetValue(42)).isEqualTo(42L);
        assertThat(calc.exposeGetValue(new int[] {7})).isEqualTo(7L);
        assertThat(calc.exposeGetValue(new int[][] {{9, 1}, {2}})).isEqualTo(9L);
        assertThat(calc.exposeGetValue(new Integer[] {3, 4})).isEqualTo(3L);
        assertThat(calc.exposeGetValue(5.9d)).isEqualTo(5L);   // Number truncation
        // Regression: cells were read with intValue(), silently wrapping anything above 2^31 - 1.
        assertThat(calc.exposeGetValue(3_000_000_000L)).isEqualTo(3_000_000_000L);
        assertThat(calc.exposeGetValue(new long[] {Long.MAX_VALUE})).isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> calc.exposeGetValue(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> calc.exposeGetValue(new long[0])).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> calc.exposeGetValue(new int[][] {{}})).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> calc.exposeGetValue("12")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("String");
    }

    @Test
    void aProviderMatrixOfTheWrongShapeIsRejectedWithAClearMessage() {
        // Regression: a short row used to fail later with ArrayIndexOutOfBoundsException from inside the loop.
        List<TestNode> nodes = List.of(TestNode.origin("O", 0, 0), TestNode.customer("A", 0, 1), TestNode.customer("B",
                0, 2));
        PathCostCalculation<TestPlan, TestNode, Integer> tooFewRows = new PathCostCalculation<>(
                OptimizationType.NO_RESTRICTION, Map.of()) {
            @Override
            protected Integer[][] getCostMatrix(List<TestNode> coordinates) {
                return new Integer[][] {{0, 1, 2}, {1, 0, 1}};
            }
        };
        PathCostCalculation<TestPlan, TestNode, Integer> shortRow = new PathCostCalculation<>(
                OptimizationType.NO_RESTRICTION, Map.of()) {
            @Override
            protected Integer[][] getCostMatrix(List<TestNode> coordinates) {
                return new Integer[][] {{0, 1, 2}, {1, 0}, {2, 1, 0}};
            }
        };

        assertThatThrownBy(() -> tooFewRows.initializeCostMatrix(nodes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("one row per node");
        assertThatThrownBy(() -> shortRow.initializeCostMatrix(nodes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("row 1");
    }

    @Test
    void costsBeyondTheIntRangeSurviveTheMatrixAndThePlanSum() {
        TestNode o = TestNode.origin("O", 0, 0);
        TestNode a = TestNode.customer("A", 0, 1);
        TestNode b = TestNode.customer("B", 0, 2);
        long big = 3_000_000_000L;
        PathCostCalculation<TestPlan, TestNode, Long> calc = new PathCostCalculation<>(OptimizationType.NO_RESTRICTION,
                Map.of()) {
            @Override
            protected Long[][] getCostMatrix(List<TestNode> coordinates) {
                return new Long[][] {{0L, big, 2 * big}, {big, 0L, big}, {2 * big, big, 0L}};
            }
        };
        calc.initializeCostMatrix(List.of(o, a, b));

        assertThat(calc.calculateCost(o, b)).isEqualTo(2 * big);
        assertThat(calc.calculateCost(new TestPlan("p", List.of(o, a, b)))).isEqualTo(2 * big);
        assertThat(calc.getClosestNodes("O")).containsExactly(o, a, b);
        assertThat(calc.getClosestNodeCostRank("A", o, b)).isEqualTo(big);
    }

    @Test
    void reinitializationPublishesACompleteSnapshotAndQueriesReflectIt() {
        TestNode o = TestNode.origin("O", 0, 0);
        TestNode a = TestNode.customer("A", 0, 10);
        TestNode b = TestNode.customer("B", 0, 20);
        MatrixCostCalculation calc = new MatrixCostCalculation();
        calc.initializeCostMatrix(List.of(o, a));
        assertThat(calc.calculateCost(o, a)).isEqualTo(10);
        assertThatThrownBy(() -> calc.calculateCost(o, b)).isInstanceOf(IllegalStateException.class);

        calc.initializeCostMatrix(List.of(o, a, b));

        assertThat(calc.calculateCost(o, b)).isEqualTo(20);
        assertThat(calc.getClosestNodes("O")).containsExactly(o, a, b);
        assertThatThrownBy(() -> new MatrixCostCalculation().getClosestNodes("O"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not initialized");
    }

    @Test
    void queriesRunningDuringReinitializationNeverSeeAMixedState() throws Exception {
        // Two alternating node sets with different sizes. Each query must be answered from one complete snapshot:
        // a ranking is exactly one of the two sets in cost order, and a pair present in both sets always costs the
        // same. Readers make single calls only; two separate calls may legitimately straddle a swap.
        TestNode o = TestNode.origin("O", 0, 0);
        TestNode a = TestNode.customer("A", 0, 10);
        List<TestNode> small = List.of(o, a);
        List<TestNode> large = List.of(o, a, TestNode.customer("B", 0, 20), TestNode.customer("C", 0, 30));
        MatrixCostCalculation calc = new MatrixCostCalculation();
        calc.initializeCostMatrix(small);
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger torn = new java.util.concurrent.atomic.AtomicInteger();
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread writer = Thread.ofPlatform().start(() -> {
            for (int i = 0; i < 2_000; i++) {
                calc.initializeCostMatrix(i % 2 == 0 ? large : small);
            }
            stop.set(true);
        });
        List<Thread> readers = new ArrayList<>();
        for (int r = 0; r < 4; r++) {
            readers.add(Thread.ofPlatform().start(() -> {
                try {
                    while (!stop.get()) {
                        List<TestNode> ranking = calc.getClosestNodes("O");
                        if (!ranking.equals(small) && !ranking.equals(large)) {
                            torn.incrementAndGet();
                        }
                        if (calc.calculateCost(o, a) != 10 || calc.calculateCost(a, o) != 10) {
                            torn.incrementAndGet();
                        }
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }
        writer.join();
        for (Thread reader : readers) {
            reader.join();
        }
        assertThat(failures).isEmpty();
        assertThat(torn.get()).isZero();
    }

    @Test
    void cellsThatFitInAnIntAreStoredCompactlyAndLargerOnesAreNot() {
        MatrixCostCalculation small = new MatrixCostCalculation();
        small.initializeCostMatrix(List.of(TestNode.origin("O", 0, 0), TestNode.customer("A", 0, 10)));
        assertThat(small.isCompactStorage()).isTrue();

        long big = 3_000_000_000L;
        PathCostCalculation<TestPlan, TestNode, Long> wide = new PathCostCalculation<>(OptimizationType.NO_RESTRICTION,
                Map.of()) {
            @Override
            protected Long[][] getCostMatrix(List<TestNode> coordinates) {
                return new Long[][] {{0L, big}, {big, 0L}};
            }
        };
        wide.initializeCostMatrix(List.of(TestNode.origin("O", 0, 0), TestNode.customer("A", 0, 1)));
        assertThat(wide.isCompactStorage()).isFalse();
        assertThat(wide.calculateCost(TestNode.origin("O", 0, 0), TestNode.customer("A", 0, 1))).isEqualTo(big);
        assertThatThrownBy(() -> new MatrixCostCalculation().isCompactStorage())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void duplicateNodeCodesAreRejected() {
        MatrixCostCalculation calc = new MatrixCostCalculation();
        assertThatThrownBy(() -> calc.initializeCostMatrix(List.of(TestNode.origin("O", 0, 0), TestNode.customer("O", 0,
                1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate node code");
    }

    @Test
    void aMedoidRequiresAtLeastOneCustomer() {
        TestNode o = TestNode.origin("O", 0, 0);
        MatrixCostCalculation calc = new MatrixCostCalculation();
        calc.initializeCostMatrix(List.of(o));
        assertThatThrownBy(() -> calc.getMedoid(new TestPlan("empty", List.of(o))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no customers");
    }

}
