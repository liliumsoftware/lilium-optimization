package ir.baho.algorithm.clarkewright;

import ir.baho.algorithm.OptimizationType;
import ir.baho.algorithm.assignment.TrackableAssignment;
import ir.baho.algorithm.calculation.PathCostCalculation;
import ir.baho.algorithm.domain.Nodeable;
import ir.baho.algorithm.domain.Planable;
import ir.baho.algorithm.domain.Trackable;
import ir.baho.algorithm.localsearch.LocalSearchAlgorithm;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Clarke-Wright construction restricted to a subset of the resource fleet. {@link #apply} repeatedly frees a
 * trackable from a plan, hands that plan to a spare one, and re-plans the freed customers against the
 * remaining ("partial") trackable pool — a way to consolidate work onto fewer resources. All merge/assign
 * steps therefore go through the {@code withTrackables} variants of {@link TrackableAssignment} so they only
 * ever draw from the currently available pool rather than the full fleet.
 */
@Slf4j
public abstract class AbstractPartialClarkeWrightAlgorithm<P extends Planable<P, N, T>, N extends Nodeable<N>,
        T extends Trackable<T>> extends AbstractClarkeWrightAlgorithm<P, N> {

    private final TrackableAssignment<P, T> trackableAssignment;
    /**
     * The restricted pool of the {@link #apply} call running on the current thread. Thread-local rather than an
     * instance field so that independent {@code apply} calls on a shared instance cannot see each other's pool.
     */
    private final ThreadLocal<List<T>> trackables = new ThreadLocal<>();

    /**
     * Zone-restricted variant with the classic saving; see the parent constructors for the parameters.
     */
    public AbstractPartialClarkeWrightAlgorithm(PathCostCalculation<P, N, ?> costCalculation,
            TrackableAssignment<P, T> trackableAssignment,
            LocalSearchAlgorithm<P> localSearchAlgorithm) {
        super(costCalculation, trackableAssignment, localSearchAlgorithm);
        this.trackableAssignment = trackableAssignment;
    }

    /**
     * Classic saving under the given zone policy.
     */
    public AbstractPartialClarkeWrightAlgorithm(PathCostCalculation<P, N, ?> costCalculation,
            TrackableAssignment<P, T> trackableAssignment,
            LocalSearchAlgorithm<P> localSearchAlgorithm,
            OptimizationType optimizationType) {
        super(costCalculation, trackableAssignment, localSearchAlgorithm, optimizationType);
        this.trackableAssignment = trackableAssignment;
    }

    /**
     * λ sweep without a β term.
     */
    public AbstractPartialClarkeWrightAlgorithm(PathCostCalculation<P, N, ?> costCalculation,
            TrackableAssignment<P, T> trackableAssignment,
            LocalSearchAlgorithm<P> localSearchAlgorithm,
            OptimizationType optimizationType, List<Double> lambdas) {
        super(costCalculation, trackableAssignment, localSearchAlgorithm, optimizationType, lambdas);
        this.trackableAssignment = trackableAssignment;
    }

    /**
     * Full λ × β sweep; see {@link AbstractClarkeWrightAlgorithm#AbstractClarkeWrightAlgorithm(PathCostCalculation,
     * TrackableAssignment, LocalSearchAlgorithm, OptimizationType, List, List)} for the validation rules.
     */
    public AbstractPartialClarkeWrightAlgorithm(PathCostCalculation<P, N, ?> costCalculation,
            TrackableAssignment<P, T> trackableAssignment,
            LocalSearchAlgorithm<P> localSearchAlgorithm,
            OptimizationType optimizationType,
            List<Double> lambdas, List<Double> betas) {
        super(costCalculation, trackableAssignment, localSearchAlgorithm, optimizationType, lambdas, betas);
        this.trackableAssignment = trackableAssignment;
    }

    /**
     * Consolidates work onto fewer resources: repeatedly hands a plan's trackable over to a spare one and re-plans
     * the customers not yet covered against the remaining pool. Mutates and returns {@code plans}. Safe to call
     * concurrently on one instance with independent inputs. {@code cancel()} aborts with a
     * {@link ir.baho.algorithm.exception.CancelException}; {@code interrupt()} stops further
     * hand-overs and returns the plans as they are (the re-planning itself, being construction, always completes).
     * Plans without a resource are carried along unchanged.
     *
     * @throws IllegalArgumentException if two customers share a code
     */
    public List<P> apply(N origin, List<N> customers, List<P> plans) {
        requireDistinctCodes(customers);
        try {
            return doApply(origin, customers, plans);
        } finally {
            this.trackables.remove();
        }
    }

    private List<P> doApply(N origin, List<N> customers, List<P> plans) {
        List<T> trackables = trackableAssignment.getTrackables();
        for (int i = 0; i < trackables.size(); i++) {
            int index = i;
            for (int j = 0; j < trackables.size(); j++) {
                if (i == j) {
                    continue;
                }
                checkCancel();
                // A hand-over is an improvement step: an interrupt stops further hand-overs, keeping the plans as
                // they are.
                if (isInterrupted()) {
                    return plans;
                }
                List<P> tempPlans = plans.stream().map(P::copy).collect(Collectors.toList());
                Optional<P> plan = tempPlans.stream().filter(p -> trackables.get(index).equals(p.getTrackable()))
                        .findAny();
                T freeTrackable = trackables.get(j);
                if (plan.isPresent() && tempPlans.stream().noneMatch(p -> freeTrackable.equals(p.getTrackable()))
                        && trackableAssignment.assignWithTrackable(plan.get(), freeTrackable)) {
                    this.trackables.set(trackables.stream().filter(t -> tempPlans.stream()
                            .noneMatch(p -> t.equals(p.getTrackable()))).collect(Collectors.toList()));
                    List<P> newPlans = plan(origin, getUnplannedNodes(tempPlans, customers));
                    if (!newPlans.isEmpty()) {
                        plans.clear();
                        plans.addAll(tempPlans);
                        plans.addAll(newPlans);
                        log.trace(getMessage(plans));
                        i = -1;
                        break;
                    }
                }
            }
        }
        return plans;
    }

    @Override
    protected List<P> mergePlans(List<P> plans, List<Saving> savings) {
        List<N> customers;
        // Walk the list once instead of removing its head each time: on an ArrayList every head removal shifts all
        // remaining savings, which made this loop quadratic in the number of savings.
        for (Saving saving : savings) {
            P firstPlan = null;
            P secondPlan = null;
            for (P plan : plans) {
                checkCancel();
                if (firstPlan != null && secondPlan != null) {
                    break;
                }
                customers = plan.getCustomers();
                if (customers.isEmpty()) {
                    continue;
                }
                if (customers.getFirst().getCode().equals(saving.destination().getCode())) {
                    secondPlan = plan;
                    continue;
                }
                if (customers.getLast().getCode().equals(saving.origin().getCode())) {
                    firstPlan = plan;
                }
            }
            if (firstPlan == null || secondPlan == null) {
                log.trace("There is no route with start / end matching saving : {}. saving ignored", saving);
            } else {
                log.trace("Trying to merge based on saving: {}", saving);
                List<N> newNodes = new ArrayList<>();
                // Depot stops as in AbstractClarkeWrightAlgorithm#mergePlans: first route's origin, second route's end.
                N mergedOrigin = firstPlan.getOrigin() != null ? firstPlan.getOrigin() : secondPlan.getOrigin();
                if (mergedOrigin != null) {
                    newNodes.add(mergedOrigin);
                }
                newNodes.addAll(firstPlan.getCustomers());
                newNodes.addAll(secondPlan.getCustomers());
                N mergedDestination = secondPlan.getDestination() != null ? secondPlan.getDestination()
                        : firstPlan.getDestination();
                if (mergedDestination != null) {
                    newNodes.add(mergedDestination);
                }
                P newPlan = getNewPlan(firstPlan, newNodes);
                List<P> tempPlans = new PlanMerger<>(plans, firstPlan, secondPlan, newPlan).merge();
                List<P> mergedPlans = tempPlans.stream()
                        .filter(p -> p.getTrackable() != null || p.equals(newPlan)).collect(Collectors.toList());
                boolean result = trackableAssignment.assignWithTrackables(mergedPlans, this.partialTrackables());
                if (result) {
                    plans.clear();
                    mergedPlans.replaceAll(localSearchAlgorithm::apply);
                    plans.addAll(tempPlans.stream().filter(p -> p.getTrackable() == null && !p.equals(newPlan))
                            .toList());
                    plans.addAll(mergedPlans);
                    log.trace("Merged plan: {}", newPlan);
                } else {
                    log.trace("Merging plans failed");
                }
            }
        }
        savings.clear();
        List<P> finalPlans = plans.stream().filter(p -> p.getTrackable() != null).collect(Collectors.toList());
        for (P plan : plans.stream().filter(p -> p.getTrackable() == null).sorted(planComparator()).toList()) {
            checkCancel();
            if (trackableAssignment.assignWithTrackables(finalPlans, plan, this.partialTrackables())) {
                finalPlans.add(plan);
            } else {
                log.trace("Trackable assignment failed");
            }
        }
        finalPlans.replaceAll(localSearchAlgorithm::apply);
        log.trace("Merged plans");
        return finalPlans;
    }

    /**
     * The restricted pool {@link #apply} narrowed the fleet to, or the whole fleet when the inherited
     * {@code plan(...)} is invoked on its own (i.e. before any hand-over has happened) — without that fallback
     * a standalone {@code plan(...)} would dereference the not-yet-assigned pool.
     */
    protected List<T> partialTrackables() {
        List<T> pool = trackables.get();
        return pool == null ? trackableAssignment.getTrackables() : pool;
    }

    /**
     * The trace message logged after a hand-over.
     */
    protected String getMessage(List<P> plans) {
        return "PartialClarkeWright " + plans.size() + " plans";
    }

    /**
     * The customers not yet served by {@code plans}, matched by code so a consumer whose node {@code copy()} is not
     * {@code equals} to its source never has a served customer planned twice.
     */
    protected List<N> getUnplannedNodes(List<P> plans, List<N> customers) {
        Set<String> plannedCodes = plans.stream().flatMap(p -> p.getCustomers().stream()).map(Nodeable::getCode)
                .collect(Collectors.toSet());
        List<N> nodes = new ArrayList<>(customers);
        nodes.removeIf(n -> plannedCodes.contains(n.getCode()));
        return nodes;
    }

}
