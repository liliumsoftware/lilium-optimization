package ir.baho.algorithm.insertion;

import ir.baho.algorithm.BaseAlgorithm;
import ir.baho.algorithm.calculation.PathCostCalculation;
import ir.baho.algorithm.domain.Nodeable;
import ir.baho.algorithm.domain.Planable;
import ir.baho.algorithm.feasibility.FeasibilityChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Constructive skeleton that places unrouted customers into existing plans by evaluating every legal
 * insertion position and keeping the cheapest one.
 *
 * <p>For each candidate the cost delta is {@code candidatePlan.getCost() - originalPlan.getCost()} (see
 * {@link InsertionCandidate}); a position is only considered when {@link #supports(Planable, Nodeable)}
 * permits it and the resulting plan passes the {@link FeasibilityChecker} — the latter runs once per
 * probed position, so it sits on the hot path.
 *
 * <p>The {@code regret} flag selects the insertion policy:
 * <ul>
 *   <li>{@code false} — plain cheapest insertion: each customer is inserted independently at its own
 *       best feasible position, optionally pre-ordered by {@link #nodeComparator()}.</li>
 *   <li>{@code true} — regret insertion: on every round the customer with the largest "regret" is inserted first —
 *       the gaps between its best candidate and its candidates in <em>every</em> considered plan, summed (there is no
 *       {@code k}: all plans contribute), i.e. how much it would suffer if deferred — which tends to avoid painting
 *       hard-to-place nodes into a corner. When {@link #nodeComparator()} returns a comparator it takes precedence
 *       here too: the customer it orders first is inserted, and regret only decides between customers the
 *       comparator ranks equal.</li>
 * </ul>
 * In both modes a customer's position is chosen in two steps: each plan contributes one candidate, its feasible slot
 * with the smallest delta cost (the earliest such slot on ties), and {@link #comparator()} then ranks these per-plan
 * winners (smallest delta by default), so the comparator chooses the plan, never a slot within a plan. A real
 * insertion always beats the "cannot insert" sentinel, however expensive it is. Every batch entry point rejects two
 * customers sharing a code, and an {@link #interrupt()} never makes a batch skip an insertable customer (a regret
 * batch finishes by cheapest insertion).
 * Subclasses supply the concrete {@link InsertionCandidate} type through
 * {@link #createInsertionCandidate(Planable, Planable, Nodeable)} /
 * {@link #createEmptyInsertionCandidate(Planable, Nodeable)}.
 *
 * @param <P> plan/route type
 * @param <N> node/stop type
 * @param <C> concrete insertion-candidate type produced by this algorithm
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractInsertionAlgorithm<P
        extends Planable<P, N, ?>, N extends Nodeable<N>, C extends InsertionCandidate<P, N>> extends BaseAlgorithm {

    protected final PathCostCalculation<P, N, ?> costCalculation;
    protected final FeasibilityChecker<P> feasibilityChecker;
    protected final boolean regret;

    /**
     * Inserts a single customer at its best feasible position across {@code plans}: the element of {@code plans}
     * holding the chosen plan is replaced by the candidate copy with the customer inserted (the original plan object
     * is not modified).
     *
     * @param index optional restriction to specific plan indices; empty means "consider all plans"
     * @return {@code true} if the customer was inserted, {@code false} if no feasible position exists
     */
    public boolean insert(List<P> plans, N customer, int... index) {
        C insertionCandidate = getBestInsertionCandidate(plans, customer, index);
        if (insertionCandidate != null) {
            int i = plans.indexOf(insertionCandidate.getOriginalPlan());
            plans.set(i, insertionCandidate.getCandidatePlan());
            return true;
        } else {
            logNotInsertable(customer);
            return false;
        }
    }

    /**
     * Inserts a batch of customers, driven by the {@code regret} policy. In regret mode the batch is
     * consumed one node per round (recomputing candidates as plans grow); otherwise customers are
     * inserted in {@link #nodeComparator()} order, each independently. Customers that cannot be placed
     * anywhere are silently skipped. Insertion is construction, so an {@link #interrupt() interrupt} never leaves an
     * insertable customer out: once interrupted, a regret batch places its remaining customers by cheapest insertion
     * (much less work per customer) instead.
     *
     * @param index optional restriction to specific plan indices; empty means "consider all plans"
     * @return the customers that were actually inserted (empty when {@code plans} is empty)
     * @throws IllegalArgumentException if two customers share a code
     */
    public List<N> insert(List<P> plans, List<N> customers, int... index) {
        requireDistinctCodes(customers);
        if (plans.isEmpty()) {
            return new ArrayList<>();
        }
        customers = new ArrayList<>(customers);
        List<N> insertedNodes = new ArrayList<>();
        if (regret) {
            while (!customers.isEmpty() && !isInterrupted()) {   // interrupted: finish by cheapest insertion below
                C insertionCandidate = getBestInsertionCandidate(plans, customers, index);
                if (insertionCandidate.getCandidatePlan() != null) {
                    insert(plans, insertedNodes, insertionCandidate);
                } else {
                    logNotInsertable(insertionCandidate.getNewNode());
                }
                // The candidate carries a copy of the customer; remove the batch entry by code so a consumer whose
                // copy() is not equal to its source cannot keep the same customer in the batch forever.
                removeByCode(customers, insertionCandidate.getNewNode());
            }
        }
        if (!customers.isEmpty()) {
            Comparator<N> nodeComparator = nodeComparator();
            if (nodeComparator != null) {
                customers = customers.stream().sorted(nodeComparator).toList();
            }
            for (N customer : customers) {
                C insertionCandidate = getBestInsertionCandidate(plans, customer, index);
                if (insertionCandidate != null) {
                    insert(plans, insertedNodes, insertionCandidate);
                } else {
                    logNotInsertable(customer);
                }
            }
        }
        return insertedNodes;
    }

    private void insert(List<P> plans, List<N> insertedNodes, C insertionCandidate) {
        int i = plans.indexOf(insertionCandidate.getOriginalPlan());
        plans.remove(i);
        plans.add(i, insertionCandidate.getCandidatePlan());
        insertedNodes.add(insertionCandidate.getNewNode());
    }

    /**
     * Regret selection: for every customer, the per-plan insertion costs are normalized by subtracting
     * that customer's cheapest cost, and the normalized costs are summed into a regret score. The
     * customer with the highest score (largest opportunity loss if deferred) wins, and its best
     * candidate is returned; ties break on node code for determinism. A customer's best candidate is the first of
     * {@link #getInsertionCandidates} — its feasible candidates ranked by {@link #comparator()}, ahead of the "cannot
     * insert" sentinels — so a real insertion is never passed over for a sentinel whose {@code MONTH} delta happens
     * to be smaller.
     */
    private C getBestInsertionCandidate(List<P> plans, Collection<N> customers, int... index) {
        Map<C, Long> regretSums = new LinkedHashMap<>();

        for (N customer : customers) {
            List<C> insertionCandidates = getInsertionCandidates(plans, customer, index);
            Map<C, Long> insertionCosts = insertionCandidates.stream()
                    .collect(LinkedHashMap::new, (map, item) -> map.put(item, item.getDeltaCost()), Map::putAll);
            long minValue = insertionCosts.values().stream().mapToLong(Long::longValue).min().orElse(0);
            insertionCosts.replaceAll((k, v) -> v - minValue);
            if (insertionCandidates.isEmpty()) {
                throw new IllegalStateException("No insertion candidate for node " + customer.getCode());
            }
            regretSums.put(insertionCandidates.getFirst(), insertionCosts.values().stream().mapToLong(Long::longValue)
                    .sum());
        }

        List<C> bestInsertionCandidates = regretSums.entrySet().stream()
                .sorted(Comparator.comparing((Function<Map.Entry<C, Long>, Long>) Map.Entry::getValue).reversed()
                        .thenComparing(o -> o.getKey().getNewNode().getCode()))
                .map(Map.Entry::getKey).toList();

        Comparator<N> nodeComparator = nodeComparator();
        if (nodeComparator != null) {
            return bestInsertionCandidates.stream().sorted((o1, o2) -> nodeComparator.compare(o1.getNewNode(),
                            o2.getNewNode()))
                    .limit(1).findFirst().orElseThrow(IllegalStateException::new);
        }
        return bestInsertionCandidates.getFirst();
    }

    /**
     * The single cheapest feasible insertion candidate for {@code customer}, or {@code null} if the
     * customer cannot be inserted into any of the considered plans.
     */
    public C getBestInsertionCandidate(List<P> plans, N customer, int... index) {
        List<C> planCandidates = getInsertionCandidates(plans, customer, index)
                .stream().filter(c -> c.getCandidatePlan() != null).toList();
        return planCandidates.isEmpty() ? null : planCandidates.getFirst();
    }

    private List<C> getInsertionCandidates(List<P> plans, N customer, int... index) {
        List<C> planCandidates = new ArrayList<>();
        if (index.length == 0) {
            plans.forEach(p -> addInsertionCandidates(p, customer, planCandidates));
        } else {
            Arrays.stream(index).forEach(i -> addInsertionCandidates(plans.get(i), customer, planCandidates));
        }

        List<C> sortedPlanCandidates = planCandidates.stream().filter(c -> c.getCandidatePlan() != null)
                .sorted(comparator().thenComparing(o -> o.getNewNode().getCode())).toList();
        planCandidates.removeAll(sortedPlanCandidates);
        planCandidates.addAll(0, sortedPlanCandidates);
        return planCandidates;
    }

    /**
     * Probes every legal slot of {@code plan} — from just after its origin (or in front of its first customer when it
     * has no origin) up to just before its destination (or up to and including the end when it has none), never before
     * an origin or after a destination — copying the plan and node per slot,
     * recosting the copy through {@code calculateCost(plan)}, and retaining the cheapest feasible one.
     * Always appends exactly one candidate for the customer — a real one when a feasible slot exists,
     * otherwise an empty (non-insertable) sentinel — so the regret matrix stays aligned.
     */
    private void addInsertionCandidates(P plan, N customer, List<C> planCandidates) {
        checkCancel();
        C bestCandidate = null;
        // Every slot between movable stops: after a leading origin (or in front of the first customer when the plan
        // has none) up to before a destination (or behind the last customer when it has none).
        int firstSlot = plan.getCount() > 0 && plan.getNodes().getFirst().getType() == Nodeable.Type.ORIGIN ? 1 : 0;
        int lastSlot = plan.getDestination() == null ? plan.getCount() : plan.getCount() - 1;
        // The zone gate does not depend on the slot, so it is asked once per plan rather than once per position.
        boolean supported = supports(plan, customer);
        for (int i = firstSlot; supported && i <= lastSlot; i++) {
            P candidatePlan = plan.copy();
            N node = customer.copy();
            candidatePlan.getNodes().add(i, node);
            // The result is not used here: the call lets an implementation that caches the cost on the plan refresh
            // it, because the candidate's delta is read from getCost().
            costCalculation.calculateCost(candidatePlan);

            C insertionCandidate = createInsertionCandidate(plan, candidatePlan, node);
            if (feasibilityChecker.getResult(insertionCandidate.getCandidatePlan()).isFeasible()
                    && (bestCandidate == null || insertionCandidate.getDeltaCost() < bestCandidate.getDeltaCost())) {
                bestCandidate = insertionCandidate;
            } else {
                log.trace("Can not create candidate for node {}", customer.getCode());
            }
        }
        planCandidates.add(Objects.requireNonNullElseGet(bestCandidate, () -> createEmptyInsertionCandidate(plan,
                customer)));
    }

    private void logNotInsertable(N customer) {
        log.trace("Can not insert node {}", customer.getCode());
    }

    private void removeByCode(List<N> customers, N node) {
        for (int i = 0; i < customers.size(); i++) {
            if (customers.get(i).getCode().equals(node.getCode())) {
                customers.remove(i);
                return;
            }
        }
        throw new IllegalStateException("Node " + node.getCode() + " is not part of the batch");
    }

    /**
     * Optional ordering imposed on customers before insertion (e.g. hardest-first). {@code null}
     * (the default) leaves the natural processing order untouched. In regret mode a non-null comparator overrides
     * the regret ranking, which then only breaks ties.
     */
    protected Comparator<N> nodeComparator() {
        return null;
    }

    /**
     * Ranking of a customer's per-plan candidates (one per plan, that plan's feasible slot with the smallest delta
     * cost) to choose the plan it goes into; the default prefers the smallest delta cost. It does not choose the slot
     * within a plan. Override to bias the choice of plan toward criteria other than pure cost.
     */
    protected Comparator<C> comparator() {
        return Comparator.comparingLong(InsertionCandidate::getDeltaCost);
    }

    /**
     * Builds the sentinel candidate representing "this node cannot be inserted into {@code originalPlan}"
     * (delta cost {@link InsertionCandidate#MONTH}).
     */
    protected abstract C createEmptyInsertionCandidate(P originalPlan, N node);

    /**
     * Wraps a concrete insertion into the subclass candidate type, given the untouched original plan,
     * the copy with {@code node} already inserted and recosted, and the inserted node.
     */
    protected abstract C createInsertionCandidate(P originalPlan, P candidatePlan, N node);

    /**
     * Zone gate deciding whether {@code customer} may be inserted into {@code plan}; the default admits
     * only customers whose zone neighbours the plan's zone. Override to widen or tighten the search space.
     */
    protected boolean supports(P plan, N customer) {
        return costCalculation.isNeighbor(plan.getZone(), customer.getZone());
    }

}
