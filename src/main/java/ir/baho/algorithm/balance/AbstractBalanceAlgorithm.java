package ir.baho.algorithm.balance;

import ir.baho.algorithm.BaseAlgorithm;
import ir.baho.algorithm.assignment.TrackableAssignment;
import ir.baho.algorithm.calculation.CostCalculation;
import ir.baho.algorithm.calculation.PathCostCalculation;
import ir.baho.algorithm.domain.Nodeable;
import ir.baho.algorithm.domain.Planable;
import ir.baho.algorithm.split.ZoneGroups;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * Base for load-balancing plans against each other. {@link #apply} first partitions plans into independent
 * zone groups (so balancing never moves work across non-neighbouring zones) and balances the groups through
 * {@link ZoneGroups} (in parallel on the units each group holds, then in input order with the idle units) via the
 * subclass's {@link #doOperation}, so no resource is booked twice and the result is deterministic. Subclasses decide
 * what "balance" means (customer count, capacity, …).
 */
@RequiredArgsConstructor
public abstract class AbstractBalanceAlgorithm<P
        extends Planable<P, N, ?>, N extends Nodeable<N>> extends BaseAlgorithm {

    protected final PathCostCalculation<P, N, ?> costCalculation;
    protected final TrackableAssignment<P, ?> trackableAssignment;

    /**
     * Position of {@code element} in {@code list} by identity, or {@code -1}; balancing locates plans by position so
     * that it does not depend on the consumer's {@code equals}.
     */
    static int indexOfInstance(List<?> list, Object element) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == element) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Position of the node with {@code code} in {@code nodes}, or {@code -1}; customers are located by their unique
     * code, so a {@link Planable#getCustomers()} that returns copies rather than the route's own instances still works.
     */
    static int indexOfCode(List<? extends Nodeable<?>> nodes, String code) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).getCode().equals(code)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The plan positions ordered by ascending {@code measure} (stable, so ties keep list order).
     */
    static <P> List<Integer> ascendingPositions(List<P> plans, ToLongFunction<P> measure) {
        List<Integer> positions = new ArrayList<>(plans.size());
        for (int i = 0; i < plans.size(); i++) {
            positions.add(i);
        }
        positions.sort(Comparator.comparingLong(i -> measure.applyAsLong(plans.get(i))));
        return positions;
    }

    /**
     * Balances {@code plans} zone group by zone group through {@link ZoneGroups} and returns the concatenated result (a
     * new list) in the order of each group's first input plan.
     */
    public List<P> apply(List<P> plans) {
        return ZoneGroups.apply(costCalculation, trackableAssignment, Math.max(1, plans.size()), plans,
                this::doOperation);
    }

    /**
     * Balances one zone group against the plans of the other groups ({@code otherPlans}, whose resources it may not
     * take) and returns the resulting plans; the input list is not mutated. Implementations assign candidate plan sets
     * with {@link TrackableAssignment#assignGreedy(List, List)
     * trackableAssignment.assignGreedy(otherPlans, candidate)}, which keeps those resources out of reach.
     */
    public abstract List<P> doOperation(List<P> otherPlans, List<P> plans);

    /**
     * The customers of {@code donors} that may move into {@code minPlan} (zone-neighbouring ones), each mapped to
     * the donor it comes from, in plan and route order. The balancing subclasses pass only the admissible donors;
     * an override must map every customer to one of the given donor instances.
     */
    protected Map<N, P> getOtherPlanNodes(List<P> donors, P minPlan) {
        Map<N, P> planNodes = new LinkedHashMap<>();   // insertion order keeps candidate evaluation deterministic
        donors.forEach(plan -> plan.getCustomers().stream()
                .filter(node -> costCalculation.isNeighbor(minPlan.getZone(), node.getZone()))
                .forEach(node -> planNodes.put(node, plan)));
        return planNodes;
    }

    /**
     * Orders candidate plan sets by ascending total cost; {@link #doOperation} keeps the minimum.
     */
    protected Comparator<List<P>> plansComparator() {
        return Comparator.comparingLong(CostCalculation::totalCost);
    }

    /**
     * The debug message logged for an accepted transfer into {@code plan}.
     */
    protected abstract String getMessage(P plan);
}
