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
 * Balances plans by <em>customer count</em>: while the largest and the smallest plan differ by at least
 * {@code threshold} customers, one neighbouring customer is moved into a small plan from a larger one.
 *
 * <p>Each step looks at the plans from the fewest customers up, as long as the largest plan exceeds them by at least
 * {@code threshold}, and for the first that can receive a customer tries every customer whose zone neighbours the
 * receiving plan's, taken from the plans with at least two customers more than the receiving plan (so every transfer
 * strictly evens the load out and customers never bounce between small plans). The customer is placed at the
 * receiving plan's best position found by the insertion algorithm
 * ({@link AbstractInsertionAlgorithm#getBestInsertionCandidate}), removed from its donor, and the transfer is
 * admissible when the resources can be re-assigned with {@link TrackableAssignment#assignGreedy(List, List)}. Among
 * the admissible transfers the step keeps the one whose plan set is <em>cheapest</em> ({@code min} under
 * {@link #plansComparator()}). Steps repeat until the spread is below the threshold or no admissible transfer is left,
 * which always happens because every transfer strictly evens the counts out, and the best state seen is returned
 * (smallest spread, then cheapest). Balancing still trades cost for evenness; this choice only makes each step the
 * least expensive way to do so.
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
public abstract class AbstractNodeBalanceAlgorithm<P extends Planable<P, N, ?>, N extends Nodeable<N>,
        C extends InsertionCandidate<P, N>> extends AbstractBalanceAlgorithm<P, N> {

    /**
     * Finds the best position for a customer in the receiving plan.
     */
    protected final AbstractInsertionAlgorithm<P, N, C> insertionAlgorithm;
    /**
     * The customer-count spread at or above which balancing runs.
     */
    protected final int threshold;

    /**
     * Creates a customer-count balancer.
     *
     * @param costCalculation     supplies the zone neighbourhood and zone grouping
     * @param trackableAssignment assigns the resources of every candidate plan set
     * @param insertionAlgorithm  finds the best position for a customer in the receiving plan
     * @param threshold           balancing runs while the largest and the smallest plan differ by at least this many
     *                            customers; a transfer always needs a donor with at least two customers more than the
     *                            receiver, whatever the threshold
     */
    public AbstractNodeBalanceAlgorithm(PathCostCalculation<P, N, ?> costCalculation,
            TrackableAssignment<P, ?> trackableAssignment, AbstractInsertionAlgorithm<P, N, C> insertionAlgorithm,
            int threshold) {
        super(costCalculation, trackableAssignment);
        this.insertionAlgorithm = insertionAlgorithm;
        this.threshold = threshold;
    }

    private static int spread(List<? extends Planable<?, ?, ?>> plans) {
        return plans.stream().mapToInt(Planable::getCustomerCount).max().orElse(0)
                - plans.stream().mapToInt(Planable::getCustomerCount).min().orElse(0);
    }

    /**
     * Runs balancing steps on {@code plans} until the spread is below the threshold, no admissible transfer is left or
     * the algorithm is interrupted (see the class description).
     *
     * @param otherPlans the plans of the other zone groups, whose resources the assignment must leave alone; not
     *                   modified
     * @param plans      the group to balance; neither the list nor its plans are modified
     * @return the best state seen (smallest spread, then cheapest), a new list; {@code plans} itself when no transfer
     *         was made or none led to a better state
     * @throws CancelException     if the balancing was cancelled
     * @throws ArithmeticException if a summed cost does not fit a {@code long}
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
     * One balancing step: the cheapest admissible transfer into the smallest plan that can receive one, or
     * {@code null} when the spread is already below the threshold or no transfer is possible.
     */
    private List<P> transfer(List<P> otherPlans, List<P> plans) {
        int maxCount = plans.stream().mapToInt(Planable::getCustomerCount).max().orElse(0);
        for (int minIndex : ascendingPositions(plans, Planable::getCustomerCount)) {
            P minPlan = plans.get(minIndex);
            if (maxCount - minPlan.getCustomerCount() < threshold) {
                break;
            }
            // Only a donor with at least two customers more than the receiver strictly reduces the imbalance.
            List<P> donors = new ArrayList<>();
            List<Integer> donorIndexes = new ArrayList<>();
            for (int i = 0; i < plans.size(); i++) {
                if (i != minIndex && plans.get(i).getCustomerCount() >= minPlan.getCustomerCount() + 2) {
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
                    if (trackableAssignment.assignGreedy(otherPlans, tempPlans)) {
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
        int candidateSpread = spread(candidate);
        int bestSpread = spread(best);
        return candidateSpread < bestSpread || (candidateSpread == bestSpread && plansComparator().compare(candidate,
                best) < 0);
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
        return "Balance node";
    }

}
