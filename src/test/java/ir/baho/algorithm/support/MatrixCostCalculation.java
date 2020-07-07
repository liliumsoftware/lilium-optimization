package ir.baho.algorithm.support;

import ir.baho.algorithm.OptimizationType;
import ir.baho.algorithm.calculation.PathCostCalculation;

import java.util.List;
import java.util.Map;

/**
 * Concrete {@link PathCostCalculation} backed by the Manhattan distance between {@link TestNode} positions —
 * a deterministic integer metric. The cost-cell type is {@code Object} so tests can also exercise
 * {@link #exposeGetValue} with nested arrays.
 *
 * <p>The plan-level {@code calculateCost(plan)} is deliberately <em>not</em> overridden, so the interface
 * default (sum of consecutive edge costs) is what the tests exercise — and {@link TestPlan#getCost()} derives
 * the same value from its current nodes, keeping the two definitions in agreement by construction.
 */
public class MatrixCostCalculation extends PathCostCalculation<TestPlan, TestNode, Object> {

    public MatrixCostCalculation() {
        this(OptimizationType.NO_RESTRICTION, Map.of());
    }

    public MatrixCostCalculation(OptimizationType optimizationType, Map<String, List<String>> neighborsMatrix) {
        super(optimizationType, neighborsMatrix);
    }

    @Override
    protected Object[][] getCostMatrix(List<TestNode> coordinates) {
        int n = coordinates.size();
        Object[][] matrix = new Object[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                matrix[i][j] = coordinates.get(i).manhattanTo(coordinates.get(j));
            }
        }
        return matrix;
    }

    /**
     * Test hook exposing the protected array-unwrapping {@code getValue}.
     */
    public long exposeGetValue(Object cell) {
        return getValue(cell);
    }

}
