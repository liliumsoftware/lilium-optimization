package ir.baho.algorithm.calculation;

import ir.baho.algorithm.OptimizationType;
import ir.baho.algorithm.domain.Nodeable;
import ir.baho.algorithm.domain.Planable;

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
 * Cost model backed by an all-pairs cost matrix plus a per-node "closest nodes" ranking and zone
 * restriction predicates. This is the shared state almost every algorithm consults.
 *
 * <p><strong>Lifecycle.</strong> {@link #initializeCostMatrix(List)} must run before any cost query;
 * until then every query throws {@link IllegalStateException}. Initialization builds, for the given node set,
 * (1) the dense cost matrix and (2) for each node, the other nodes sorted by ascending cost — the neighbour
 * ordering that the exchange/relocate/destroy-repair heuristics use to limit candidate moves to a node's nearest
 * neighbours. Node codes must be unique within the set.
 *
 * <p><strong>Provider cost cell {@code C} and storage.</strong> {@link #getCostMatrix(List)} returns raw provider
 * cells (an {@code int[][]} from a routing service, a {@code Long[][]}, a nested array, …); {@link #getValue(Object)}
 * unwraps each cell to a scalar {@code long}. Costs are integral (milliseconds, metres, provider units), never
 * floating point. The matrix is kept as one flat primitive array: an {@code int[]} when every cell fits in 32 bits
 * (4 bytes per cell, enough for about 24.8 days in milliseconds or 2 147 km in metres per edge), otherwise a
 * {@code long[]} (8 bytes per cell). Sums over several edges are always computed in {@code long}, so a plan or a
 * plan set may exceed the {@code int} range regardless of how the cells are stored.
 *
 * <p><strong>Thread-safety.</strong> The matrix and the ranking are built privately and published together as one
 * immutable snapshot through a single {@code volatile} field, so {@link #initializeCostMatrix(List)} may be called
 * at any time, from any thread, while other threads query: every query sees either the complete old state or the
 * complete new one, never a mix. All query methods are read-only. The caller-supplied {@code neighborsMatrix} is
 * only read and must not be mutated while in use.
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

    protected final OptimizationType optimizationType;
    protected final Map<String, List<String>> neighborsMatrix;
    private volatile State<N> state;

    /**
     * Creates a calculation with the given zone policy; the matrix is built later by {@link #initializeCostMatrix}.
     *
     * @param optimizationType whether zone restrictions apply
     * @param neighborsMatrix  for each zone, the zones it neighbours (directed; a zone is its own neighbour only if
     *                         listed); use an empty map when there are no zones
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

    private State<N> requireState() {
        State<N> current = state;
        if (current == null) {
            throw new IllegalStateException("Cost matrix is not initialized");
        }
        return current;
    }

    /**
     * Cost of the directed edge {@code start -> end} as read from the matrix.
     *
     * @throws IllegalStateException if the matrix is uninitialized or either node code is unknown to it
     */
    @Override
    public long calculateCost(N start, N end) {
        State<N> current = requireState();
        return current.cost(current.indexOf(start.getCode()), current.indexOf(end.getCode()));
    }

    /**
     * Builds the cost matrix and per-node closest-node ranking for {@code nodes}. Must be called before
     * any cost query. Rebuilding replaces the previous state atomically. O(n² log n) time, O(n²) space
     * (4 or 8 bytes per cell, see the class description).
     *
     * @throws IllegalStateException    if {@link #getCostMatrix} returns a matrix that is not {@code n × n}, or a cell
     *                                  that is {@code null}, not a number or not finite (see {@link #getValue})
     * @throws IllegalArgumentException if two nodes share a code, or if there are so many nodes that the {@code n × n}
     *                                  matrix cannot be held in one array (more than 46 340); checked before
     *                                  {@link #getCostMatrix} is asked for the matrix
     */
    public void initializeCostMatrix(List<N> nodes) {
        int n = nodes.size();
        if ((long) n * n > MAX_CELLS) {
            throw new IllegalArgumentException("A cost matrix for " + n + " nodes needs " + ((long) n * n)
                    + " cells, more than one array can hold (at most " + (long) Math.sqrt(MAX_CELLS) + " nodes)");
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
        Map<String, Integer> index = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            Integer previous = index.putIfAbsent(nodes.get(i).getCode(), i);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate node code in cost matrix: " + nodes.get(i).getCode());
            }
        }

        long[] cells = new long[n * n];
        boolean fitsInt = true;
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
                cells[i * n + j] = value;
                fitsInt &= value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
            }
        }
        int[] intCells = null;
        if (fitsInt) {
            intCells = new int[cells.length];
            for (int k = 0; k < cells.length; k++) {
                intCells[k] = (int) cells[k];
            }
        }

        // Ranking: for each node, the node itself first, then all others by ascending cost from it; ties keep matrix
        // order (stable sort). Self is pinned first because a co-located node also costs 0 and may come earlier.
        List<List<N>> closest = new ArrayList<>(n);
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            final int row = i;
            for (int j = 0; j < n; j++) {
                order[j] = j;
            }
            Arrays.sort(order, Comparator.comparingInt((Integer a) -> a == row ? 0 : 1)
                    .thenComparingLong(a -> cells[row * n + a]));
            List<N> ranking = new ArrayList<>(n);
            for (Integer j : order) {
                ranking.add(nodes.get(j));
            }
            closest.add(Collections.unmodifiableList(ranking));
        }
        // Single volatile write: readers see the old snapshot or the new one, never one of each.
        state = new State<>(Collections.unmodifiableMap(index), intCells, fitsInt ? null : cells,
                Collections.unmodifiableList(closest));
    }

    /**
     * Whether the current matrix is held as 32-bit cells (every cost fit in an {@code int}) rather than 64-bit ones.
     *
     * @throws IllegalStateException if the matrix is uninitialized
     */
    public boolean isCompactStorage() {
        return requireState().intCells() != null;
    }

    /**
     * The node itself first, then every other node ordered by ascending cost from {@code code}, ties in matrix order;
     * unmodifiable. The node is first even when another node shares its location (also at cost 0).
     *
     * @throws IllegalStateException if the matrix is uninitialized or the code is unknown to it
     */
    public List<N> getClosestNodes(String code) {
        State<N> current = requireState();
        return current.closest().get(current.indexOf(code));
    }

    /**
     * Whether {@code candidateZoneName} is the same zone as {@code zoneName}; always {@code true} under
     * {@link OptimizationType#NO_RESTRICTION} or when either zone is {@code null}, so the predicate is symmetric.
     */
    public boolean isSameZone(String zoneName, String candidateZoneName) {
        if (optimizationType == OptimizationType.NO_RESTRICTION || zoneName == null || candidateZoneName == null) {
            return true;
        }
        return Objects.equals(zoneName, candidateZoneName);
    }

    /**
     * Whether {@code neighborCandidateZoneName} is listed as a neighbour of {@code zoneName} in the neighbours
     * matrix; always {@code true} under {@link OptimizationType#NO_RESTRICTION} or when either zone is {@code null}.
     * The relation is directed (callers that need "either way" ask both directions), and a zone is not its own
     * neighbour unless the matrix says so.
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
     * Rank (0-based position in {@code code}'s closest-node ordering) of {@code code}'s worst neighbour
     * among {@code before}/{@code after} — a proxy for "how badly placed is this stop between its
     * neighbours". Origin/destination endpoints are treated as free (rank 0 or ignored), and so is a missing
     * neighbour ({@code null}: before the first stop of a plan without an origin, after the last stop of a plan
     * without a destination). Higher rank means a more promising candidate to destroy and re-insert.
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
        return -1;
    }

    /**
     * Like {@link #getClosestNodeRank} but returns the raw cost to the worse-placed neighbour instead of its rank.
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
     * The plan's customer whose total cost to all other customers is smallest — the cluster's centre.
     *
     * @throws IllegalArgumentException if the plan has no customers
     */
    public N getMedoid(P plan) {
        List<N> customers = plan.getCustomers();
        if (customers.isEmpty()) {
            throw new IllegalArgumentException("Plan " + plan.getCode() + " has no customers, so it has no medoid");
        }
        N medoid = null;
        long best = Long.MAX_VALUE;
        for (N candidate : customers) {
            long sum = 0;
            for (N other : customers) {
                sum = CostSums.add(sum, calculateCost(candidate, other), "the medoid sums of plan", plan.getCode());
            }
            if (sum < best) {
                best = sum;
                medoid = candidate;
            }
        }
        return medoid;
    }

    /**
     * Sum of the costs from the plan's {@link #getMedoid medoid} to each of its other customers — a compactness
     * measure of the plan's cluster.
     *
     * @throws IllegalArgumentException if the plan has no customers
     */
    public long calculateMedoidToOthersCost(P plan) {
        N medoid = getMedoid(plan);
        long sum = 0;
        for (N node : plan.getCustomers()) {
            if (!node.equals(medoid)) {
                sum = CostSums.add(sum, calculateCost(medoid, node), "the medoid sums of plan", plan.getCode());
            }
        }
        return sum;
    }

    /**
     * Unwraps a possibly nested array cost cell (following index 0 at each level) to its scalar value. Any
     * {@link Number} is accepted and read as a {@code long}, so cells may exceed the {@code int} range; a fractional
     * value is truncated. Negative values are accepted as given (the algorithms compare sums, so a negative edge
     * simply makes a move more attractive); it is the provider's job not to produce them unless intended.
     *
     * @throws IllegalStateException if the cell (or its innermost element) is {@code null}, an empty array, not a
     *                               {@link Number}, a floating-point {@code NaN} or infinity, or a value outside the
     *                               {@code long} range (a {@code double}/{@code float} or {@link BigDecimal} beyond it,
     *                               a {@link BigInteger} that does not fit): such a cell would otherwise silently
     *                               become a free edge, saturate to {@link Long#MAX_VALUE} or wrap around
     */
    protected long getValue(C cost) {
        Object o = cost;
        while (o != null && o.getClass().isArray()) {
            o = Array.getLength(o) == 0 ? null : Array.get(o, 0);
        }
        Number number = o instanceof Number ? (Number) o : null;
        if (number == null) {
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
        } else if (number instanceof BigInteger) {
            BigInteger integer = (BigInteger) number;
            if (integer.bitLength() > 63) {
                throw outOfLongRange(number);
            }
        } else if (number instanceof BigDecimal) {
            BigDecimal decimal = (BigDecimal) number;
            if (decimal.compareTo(LONG_MIN) < 0 || decimal.compareTo(LONG_MAX) > 0) {
                throw outOfLongRange(number);
            }
        }
        return number.longValue();
    }

    /**
     * Supplies the raw all-pairs cost matrix for {@code coordinates}, row/column aligned to that list.
     */
    protected abstract C[][] getCostMatrix(List<N> coordinates);

    /**
     * One complete, immutable initialization result. {@code index} maps a node code to its row/column; exactly one
     * of {@code intCells} / {@code longCells} is non-null and holds the {@code n × n} matrix row-major.
     */
    private static final class State<N> {
        private final Map<String, Integer> index;
        private final int[] intCells;
        private final long[] longCells;
        private final List<List<N>> closest;

        /**
         * Creates a {@code State} from its components.
         */
        public State(Map<String, Integer> index, int[] intCells, long[] longCells, List<List<N>> closest) {
            this.index = index;
            this.intCells = intCells;
            this.longCells = longCells;
            this.closest = closest;
        }

        /**
         * Returns the {@code index} component.
         */
        public Map<String, Integer> index() {
            return index;
        }

        /**
         * Returns the {@code intCells} component.
         */
        public int[] intCells() {
            return intCells;
        }

        /**
         * Returns the {@code longCells} component.
         */
        public long[] longCells() {
            return longCells;
        }

        /**
         * Returns the {@code closest} component.
         */
        public List<List<N>> closest() {
            return closest;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof State)) {
                return false;
            }
            State<?> other = (State<?>) o;
            return java.util.Objects.equals(index, other.index)
                    && java.util.Arrays.equals(intCells, other.intCells)
                    && java.util.Arrays.equals(longCells, other.longCells)
                    && java.util.Objects.equals(closest, other.closest);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(index, java.util.Arrays.hashCode(intCells),
                    java.util.Arrays.hashCode(longCells), closest);
        }

        @Override
        public String toString() {
            return "State["
                    + "index=" + index
                    + ", intCells=" + java.util.Arrays.toString(intCells)
                    + ", longCells=" + java.util.Arrays.toString(longCells)
                    + ", closest=" + closest
                    + "]";
        }

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
