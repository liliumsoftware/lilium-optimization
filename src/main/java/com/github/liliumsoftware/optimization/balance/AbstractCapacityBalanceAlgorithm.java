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

package com.github.liliumsoftware.optimization.balance;

import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.domain.Planable;
import com.github.liliumsoftware.optimization.exception.CancelException;
import com.github.liliumsoftware.optimization.insertion.AbstractInsertionAlgorithm;
import com.github.liliumsoftware.optimization.insertion.InsertionCandidate;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Balances plans by <em>capacity</em>, a domain measure supplied by {@link #getCapacity(Planable)} (for example the
 * load a plan carries): while a plan sits below {@code minCapacity}, one neighbouring customer is moved into it from
 * another plan.
 *
 * <p>Each step looks at the plans below the floor from the lowest capacity up and, for the first that can receive a
 * customer, tries every customer of every other plan with at least two customers whose zone neighbours the receiving
 * plan's: the customer is placed at the receiving plan's best position found by the insertion algorithm
 * ({@link AbstractInsertionAlgorithm#getBestInsertionCandidate}), removed from its donor, and the resources are
 * re-assigned with {@link TrackableAssignment#assignGreedy(List, List)}. A transfer is admissible only when that
 * assignment succeeds and the total shortfall below the floor, measured after the re-assignment, strictly shrinks; so
 * customers never bounce between plans below the floor, a donor may itself drop below the floor when that still
 * reduces the total shortfall, and the loop terminates even when {@link #getCapacity(Planable)} depends on the
 * assigned resource. Among the admissible transfers the step keeps the one whose plan set is <em>cheapest</em>
 * ({@code min} under {@link #plansComparator()}). A donor always keeps at least one customer, as in
 * {@link AbstractNodeBalanceAlgorithm}, so balancing never empties or removes a plan: dissolving a plan concentrates
 * load rather than evening it out, and is left to the merge algorithms. Steps repeat until every plan meets the floor
 * or no admissible transfer exists, and the best state seen is returned (smallest shortfall, then cheapest).
 * Balancing still trades cost for evenness; this choice only makes each step the least expensive way to do so.
 *
 * <p>Plans are located by position and customers by code, never by {@code equals}. Every candidate is built on
 * copies, so neither the list passed in nor its plans are modified. {@link #cancel()} aborts with a
 * {@link CancelException}; {@link #interrupt()} is polled before every step and returns the best state seen so far.
 * Both signals are forwarded to the insertion algorithm.
 *
 * @param <P> the plan type
 * @param <N> the node type
 * @param <C> the insertion candidate type of the insertion algorithm
 */
@Slf4j
public abstract class AbstractCapacityBalanceAlgorithm<P extends Planable<P, N, ?>, N extends Nodeable<N>,
        C extends InsertionCandidate<P, N>> extends AbstractBalanceAlgorithm<P, N> {

    /**
     * Finds the best position for a customer in the receiving plan.
     */
    protected final AbstractInsertionAlgorithm<P, N, C> insertionAlgorithm;
    /**
     * The floor every plan should reach, in {@link #getCapacity(Planable)} units.
     */
    protected final long minCapacity;

    /**
     * Creates a capacity balancer.
     *
     * @param costCalculation     supplies the zone neighbourhood and zone grouping
     * @param trackableAssignment assigns the resources of every candidate plan set
     * @param insertionAlgorithm  finds the best position for a customer in the receiving plan
     * @param minCapacity         the floor every plan should reach, in {@link #getCapacity(Planable)} units; a plan
     *                            whose capacity is below it receives customers, and its shortfall is
     *                            {@code minCapacity - getCapacity(plan)}
     */
    public AbstractCapacityBalanceAlgorithm(PathCostCalculation<P, N, ?> costCalculation,
            TrackableAssignment<P, ?> trackableAssignment, AbstractInsertionAlgorithm<P, N, C> insertionAlgorithm,
            long minCapacity) {
        super(costCalculation, trackableAssignment);
        this.insertionAlgorithm = insertionAlgorithm;
        this.minCapacity = minCapacity;
    }

    /**
     * Runs balancing steps on {@code plans} until every plan meets the floor, no admissible transfer is left or the
     * algorithm is interrupted (see the class description).
     *
     * @param otherPlans the plans of the other zone groups, whose resources the assignment must leave alone; not
     *                   modified
     * @param plans      the group to balance; neither the list nor its plans are modified
     * @return the best state seen (smallest shortfall, then cheapest), a new list; {@code plans} itself when no
     *         transfer was made
     * @throws CancelException     if the balancing was cancelled
     * @throws ArithmeticException if a capacity difference, the total shortfall or a summed cost does not fit a
     *                             {@code long}
     */
    @Override
    public List<P> doOperation(List<P> otherPlans, List<P> plans) {
        List<P> current = plans;
        List<P> best = plans;
        while (!isInterrupted()) {
            checkCancel();
            List<P> next = transfer(otherPlans, current);
            if (next == null) {
                break;
            }
            current = next;
            if (isBetter(current, best)) {
                best = current;
            }
        }
        return best;
    }

    /**
     * One balancing step: the cheapest admissible transfer into the lowest plan below the floor that can receive one,
     * or {@code null} when every plan meets the floor or no transfer is possible.
     */
    private List<P> transfer(List<P> otherPlans, List<P> plans) {
        long shortfall = shortfall(plans);
        for (int minIndex : ascendingPositions(plans, this::getCapacity)) {
            P minPlan = plans.get(minIndex);
            long minPlanCapacity = getCapacity(minPlan);
            if (minPlanCapacity >= minCapacity) {
                break;
            }
            List<P> donors = new ArrayList<>();
            List<Integer> donorIndexes = new ArrayList<>();
            for (int i = 0; i < plans.size(); i++) {
                // A donor keeps at least one customer, as in node balancing: balancing never dissolves a plan.
                if (i != minIndex && plans.get(i).getCustomerCount() >= 2) {
                    donors.add(plans.get(i).copy());
                    donorIndexes.add(i);
                }
            }
            List<P> receiver = List.of(minPlan.copy());
            List<List<P>> candidates = new ArrayList<>();
            for (Map.Entry<N, P> nodePlan : getOtherPlanNodes(donors, receiver.getFirst()).entrySet()) {
                checkCancel();
                C bestInsertionCandidate = insertionAlgorithm.getBestInsertionCandidate(receiver, nodePlan.getKey());
                int donor = indexOfInstance(donors, nodePlan.getValue());
                if (bestInsertionCandidate != null && donor >= 0) {
                    List<P> tempPlans = plans.stream().map(P::copy).collect(Collectors.toList());
                    tempPlans.set(minIndex, bestInsertionCandidate.getCandidatePlan());
                    P tempDonor = tempPlans.get(donorIndexes.get(donor));
                    tempDonor.getNodes().remove(indexOfCode(tempDonor.getNodes(), nodePlan.getKey().getCode()));
                    // Admissible only if the total shortfall strictly shrinks once the resources are re-assigned, which
                    // is what makes every step progress whatever the capacity depends on.
                    if (trackableAssignment.assignGreedy(otherPlans, tempPlans) && shortfall(tempPlans) < shortfall) {
                        candidates.add(tempPlans);
                    }
                }
            }
            if (!candidates.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug(getMessage(minPlan));
                }
                return candidates.stream().min(plansComparator()).orElseThrow(IllegalStateException::new);
            }
        }
        return null;
    }

    private boolean isBetter(List<P> candidate, List<P> best) {
        long candidateShortfall = shortfall(candidate);
        long bestShortfall = shortfall(best);
        return candidateShortfall < bestShortfall
                || (candidateShortfall == bestShortfall && plansComparator().compare(candidate, best) < 0);
    }

    /**
     * Total capacity missing below the floor, summed over the plans.
     *
     * @throws ArithmeticException if a difference or the sum does not fit a {@code long}, instead of wrapping to a
     *                             value that would make a worse candidate look better
     */
    private long shortfall(List<P> plans) {
        long total = 0;
        for (P plan : plans) {
            total = Math.addExact(total, Math.max(0, Math.subtractExact(minCapacity, getCapacity(plan))));
        }
        return total;
    }

    /**
     * Requests an abort of this algorithm and of its insertion algorithm; see
     * {@link com.github.liliumsoftware.optimization.BaseAlgorithm#cancel()}.
     */
    @Override
    public void cancel() {
        insertionAlgorithm.cancel();
        super.cancel();
    }

    /**
     * Requests a graceful stop of this algorithm and of its insertion algorithm; see
     * {@link com.github.liliumsoftware.optimization.BaseAlgorithm#interrupt()}.
     */
    @Override
    public void interrupt() {
        insertionAlgorithm.interrupt();
        super.interrupt();
    }

    @Override
    protected String getMessage(P plan) {
        return "Balance capacity";
    }

    /**
     * The capacity measure of {@code plan} that is compared with {@code minCapacity}, for example its summed demand.
     * It may depend on the plan's assigned resource: it is read after the resources of a candidate set have been
     * re-assigned. Called many times per step, so it should be cheap.
     *
     * @param plan a plan of the group being balanced, or a candidate copy of one
     * @return the plan's capacity, in the units of {@code minCapacity}
     */
    protected abstract long getCapacity(P plan);

}
