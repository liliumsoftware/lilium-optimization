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

package com.github.liliumsoftware.optimization.insertion;

import com.github.liliumsoftware.optimization.BaseAlgorithm;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.domain.Planable;
import com.github.liliumsoftware.optimization.feasibility.FeasibilityChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Constructive skeleton that places unrouted customers into existing plans by evaluating every legal
 * insertion position and keeping the cheapest one.
 *
 * <p>Each probe inserts a {@linkplain Nodeable#copy() copy} of the customer into a {@linkplain Planable#copy() copy}
 * of the plan, calls {@link PathCostCalculation#calculateCost(Planable)} on it (the result is discarded; the call only
 * lets an implementation refresh a cost cached on the plan) and wraps it with
 * {@link #createInsertionCandidate(Planable, Planable, Nodeable)}. With the base {@link InsertionCandidate}
 * constructor the delta is {@code candidatePlan.getCost() - originalPlan.getCost()}, so {@link Planable#getCost()}
 * must reflect the plan's current nodes. A plan is only probed when {@link #supports(Planable, Nodeable)} permits the
 * customer, and a slot is only accepted when the resulting plan passes the {@link FeasibilityChecker}; the checker is
 * consulted only for slots whose delta beats the best feasible slot of that plan so far, which gives the same result
 * as checking every slot at a fraction of the cost.
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
 * <p>Plans are probed on copies; a batch replaces elements of the caller's plan list with the enlarged copies and
 * never modifies a plan object. {@code cancel()} lands at the start of every plan probe ({@link #checkCancel()});
 * {@code interrupt()} only shortens regret batches (see {@link #insert(List, List, int...)}).
 *
 * <p><strong>Thread-safety.</strong> The instance holds only its collaborators, the {@code regret} flag and the two
 * {@link BaseAlgorithm} flags, so it may run concurrently on independent plan lists as far as the collaborators allow.
 *
 * @param <P> plan/route type
 * @param <N> node/stop type
 * @param <C> concrete insertion-candidate type produced by this algorithm
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractInsertionAlgorithm<P
        extends Planable<P, N, ?>, N extends Nodeable<N>, C extends InsertionCandidate<P, N>> extends BaseAlgorithm {

    /**
     * The cost model: recosts each probed copy and supplies the zone neighbourhood for {@link #supports}.
     */
    protected final PathCostCalculation<P, N, ?> costCalculation;
    /**
     * Decides whether a probed copy is an acceptable plan.
     */
    protected final FeasibilityChecker<P> feasibilityChecker;
    /**
     * {@code true} for regret insertion of batches, {@code false} for plain cheapest insertion.
     */
    protected final boolean regret;

    /**
     * The position of {@code plan} in {@code plans} by identity: the candidate's original plan is an element of the
     * list, and plans may compare equal by code or value, so {@code indexOf} could pick another element.
     */
    private static int indexOfInstance(List<?> plans, Object plan) {
        for (int i = 0; i < plans.size(); i++) {
            if (plans.get(i) == plan) {
                return i;
            }
        }
        throw new IllegalStateException("The chosen plan is not an element of the plan list");
    }

    /**
     * Inserts a single customer at its best feasible position across {@code plans}, chosen as by
     * {@link #getBestInsertionCandidate(List, Nodeable, int...)}: the element of {@code plans} that is the chosen plan
     * (located by identity, never by {@code equals}) is replaced by the candidate copy, which holds a copy of
     * {@code customer}; the original plan object is not modified. Always uses cheapest insertion, whatever the
     * {@code regret} flag.
     *
     * @param plans    the plans to choose from; one element is replaced on success
     * @param customer the customer to insert; not modified
     * @param index    optional restriction to specific plan indices; empty means "consider all plans", a duplicate
     *                 index probes that plan twice
     * @return {@code true} if the customer was inserted, {@code false} if no feasible position exists (or
     *     {@code plans} is empty)
     * @throws IndexOutOfBoundsException if an index is out of range of {@code plans}
     * @throws com.github.liliumsoftware.optimization.exception.CancelException if the algorithm is cancelled or the
     *     running thread is interrupted
     */
    public boolean insert(List<P> plans, N customer, int... index) {
        C insertionCandidate = getBestInsertionCandidate(plans, customer, index);
        if (insertionCandidate != null) {
            int i = indexOfInstance(plans, insertionCandidate.getOriginalPlan());
            plans.set(i, insertionCandidate.getCandidatePlan());
            return true;
        } else {
            logNotInsertable(customer);
            return false;
        }
    }

    /**
     * Inserts a batch of customers, driven by the {@code regret} policy.
     *
     * <p>Cheapest mode ({@code regret == false}): customers are inserted in {@link #nodeComparator()} order (input
     * order when it is {@code null}), each independently at the candidate
     * {@link #getBestInsertionCandidate(List, Nodeable, int...)} chooses.
     *
     * <p>Regret mode: the batch is consumed one customer per round, recomputing candidates as plans grow. The customer
     * with the largest regret is placed at its best candidate, or dropped from the batch when no plan can take it. The
     * regret of a customer is the sum, over the considered plans, of {@code delta − bestDelta}, where {@code delta} is
     * that plan's best feasible delta (a plan that cannot take the customer contributes its sentinel's
     * {@link InsertionCandidate#MONTH}) and {@code bestDelta} is the smallest of these values. Equal regrets go to the
     * customer whose best candidate has the smaller delta cost (a customer no plan can take, whose best candidate is a
     * sentinel, therefore comes last), then to the lexicographically smaller node code; a non-{@code null}
     * {@link #nodeComparator()} overrides the regret order, which then only breaks its ties. With a single plan every
     * regret is {@code 0}, so the cheapest insertion goes first (or the comparator decides). The enlarged copy replaces
     * the element of {@code plans} that is the chosen plan, located by identity.
     *
     * <p>Customers that cannot be placed anywhere are silently skipped. Insertion is construction, so an
     * {@link #interrupt() interrupt} never leaves an insertable customer out: once interrupted, a regret batch places
     * its remaining customers by cheapest insertion (much less work per customer) instead.
     *
     * @param plans     the plans to insert into; elements are replaced by enlarged copies, plan objects are not
     *                  modified
     * @param customers the batch; not modified
     * @param index     optional restriction to specific plan indices; empty means "consider all plans", a duplicate
     *                  index probes that plan twice (and counts twice in the regret)
     * @return a new list of the inserted customers, in insertion order; these are the copies placed in the plans
     *     ({@link Nodeable#copy()}), not the caller's objects (empty when {@code plans} is empty)
     * @throws IllegalArgumentException  if two customers share a code
     * @throws IndexOutOfBoundsException if an index is out of range of {@code plans}
     * @throws com.github.liliumsoftware.optimization.exception.CancelException if the algorithm is cancelled or the
     *     running thread is interrupted
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
        plans.set(indexOfInstance(plans, insertionCandidate.getOriginalPlan()), insertionCandidate.getCandidatePlan());
        insertedNodes.add(insertionCandidate.getNewNode());
    }

    /**
     * Regret selection: for every customer, the per-plan insertion costs are normalized by subtracting
     * that customer's cheapest cost, and the normalized costs are summed into a regret score. The
     * customer with the highest score (largest opportunity loss if deferred) wins, and its best
     * candidate is returned; ties break on the best candidate's delta cost (smallest first), then on node code. A
     * customer's best candidate is the first of
     * {@link #getInsertionCandidates} — its feasible candidates ranked by {@link #comparator()}, ahead of the "cannot
     * insert" sentinels — so a real insertion is never passed over for a sentinel whose {@code MONTH} delta happens
     * to be smaller.
     */
    private C getBestInsertionCandidate(List<P> plans, Collection<N> customers, int... index) {
        // One entry per customer, in batch order; plain lists rather than maps keyed by candidate, whose equals and
        // hashCode would compare and hash whole plans.
        List<Regret<C>> regrets = new ArrayList<>(customers.size());
        for (N customer : customers) {
            List<C> insertionCandidates = getInsertionCandidates(plans, customer, index);
            if (insertionCandidates.isEmpty()) {
                throw new IllegalStateException("No insertion candidate for node " + customer.getCode());
            }
            long minValue = Long.MAX_VALUE;
            for (C candidate : insertionCandidates) {
                minValue = Math.min(minValue, candidate.getDeltaCost());
            }
            long regret = 0;
            for (C candidate : insertionCandidates) {
                regret += candidate.getDeltaCost() - minValue;
            }
            regrets.add(new Regret<>(insertionCandidates.getFirst(), regret));
        }

        // Equal regrets (always the case with a single plan) go to the cheapest best candidate, then to the code.
        List<C> bestInsertionCandidates = regrets.stream()
                .sorted(Comparator.comparingLong((Regret<C> r) -> r.regret()).reversed()
                        .thenComparingLong(r -> r.candidate().getDeltaCost())
                        .thenComparing(r -> r.candidate().getNewNode().getCode()))
                .map(Regret::candidate).toList();

        Comparator<N> nodeComparator = nodeComparator();
        if (nodeComparator != null) {
            return bestInsertionCandidates.stream().sorted((o1, o2) -> nodeComparator.compare(o1.getNewNode(),
                            o2.getNewNode()))
                    .limit(1).findFirst().orElseThrow(IllegalStateException::new);
        }
        return bestInsertionCandidates.getFirst();
    }

    /**
     * The best feasible insertion candidate for {@code customer}: each considered plan contributes its feasible slot
     * with the smallest delta (the earliest on ties), and the first of these under {@link #comparator()} (plan order
     * on ties) is returned — the cheapest with the default comparator, not necessarily with an overridden one. Nothing
     * is modified; the candidate holds copies of the plan and the customer.
     *
     * @param plans    the plans to probe; not modified
     * @param customer the customer to place; not modified
     * @param index    optional restriction to specific plan indices; empty means "consider all plans"
     * @return the chosen candidate, or {@code null} if the customer cannot be inserted into any of the considered
     *     plans (or {@code plans} is empty)
     * @throws IndexOutOfBoundsException if an index is out of range of {@code plans}
     * @throws com.github.liliumsoftware.optimization.exception.CancelException if the algorithm is cancelled or the
     *     running thread is interrupted
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

        // The real candidates ranked by comparator(), then the "cannot insert" sentinels in plan order.
        List<C> ranked = new ArrayList<>(planCandidates.stream().filter(c -> c.getCandidatePlan() != null)
                .sorted(comparator().thenComparing(o -> o.getNewNode().getCode())).toList());
        planCandidates.stream().filter(c -> c.getCandidatePlan() == null).forEach(ranked::add);
        return ranked;
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
            // The cost test comes first: the feasibility check is the expensive part of a probe, and a slot that
            // cannot beat the best one so far would be discarded whatever the check says.
            if ((bestCandidate == null || insertionCandidate.getDeltaCost() < bestCandidate.getDeltaCost())
                    && feasibilityChecker.getResult(insertionCandidate.getCandidatePlan()).isFeasible()) {
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
     *
     * @return the customer order, or {@code null} for input order (cheapest mode) / pure regret order (regret mode)
     */
    protected Comparator<N> nodeComparator() {
        return null;
    }

    /**
     * Ranking of a customer's per-plan candidates (one per plan, that plan's feasible slot with the smallest delta
     * cost) to choose the plan it goes into; the default prefers the smallest delta cost. It does not choose the slot
     * within a plan. Override to bias the choice of plan toward criteria other than pure cost. Only real candidates are
     * ranked (the "cannot insert" sentinels always come last), and ties keep plan order.
     *
     * @return the candidate order, best first; must not be {@code null}
     */
    protected Comparator<C> comparator() {
        return Comparator.comparingLong(InsertionCandidate::getDeltaCost);
    }

    /**
     * Builds the sentinel candidate representing "this node cannot be inserted into {@code originalPlan}". It must have
     * a {@code null} {@link InsertionCandidate#getCandidatePlan() candidate plan}, which is how the algorithm
     * recognises it; its delta should be {@link InsertionCandidate#MONTH}, as with the
     * {@link InsertionCandidate#InsertionCandidate(Planable, Nodeable) sentinel constructor}, because the regret sums
     * it.
     *
     * @param originalPlan the plan that cannot take the node
     * @param node         the caller's customer (not a copy)
     * @return the sentinel
     */
    protected abstract C createEmptyInsertionCandidate(P originalPlan, N node);

    /**
     * Wraps a concrete insertion into the subclass candidate type. {@code candidatePlan} is a copy of
     * {@code originalPlan} with {@code node} inserted, on which {@link PathCostCalculation#calculateCost(Planable)} has
     * been called with its result discarded: the delta the algorithm ranks by is the candidate's
     * {@link InsertionCandidate#getDeltaCost()}, which the base constructor reads from {@link Planable#getCost()}.
     *
     * @param originalPlan  the untouched plan from the caller's list
     * @param candidatePlan the probed copy with {@code node} inserted
     * @param node          the inserted copy of the customer
     * @return the candidate; its candidate plan must not be {@code null}
     */
    protected abstract C createInsertionCandidate(P originalPlan, P candidatePlan, N node);

    /**
     * Zone gate deciding whether {@code customer} may be inserted into {@code plan} at all; asked once per probed plan,
     * before any slot is tried. Override to widen or tighten the search space.
     *
     * <p>The default returns {@link PathCostCalculation#isNeighbor
     * costCalculation.isNeighbor(plan.getZone(), customer.getZone())}: always {@code true} under
     * {@code NO_RESTRICTION} or when either zone is {@code null}, otherwise only when the customer's zone is listed as
     * a neighbour of the plan's zone (a zone is its own neighbour only if listed).
     *
     * @param plan     the plan to probe
     * @param customer the customer to insert
     * @return {@code true} if the plan's slots may be probed for {@code customer}
     */
    protected boolean supports(P plan, N customer) {
        return costCalculation.isNeighbor(plan.getZone(), customer.getZone());
    }

    /**
     * A customer's best candidate and the regret of deferring it.
     */
    private record Regret<C>(C candidate, long regret) {
    }

}
