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
import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.domain.Planable;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cost model backed by an all-pairs cost matrix plus a per-node "closest nodes" ranking, zone restriction predicates
 * and medoids. This is the shared state almost every algorithm consults; consumers subclass it and supply the raw
 * matrix through {@link #getCostMatrix(List)} (for example from
 * {@link com.github.liliumsoftware.optimization.routing.RoutingProvider}).
 *
 * <p><strong>Lifecycle.</strong> {@link #initializeCostMatrix(List)} must run with <em>every</em> node of the run
 * (depots and customers) before any matrix query; until then every query that reads the matrix throws
 * {@link IllegalStateException} (the zone predicates do not need it). Initialization builds, for the given node set,
 * (1) the dense cost matrix and (2) for each node, the other nodes sorted by ascending cost: the neighbour ordering
 * that the exchange/relocate/destroy-repair heuristics use to limit candidate moves to a node's nearest neighbours.
 * Nodes are looked up by {@link Nodeable#getCode()}, so codes must be unique within the set, and a query about a node
 * whose code was not in the set throws {@link IllegalStateException}.
 *
 * <p><strong>Provider cost cell {@code C} and storage.</strong> {@link #getCostMatrix(List)} returns raw provider
 * cells (an {@code int[][]} from a routing service, a {@code Long[][]}, a nested array, …); {@link #getValue(Object)}
 * unwraps each cell to a scalar {@code long}. Costs are integral (milliseconds, metres, provider units), never
 * floating point. The matrix is kept as one flat primitive array: an {@code int[]} when every cell fits in 32 bits
 * (4 bytes per cell, enough for about 24.8 days in milliseconds or 2 147 483 km in metres per edge), otherwise a
 * {@code long[]} (8 bytes per cell). Sums over several edges are always computed in {@code long}, so a plan or a
 * plan set may exceed the {@code int} range regardless of how the cells are stored.
 *
 * <p><strong>Thread-safety.</strong> The matrix and the ranking are built privately and published together as one
 * immutable snapshot through a single {@code volatile} field, and all query methods are read-only, so any number of
 * threads may query concurrently. A single query ({@link #calculateCost(Nodeable, Nodeable)},
 * {@link #getClosestNodes}, {@link #getClosestNodeRank}, {@link #getClosestNodeCostRank},
 * {@link #isCompactStorage}) reads one snapshot, so it sees either the complete old state or the complete new one even
 * while {@link #initializeCostMatrix(List)} runs on another thread. Computations over several edges
 * ({@link CostCalculation#calculateCost(Planable)}, {@link #getMedoid}, {@link #calculateMedoidToOthersCost}, and the
 * algorithms' own sequences of queries) read the current snapshot once per edge, so re-initializing concurrently may
 * make them mix costs of the old and the new matrix, or throw {@link IllegalStateException} for a node missing from
 * the new one: re-initialize only while no planning run uses the instance. The caller-supplied
 * {@link #neighborsMatrix} is kept by reference, only read, and must not be mutated while in use.
 *
 * <p><strong>Zones.</strong> {@link #isSameZone} / {@link #isNeighbor} return {@code true} unconditionally
 * under {@link OptimizationType#NO_RESTRICTION} or when either zone is {@code null} (a {@code null} zone opts out
 * of restriction on both sides), so zone-unaware callers get the unrestricted behaviour for free; otherwise moves
 * are confined to a zone or to neighbouring zones. The neighbour relation is directed and taken literally from the
 * neighbours matrix: under {@link OptimizationType#LOCATION_ZONE} a zone is <em>not</em> its own neighbour unless its
 * list names it.
 *
 * @param <P> the plan type
 * @param <N> the node type
 * @param <C> the raw provider cost-cell type
 */
public abstract class PathCostCalculation<P
        extends Planable<P, N, ?>, N extends Nodeable<N>, C> implements CostCalculation<P, N> {

    /**
     * The largest matrix the flat cell array can hold: a little below {@link Integer#MAX_VALUE}, the practical
     * maximum length of a Java array.
     */
    private static final long MAX_CELLS = Integer.MAX_VALUE - 8;

    private static final BigDecimal LONG_MIN = BigDecimal.valueOf(Long.MIN_VALUE);
    private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);
    /**
     * Deepest array nesting {@link #getValue} unwraps; a deeper (or cyclic, self-containing) cell is rejected.
     */
    private static final int MAX_CELL_NESTING = 32;

    /**
     * The zone policy of the zone predicates; may be {@code null}, which applies zone restrictions as
     * {@link OptimizationType#LOCATION_ZONE} does.
     */
    protected final OptimizationType optimizationType;
    /**
     * For each zone, the zones it neighbours (a directed relation, taken literally); the caller's map, kept by
     * reference.
     */
    protected final Map<String, List<String>> neighborsMatrix;
    private volatile State<N> state;

    /**
     * Creates a calculation with the given zone policy; the matrix is built later by {@link #initializeCostMatrix}.
     *
     * @param optimizationType whether zone restrictions apply; {@code null} is not rejected and behaves like
     *                         {@link OptimizationType#LOCATION_ZONE}
     * @param neighborsMatrix  for each zone, the zones it neighbours (directed; a zone is its own neighbour only if
     *                         listed); use an empty map when there are no zones. Kept by reference, not copied
     * @throws NullPointerException if {@code neighborsMatrix} is {@code null}
     */
    public PathCostCalculation(OptimizationType optimizationType, Map<String, List<String>> neighborsMatrix) {
        this.optimizationType = optimizationType;
        this.neighborsMatrix = Objects.requireNonNull(neighborsMatrix,
                "neighborsMatrix must not be null; use an empty map when there are no zones");
    }

    private static IllegalStateException outOfLongRange(Number number) {
        return new IllegalStateException("Cost cell must fit a long, got " + number);
    }

    /**
     * Row {@code row} of a 32-bit matrix ranked: {@code row} itself, then every other column by ascending cost, ties
     * by column. Each column is packed with its cost into one {@code long} ({@code cost << 32 | column}; the cost is
     * signed and the column below 2<sup>31</sup>, so the packed order is exactly cost, then column), which sorts as
     * primitives without boxing a comparator per cell.
     */
    private static int[] rankRow(int[] cells, int n, int row) {
        long[] keys = new long[n - 1];
        int k = 0;
        for (int j = 0; j < n; j++) {
            if (j != row) {
                keys[k++] = ((long) cells[row * n + j] << 32) | j;
            }
        }
        Arrays.sort(keys);
        int[] order = new int[n];
        order[0] = row;
        for (int m = 0; m < keys.length; m++) {
            order[m + 1] = (int) keys[m];
        }
        return order;
    }

    /**
     * Like {@link #rankRow(int[], int, int)} for a 64-bit matrix, whose costs leave no room to pack the column; the
     * stable sort keeps ties in column order.
     */
    private static int[] rankRow(long[] cells, int n, int row) {
        Integer[] others = new Integer[n - 1];
        int k = 0;
        for (int j = 0; j < n; j++) {
            if (j != row) {
                others[k++] = j;
            }
        }
        Arrays.sort(others, Comparator.comparingLong(j -> cells[row * n + j]));
        int[] order = new int[n];
        order[0] = row;
        for (int m = 0; m < others.length; m++) {
            order[m + 1] = others[m];
        }
        return order;
    }

    /**
     * Position of {@code medoid} in {@code customers}: the first entry that is the same instance, else the first one
     * {@code equals} to it, else {@code -1}.
     */
    private static <N> int positionOf(List<N> customers, N medoid) {
        for (int i = 0; i < customers.size(); i++) {
            if (customers.get(i) == medoid) {
                return i;
            }
        }
        return customers.indexOf(medoid);
    }

    private State<N> requireState() {
        State<N> current = state;
        if (current == null) {
            throw new IllegalStateException("Cost matrix is not initialized");
        }
        return current;
    }

    /**
     * Cost of the directed edge {@code start -> end} as read from the matrix, looked up by node code.
     *
     * @param start the node the edge leaves
     * @param end   the node the edge enters
     * @return the matrix cell for the two codes
     * @throws IllegalStateException if the matrix is uninitialized or either node code is unknown to it
     */
    @Override
    public long calculateCost(N start, N end) {
        State<N> current = requireState();
        return current.cost(current.indexOf(start.getCode()), current.indexOf(end.getCode()));
    }

    /**
     * Builds the cost matrix and per-node closest-node ranking for {@code nodes}. Must be called with every node of
     * the run before any matrix query. The node count and the uniqueness of the codes are checked first, so an invalid
     * set never costs a (possibly remote) {@link #getCostMatrix} call; that hook is then called exactly once and each
     * cell is unwrapped with {@link #getValue}. Rebuilding replaces the previous state with one volatile write (see
     * the class description for what concurrent queries observe); on failure the previous state stays in place.
     *
     * <p>Cost: O(n² log n) time. Memory: the {@code n²} cells at 4 or 8 bytes each (see the class description), plus
     * {@code n²} references for the per-node rankings (each ranking lists every node), plus the provider's own
     * matrix while this method runs.
     *
     * @param nodes every node of the run, depots included; not modified, and the ranking keeps references to these
     *              instances
     * @throws IllegalArgumentException if two nodes share a code, or if there are so many nodes that the {@code n × n}
     *                                  matrix cannot be held in one array (more than 46 340); both checked before
     *                                  {@link #getCostMatrix} is called
     * @throws IllegalStateException    if {@link #getCostMatrix} returns a matrix that is not {@code n × n}, or a cell
     *                                  that is {@code null}, not a number, not finite or outside the {@code long} range
     *                                  (see {@link #getValue})
     */
    public void initializeCostMatrix(List<N> nodes) {
        int n = nodes.size();
        if ((long) n * n > MAX_CELLS) {
            throw new IllegalArgumentException("A cost matrix for " + n + " nodes needs " + ((long) n * n)
                    + " cells, more than one array can hold (at most " + (long) Math.sqrt(MAX_CELLS) + " nodes)");
        }
        // Checked before the provider is asked: a duplicate would otherwise cost a full (possibly remote) matrix call.
        Map<String, Integer> index = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            Integer previous = index.putIfAbsent(nodes.get(i).getCode(), i);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate node code in cost matrix: " + nodes.get(i).getCode());
            }
        }
        C[][] matrix = getCostMatrix(nodes);
        if (matrix == null || matrix.length != n) {
            throw new IllegalStateException("Cost matrix must have one row per node (" + n + "), got "
                    + (matrix == null ? "null" : matrix.length));
        }
        for (int i = 0; i < n; i++) {
            if (matrix[i] == null || matrix[i].length != n) {
                throw new IllegalStateException("Cost matrix row " + i + " must have one column per node ("
                        + n + "), got " + (matrix[i] == null ? "null" : matrix[i].length));
            }
        }

        // Cells go straight into an int[]; only the first value outside the int range widens the matrix to a long[],
        // so the common case never holds both arrays.
        int[] intCells = new int[n * n];
        long[] longCells = null;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                long value;
                try {
                    value = getValue(matrix[i][j]);
                } catch (IllegalStateException e) {
                    throw new IllegalStateException("Invalid cost cell [" + i + "][" + j + "] (" + nodes.get(i)
                            .getCode()
                            + " -> " + nodes.get(j).getCode() + "): " + e.getMessage(), e);
                }
                int k = i * n + j;
                if (longCells != null) {
                    longCells[k] = value;
                } else if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                    intCells[k] = (int) value;
                } else {
                    longCells = new long[n * n];
                    for (int m = 0; m < k; m++) {
                        longCells[m] = intCells[m];
                    }
                    longCells[k] = value;
                    intCells = null;
                }
            }
        }

        // Ranking: for each node, the node itself first, then all others by ascending cost from it; ties keep matrix
        // order. Self is pinned first because a co-located node also costs 0 and may come earlier.
        List<List<N>> closest = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int[] order = intCells != null ? rankRow(intCells, n, i) : rankRow(longCells, n, i);
            List<N> ranking = new ArrayList<>(n);
            for (int j : order) {
                ranking.add(nodes.get(j));
            }
            closest.add(Collections.unmodifiableList(ranking));
        }
        // Single volatile write: readers see the old snapshot or the new one, never one of each.
        state = new State<>(Collections.unmodifiableMap(index), intCells, longCells,
                Collections.unmodifiableList(closest));
    }

    /**
     * Whether the current matrix is held as 32-bit cells (every cost fit in an {@code int}) rather than 64-bit ones.
     *
     * @return {@code true} for an {@code int[]} matrix, {@code false} for a {@code long[]} one
     * @throws IllegalStateException if the matrix is uninitialized
     */
    public boolean isCompactStorage() {
        return requireState().intCells() != null;
    }

    /**
     * The node itself first, then every other node ordered by ascending cost from {@code code}, ties in the order the
     * nodes were given to {@link #initializeCostMatrix}. The node is first even when another node shares its location
     * (also at cost 0).
     *
     * @param code the code of the node whose ranking is wanted
     * @return an unmodifiable list of every node of the matrix (the instances given to {@link #initializeCostMatrix})
     * @throws IllegalStateException if the matrix is uninitialized or the code is unknown to it
     */
    public List<N> getClosestNodes(String code) {
        State<N> current = requireState();
        return current.closest().get(current.indexOf(code));
    }

    /**
     * Whether {@code candidateZoneName} is the same zone as {@code zoneName}; always {@code true} under
     * {@link OptimizationType#NO_RESTRICTION} or when either zone is {@code null}, so the predicate is symmetric.
     * Does not need the cost matrix.
     *
     * @param zoneName          the reference zone, or {@code null}
     * @param candidateZoneName the zone to test, or {@code null}
     * @return whether the two zones may be combined as one zone
     */
    public boolean isSameZone(String zoneName, String candidateZoneName) {
        if (optimizationType == OptimizationType.NO_RESTRICTION || zoneName == null || candidateZoneName == null) {
            return true;
        }
        return Objects.equals(zoneName, candidateZoneName);
    }

    /**
     * Whether {@code neighborCandidateZoneName} is listed as a neighbour of {@code zoneName} in the neighbours
     * matrix; always {@code true} under {@link OptimizationType#NO_RESTRICTION} or when either zone is {@code null},
     * and {@code false} for a zone without an entry in the matrix. The relation is directed (callers that need "either
     * way" ask both directions), and a zone is not its own neighbour unless the matrix says so. Does not need the
     * cost matrix.
     *
     * @param zoneName                  the zone whose neighbour list is consulted, or {@code null}
     * @param neighborCandidateZoneName the zone to look for in that list, or {@code null}
     * @return whether moves between the two zones are allowed
     */
    public boolean isNeighbor(String zoneName, String neighborCandidateZoneName) {
        if (optimizationType == OptimizationType.NO_RESTRICTION || zoneName == null
                || neighborCandidateZoneName == null) {
            return true;
        }
        List<String> neighbors = neighborsMatrix.get(zoneName);
        return neighbors != null && neighbors.contains(neighborCandidateZoneName);
    }

    /**
     * Rank (0-based position in {@code code}'s {@link #getClosestNodes closest-node ordering}) of the worse-ranked of
     * the stop's route neighbours {@code before} and {@code after}: a proxy for "how badly placed is this stop between
     * its neighbours", used by destroy-repair to pick customers to remove (higher rank, more promising). A neighbour
     * that is a depot ({@code before} an {@link Nodeable.Type#ORIGIN}, {@code after} a
     * {@link Nodeable.Type#DESTINATION}) or missing ({@code null}: before the first stop of a plan without an origin,
     * after the last stop of a plan without a destination) is ignored; with neither side counting the rank is
     * {@code 0}. Reads one snapshot.
     *
     * @param code   the code of the stop being judged
     * @param before the stop preceding it in the plan, or {@code null}
     * @param after  the stop following it in the plan, or {@code null}
     * @return the larger rank of the counted neighbours, or {@code 0}
     * @throws IllegalStateException if the matrix is uninitialized, or {@code code} or the code of a counted neighbour
     *                               is unknown to it
     */
    public int getClosestNodeRank(String code, N before, N after) {
        List<N> closest = getClosestNodes(code);
        if (after == null || after.getType() == Nodeable.Type.DESTINATION) {
            if (before == null || before.getType() == Nodeable.Type.ORIGIN) {
                return 0;
            }
            return rankOf(closest, before);
        }
        if (before == null || before.getType() == Nodeable.Type.ORIGIN) {
            return rankOf(closest, after);
        }
        return Math.max(rankOf(closest, before), rankOf(closest, after));
    }

    private int rankOf(List<N> closest, N node) {
        for (int i = 0; i < closest.size(); i++) {
            if (closest.get(i).getCode().equals(node.getCode())) {
                return i;
            }
        }
        throw new IllegalStateException("Cost matrix does not contain a node with code: " + node.getCode());
    }

    /**
     * Like {@link #getClosestNodeRank} but returns the larger of the costs from the stop to its counted neighbours
     * ({@code cost(code, neighbour)}) instead of a rank; {@code 0} when neither neighbour counts. Reads one snapshot.
     *
     * @param code   the code of the stop being judged
     * @param before the stop preceding it in the plan, or {@code null}
     * @param after  the stop following it in the plan, or {@code null}
     * @return the larger edge cost to the counted neighbours, or {@code 0}
     * @throws IllegalStateException if the matrix is uninitialized, or {@code code} or the code of a counted neighbour
     *                               is unknown to it
     */
    public long getClosestNodeCostRank(String code, N before, N after) {
        State<N> current = requireState();
        int row = current.indexOf(code);
        if (after == null || after.getType() == Nodeable.Type.DESTINATION) {
            if (before == null || before.getType() == Nodeable.Type.ORIGIN) {
                return 0;
            }
            return current.cost(row, current.indexOf(before.getCode()));
        }
        if (before == null || before.getType() == Nodeable.Type.ORIGIN) {
            return current.cost(row, current.indexOf(after.getCode()));
        }
        return Math.max(current.cost(row, current.indexOf(before.getCode())), current.cost(row,
                current.indexOf(after.getCode())));
    }

    /**
     * The plan's medoid: the customer whose summed cost to every <em>other</em> customer of the plan
     * ({@code calculateCost(candidate, other)}; the candidate's own entry is skipped by position, so a different
     * customer that is {@code equals} to it still counts) is smallest. Ties keep
     * the first such customer in route order. Edge costs come from {@link #calculateCost(Nodeable, Nodeable)}, one
     * snapshot read per edge (see the class description).
     *
     * @param plan the plan whose customers form the cluster; not modified
     * @return the medoid, one of the plan's own customer instances
     * @throws IllegalArgumentException if the plan has no customers
     * @throws IllegalStateException    if the matrix is uninitialized or a customer code is unknown to it
     * @throws ArithmeticException      if a candidate's sum does not fit a {@code long}
     */
    public N getMedoid(P plan) {
        List<N> customers = plan.getCustomers();
        if (customers.isEmpty()) {
            throw new IllegalArgumentException("Plan " + plan.getCode() + " has no customers, so it has no medoid");
        }
        N medoid = null;
        long best = 0;
        for (int i = 0; i < customers.size(); i++) {
            N candidate = customers.get(i);
            long sum = 0;
            for (int j = 0; j < customers.size(); j++) {
                if (j != i) {
                    sum = CostSums.add(sum, calculateCost(candidate, customers.get(j)), "the medoid sums of plan",
                            plan.getCode());
                }
            }
            if (medoid == null || sum < best) {
                best = sum;
                medoid = candidate;
            }
        }
        return medoid;
    }

    /**
     * Sum of the costs from the plan's {@link #getMedoid medoid} to each of its other customers: a compactness
     * measure of the plan's cluster, and exactly the sum {@link #getMedoid} minimised. Like {@link #getMedoid}, it
     * skips the medoid by position, not by {@code equals}: only the medoid's own entry in the customer list (the first
     * entry that is the same instance, or failing that the first one {@code equals} to it, for an overridden
     * {@link #getMedoid} that returns a copy) is left out, so another customer that is {@code equals} to the medoid
     * still counts.
     *
     * @param plan the plan whose customers form the cluster; not modified
     * @return the medoid's summed cost to the other customers, {@code 0} for a single customer
     * @throws IllegalArgumentException if the plan has no customers
     * @throws IllegalStateException    if the matrix is uninitialized or a customer code is unknown to it
     * @throws ArithmeticException      if a sum does not fit a {@code long}
     */
    public long calculateMedoidToOthersCost(P plan) {
        N medoid = getMedoid(plan);
        List<N> customers = plan.getCustomers();
        int position = positionOf(customers, medoid);
        long sum = 0;
        for (int i = 0; i < customers.size(); i++) {
            if (i != position) {
                sum = CostSums.add(sum, calculateCost(medoid, customers.get(i)), "the medoid sums of plan",
                        plan.getCode());
            }
        }
        return sum;
    }

    /**
     * Unwraps a possibly nested array cost cell (following index 0 at each level, at most 32 levels deep) to its
     * scalar value. Any
     * {@link Number} is accepted and read with {@link Number#longValue()}, so cells may exceed the {@code int} range;
     * a fractional value is truncated toward zero ({@code 5.9} becomes {@code 5}, {@code 0.9} becomes a free edge
     * {@code 0}), which is why costs should use an integral unit such as milliseconds or metres. Negative values are
     * accepted as given (the algorithms compare sums, so a negative edge simply makes a move more attractive); it is
     * the provider's job not to produce them unless intended. Override to support other cell types.
     *
     * @param cost the raw provider cell
     * @return the cell's value as a {@code long}
     * @throws IllegalStateException if the cell (or its innermost element) is {@code null}, an empty array, not a
     *                               {@link Number}, a floating-point {@code NaN} or infinity, or a value outside the
     *                               {@code long} range (a {@code double}/{@code float} or {@link BigDecimal} beyond it,
     *                               a {@link BigInteger} that does not fit): such a cell would otherwise silently
     *                               become a free edge, saturate to {@link Long#MAX_VALUE} or wrap around; also if
     *                               the arrays are nested more than 32 levels deep, which includes a cyclic cell (an
     *                               {@code Object[]} that contains itself at index 0) that would otherwise be
     *                               followed forever
     */
    protected long getValue(C cost) {
        Object o = cost;
        int depth = 0;
        while (o != null && o.getClass().isArray()) {
            if (++depth > MAX_CELL_NESTING) {
                throw new IllegalStateException("Cost cell is nested more than " + MAX_CELL_NESTING
                        + " arrays deep (or contains itself)");
            }
            o = Array.getLength(o) == 0 ? null : Array.get(o, 0);
        }
        if (!(o instanceof Number number)) {
            throw new IllegalStateException("Cost cell must be a Number or an array of Numbers, got "
                    + (o == null ? "null" : o.getClass().getName()));
        }
        if ((number instanceof Double || number instanceof Float) && !Double.isFinite(number.doubleValue())) {
            throw new IllegalStateException("Cost cell must be a finite number, got " + number);
        }
        if (number instanceof Double || number instanceof Float) {
            double value = number.doubleValue();
            // 2^63 is the first double above Long.MAX_VALUE; -2^63 is Long.MIN_VALUE itself
            if (value >= 0x1p63 || value < -0x1p63) {
                throw outOfLongRange(number);
            }
        } else if (number instanceof BigInteger integer) {
            if (integer.bitLength() > 63) {
                throw outOfLongRange(number);
            }
        } else if (number instanceof BigDecimal decimal) {
            if (decimal.compareTo(LONG_MIN) < 0 || decimal.compareTo(LONG_MAX) > 0) {
                throw outOfLongRange(number);
            }
        }
        return number.longValue();
    }

    /**
     * Supplies the raw all-pairs cost matrix for {@code coordinates}; called once by each
     * {@link #initializeCostMatrix(List)}, after the node list passed its checks. Cell {@code [i][j]} is the cost of
     * the directed edge from {@code coordinates.get(i)} to {@code coordinates.get(j)}, unwrapped by
     * {@link #getValue}. Exceptions thrown here propagate out of {@link #initializeCostMatrix(List)} unchanged.
     *
     * @param coordinates every node of the run, in matrix order; must not be modified
     * @return an {@code n × n} matrix for the {@code n} nodes; any other shape, or a {@code null} matrix or row, makes
     *         {@link #initializeCostMatrix(List)} throw {@link IllegalStateException}
     */
    protected abstract C[][] getCostMatrix(List<N> coordinates);

    /**
     * One complete, immutable initialization result. {@code index} maps a node code to its row/column; exactly one
     * of {@code intCells} / {@code longCells} is non-null and holds the {@code n × n} matrix row-major.
     */
    private record State<N>(Map<String, Integer> index, int[] intCells, long[] longCells, List<List<N>> closest) {

        int size() {
            return index.size();
        }

        long cost(int i, int j) {
            int k = i * size() + j;
            return intCells != null ? intCells[k] : longCells[k];
        }

        int indexOf(String code) {
            Integer i = index.get(code);
            if (i == null) {
                throw new IllegalStateException("Cost matrix does not contain a node with code: " + code);
            }
            return i;
        }
    }

}
