package ir.baho.algorithm.merge;

import ir.baho.algorithm.BaseAlgorithm;
import ir.baho.algorithm.OptimizationType;
import ir.baho.algorithm.assignment.TrackableAssignment;
import ir.baho.algorithm.domain.Nodeable;
import ir.baho.algorithm.domain.Planable;
import ir.baho.algorithm.localsearch.LocalSearchAlgorithm;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Redistributes customers between two plans that both span the <em>same two</em> zones: each plan keeps one
 * zone's customers and trades away the other's, so the two plans separate cleanly along the zone boundary.
 * Applies only when both plans cover exactly the same pair of real zones (a customer without a zone or in the default
 * {@code "NoZone"} disqualifies the pair) and never under {@link OptimizationType#NO_RESTRICTION}; accepted only if the
 * resulting assignment succeeds (there is no cost check).
 */
@Slf4j
public abstract class AbstractDoubleUnMergeAlgorithm<P
        extends Planable<P, N, ?>, N extends Nodeable<N>> extends BaseAlgorithm {

    /**
     * The zone {@link Nodeable#getZone()} reports by default, i.e. "no zone".
     */
    private static final String DEFAULT_ZONE = "NoZone";
    protected final TrackableAssignment<P, ?> trackableAssignment;
    protected final LocalSearchAlgorithm<P> localSearchAlgorithm;
    protected final OptimizationType optimizationType;

    /**
     * Zone-restricted un-merging ({@link OptimizationType#LOCATION_ZONE}).
     */
    public AbstractDoubleUnMergeAlgorithm(TrackableAssignment<P, ?> trackableAssignment,
            LocalSearchAlgorithm<P> localSearchAlgorithm) {
        this(trackableAssignment, localSearchAlgorithm, OptimizationType.LOCATION_ZONE);
    }

    /**
     * Creates an un-merge for the given zone policy.
     *
     * @param optimizationType the zone policy of the run; under {@link OptimizationType#NO_RESTRICTION} zones do not
     *                         restrict anything, so no plan is ever un-merged
     */
    public AbstractDoubleUnMergeAlgorithm(TrackableAssignment<P, ?> trackableAssignment,
            LocalSearchAlgorithm<P> localSearchAlgorithm,
            OptimizationType optimizationType) {
        this.trackableAssignment = trackableAssignment;
        this.localSearchAlgorithm = localSearchAlgorithm;
        this.optimizationType = optimizationType;
    }

    /**
     * Scans every plan pair, applying the first accepted un-merge and restarting the scan, until a full pass changes
     * nothing or {@link #isInterrupted()}. Mutates and returns {@code plans}.
     */
    public List<P> apply(List<P> plans) {
        for (int i = 0; i < plans.size(); i++) {
            if (!this.supports(plans.get(i))) {
                continue;
            }
            for (int j = i + 1; j < plans.size(); j++) {
                checkCancel();
                if (isInterrupted()) {
                    return plans;
                }
                if (i == j) {
                    continue;
                }
                if (!this.supports(plans.get(j))) {
                    continue;
                }
                boolean done = apply(plans, plans.get(i), plans.get(j));
                if (done) {
                    i = -1;
                    break;
                }
            }
        }
        return plans;
    }

    /**
     * Tries to un-merge the pair; {@code true} if the plan list was changed.
     */
    protected boolean apply(List<P> plans, P firstPlan, P secondPlan) {
        if (firstPlan.equals(secondPlan)) {
            return false;
        }
        if (!this.supports(firstPlan) || !this.supports(secondPlan)) {
            return false;
        }

        if (unMerge(plans, firstPlan, secondPlan)) {
            return true;
        }
        return unMerge(plans, secondPlan, firstPlan);
    }

    /**
     * Swaps the customers the two plans hold in each other's zone, on copies, and commits the result when the resources
     * can still be assigned.
     */
    protected boolean unMerge(List<P> plans, P firstPlan, P secondPlan) {
        if (supports(firstPlan, secondPlan)) {
            List<P> tempPlans = plans.stream().map(P::copy).collect(Collectors.toList());
            P tempFirstPlan = tempPlans.get(plans.indexOf(firstPlan));
            P tempSecondPlan = tempPlans.get(plans.indexOf(secondPlan));

            // Sorted so the orientation of the swap does not depend on hash iteration order.
            List<String> zones = firstPlan.getCustomers().stream().map(Nodeable::getZone).distinct().sorted().toList();
            String firstZone = zones.getFirst();
            String secondZone = zones.get(1);

            List<N> firstNodes = tempFirstPlan.getCustomers().stream().filter(n -> n.getZone().equals(firstZone))
                    .toList();
            List<N> secondNodes = tempSecondPlan.getCustomers().stream().filter(n -> n.getZone().equals(secondZone))
                    .toList();
            tempFirstPlan.getNodes().removeAll(firstNodes);
            tempSecondPlan.getNodes().removeAll(secondNodes);

            N firstPlanDestination = tempFirstPlan.getDestination();
            if (firstPlanDestination != null) {
                tempFirstPlan.getNodes().remove(firstPlanDestination);
                tempFirstPlan.getNodes().addAll(secondNodes);
                tempFirstPlan.getNodes().add(firstPlanDestination);
            } else {
                tempFirstPlan.getNodes().addAll(secondNodes);
            }

            N secondPlanDestination = tempSecondPlan.getDestination();
            if (secondPlanDestination != null) {
                tempSecondPlan.getNodes().remove(secondPlanDestination);
                tempSecondPlan.getNodes().addAll(firstNodes);
                tempSecondPlan.getNodes().add(secondPlanDestination);
            } else {
                tempSecondPlan.getNodes().addAll(firstNodes);
            }

            tempPlans.set(plans.indexOf(firstPlan), localSearchAlgorithm.apply(tempFirstPlan));
            tempPlans.set(plans.indexOf(secondPlan), localSearchAlgorithm.apply(tempSecondPlan));

            if (trackableAssignment.assign(tempPlans)) {
                plans.clear();
                plans.addAll(tempPlans);
                log.debug(getMessage(firstPlan, secondPlan));
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code plan} may take part in an un-merge.
     */
    protected boolean supports(P plan) {
        return true;
    }

    /**
     * Whether both plans serve exactly the same two real zones; never under {@link OptimizationType#NO_RESTRICTION},
     * and never when a customer has no zone or the default {@code "NoZone"}.
     */
    protected boolean supports(P firstPlan, P secondPlan) {
        if (optimizationType == OptimizationType.NO_RESTRICTION) {
            return false;
        }
        Set<String> firstZones = zones(firstPlan);
        Set<String> secondZones = zones(secondPlan);
        if (firstZones.contains(null) || firstZones.contains(DEFAULT_ZONE)) {
            return false;
        }
        return firstZones.size() == 2 && secondZones.size() == 2 && isEqualCollection(firstZones, secondZones);
    }

    private Set<String> zones(P plan) {
        Set<String> zones = new HashSet<>();
        plan.getCustomers().forEach(customer -> zones.add(customer.getZone()));
        return zones;
    }

    /**
     * The debug message logged for an accepted un-merge.
     */
    protected String getMessage(P firstPlan, P secondPlan) {
        return "DoubleUnMerge plans";
    }

    /**
     * Propagates cancellation to the nested local search before cancelling itself.
     */
    @Override
    public void cancel() {
        localSearchAlgorithm.cancel();
        super.cancel();
    }

    /**
     * Propagates the graceful stop to the nested local search before interrupting itself.
     */
    @Override
    public void interrupt() {
        localSearchAlgorithm.interrupt();
        super.interrupt();
    }

}
