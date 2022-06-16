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
 * Balances plans by <em>capacity</em> (a domain-defined {@link #getCapacity(Planable)} measure): while any
 * plan sits below {@code minCapacity}, it pulls one neighbouring customer into that plan via cheapest insertion.
 * A transfer is admissible only when the total shortfall below the floor, measured after the resources have been
 * re-assigned, strictly shrinks; so customers never bounce between plans below the floor, a donor may itself drop
 * below the floor when that still reduces the total shortfall, and the loop terminates even when
 * {@link #getCapacity(Planable)} depends on the assigned resource. Among the admissible single-customer transfers it
 * keeps the one whose resulting plan set has the <em>highest</em> total cost ({@code max} under
 * {@link #plansComparator()}, as historically implemented), then repeats until every plan meets the floor or no
 * admissible transfer is assignable, and returns the best state seen (smallest shortfall, then cheapest). Plans are
 * located by position and customers by code, never by {@code equals};
 * {@code cancel()} and {@code interrupt()} are honoured between transfers and forwarded to the insertion algorithm.
 */
@Slf4j
public abstract class AbstractCapacityBalanceAlgorithm<P extends Planable<P, N, ?>, N extends Nodeable<N>,
        C extends InsertionCandidate<P, N>> extends AbstractBalanceAlgorithm<P, N> {

    protected final AbstractInsertionAlgorithm<P, N, C> insertionAlgorithm;
    protected final long minCapacity;

    /**
     * Creates a capacity balancer.
     *
     * @param minCapacity the floor every plan should reach, in {@link #getCapacity(Planable)} units
     */
    public AbstractCapacityBalanceAlgorithm(PathCostCalculation<P, N, ?> costCalculation,
            TrackableAssignment<P, ?> trackableAssignment, AbstractInsertionAlgorithm<P, N, C> insertionAlgorithm,
            long minCapacity) {
        super(costCalculation, trackableAssignment);
        this.insertionAlgorithm = insertionAlgorithm;
        this.minCapacity = minCapacity;
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
                if (i != minIndex) {
                    donors.add(plans.get(i).copy());
                    donorIndexes.add(i);
                }
            }
            List<P> receiver = List.of(minPlan.copy());
            List<List<P>> candidates = new ArrayList<>();
            for (Map.Entry<N, P> nodePlan : getOtherPlanNodes(donors, receiver.get(0)).entrySet()) {
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
                log.debug(getMessage(minPlan));
                return candidates.stream().max(plansComparator()).orElseThrow(IllegalStateException::new);
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
     */
    private long shortfall(List<P> plans) {
        return plans.stream().mapToLong(p -> Math.max(0, minCapacity - getCapacity(p))).sum();
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
        return "Balance capacity";
    }

    /**
     * The capacity measure of {@code plan} that is compared with the floor.
     */
    protected abstract long getCapacity(P plan);

}
