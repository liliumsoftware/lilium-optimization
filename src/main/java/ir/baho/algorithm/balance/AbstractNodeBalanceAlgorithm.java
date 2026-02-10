package ir.baho.algorithm.balance;

import ir.baho.algorithm.assignment.TrackableAssignment;
import ir.baho.algorithm.calculation.PathCostCalculation;
import ir.baho.algorithm.domain.Nodeable;
import ir.baho.algorithm.domain.Planable;
import ir.baho.algorithm.insertion.AbstractInsertionAlgorithm;
import ir.baho.algorithm.insertion.InsertionCandidate;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Balances plans by <em>customer count</em>: while the gap between the largest and smallest plan is at least
 * {@code threshold}, it moves a neighbouring customer into the smallest plan (via cheapest insertion) and out
 * of its current one. Only a plan with at least two customers more than the receiving plan may donate, so every
 * transfer strictly evens the load out (customers never bounce between small plans). Among the feasible transfers it
 * keeps the one whose resulting plan set has the <em>highest</em> total cost ({@code max} under
 * {@link #plansComparator()}, as historically implemented). Iterates until the spread is below the threshold or no
 * admissible transfer is left, and returns the best state seen (smallest spread, then cheapest). Plans are located by
 * position and customers by code, never by {@code equals};
 * {@code cancel()} and {@code interrupt()} are honoured between transfers and forwarded to the insertion algorithm.
 */
@Slf4j
public abstract class AbstractNodeBalanceAlgorithm<P extends Planable<P, N, ?>, N extends Nodeable<N>,
        C extends InsertionCandidate<P, N>> extends AbstractBalanceAlgorithm<P, N> {

    protected final AbstractInsertionAlgorithm<P, N, C> insertionAlgorithm;
    protected final int threshold;

    /**
     * Creates a customer-count balancer.
     *
     * @param threshold balancing runs while the largest and smallest plan differ by at least this many customers
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
     * See the class description; the input list and its plans are left untouched (the input list itself is returned
     * when no transfer is made).
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
                log.debug(getMessage(minPlan));
                return candidates.stream().max(plansComparator()).orElseThrow(IllegalStateException::new);
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
     * Propagates cancellation to the nested insertion algorithm before cancelling itself.
     */
    @Override
    public void cancel() {
        insertionAlgorithm.cancel();
        super.cancel();
    }

    /**
     * Propagates the graceful stop to the nested insertion algorithm before interrupting itself.
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
