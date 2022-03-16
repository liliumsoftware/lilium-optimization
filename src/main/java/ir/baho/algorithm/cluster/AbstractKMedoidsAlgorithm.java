package ir.baho.algorithm.cluster;

import ir.baho.algorithm.BaseAlgorithm;
import ir.baho.algorithm.assignment.TrackableAssignment;
import ir.baho.algorithm.calculation.PathCostCalculation;
import ir.baho.algorithm.domain.Nodeable;
import ir.baho.algorithm.domain.Planable;
import ir.baho.algorithm.insertion.AbstractInsertionAlgorithm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * K-medoids clustering used as a plan seeding/re-seeding step. Each round takes the current plans' medoids
 * (the most central customer of each, from the cost matrix), rebuilds one seed plan per medoid, and greedily
 * assigns every other customer to its nearest feasible medoid's plan via the insertion algorithm. It repeats
 * until the medoid set stops changing, so the number of clusters is fixed by the number of input plans rather
 * than chosen by the algorithm. "Stops changing" is checked as set equality against every earlier round's medoid set
 * (a repeated set ends the loop, so it always terminates); a round whose medoids are merely a subset of an earlier set
 * — for example because a seed found no resource — is not a fixed point and the loop goes on. Between medoids that are
 * equally close to a customer, the earlier seed is tried first.
 *
 * <p><strong>Customers that cannot be placed are left out.</strong> A customer whose zone neighbours no medoid's
 * zone, or that no medoid plan can feasibly absorb, is absent from the returned plans; it stays unplanned. Callers
 * recover such customers by diffing the result against their input (see
 * {@code BaseAlgorithmRunner#getUnplannedNodes}). No customer is ever duplicated.
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractKMedoidsAlgorithm<P
        extends Planable<P, N, ?>, N extends Nodeable<N>> extends BaseAlgorithm {

    protected final PathCostCalculation<P, N, ?> costCalculation;
    protected final TrackableAssignment<P, ?> trackableAssignment;
    protected final AbstractInsertionAlgorithm<P, N, ?> insertionAlgorithm;

    /**
     * Re-seeds {@code plans} (plus {@code unplannedNodes}) around their medoids as described in the class
     * documentation and returns the resulting plans, which may omit customers that could not be placed.
     *
     * @throws IllegalArgumentException if any input plan has no customers (it has no medoid), or if two customers of
     *                                  the plans and {@code unplannedNodes} share a code
     */
    public List<P> apply(N origin, List<N> unplannedNodes, List<P> plans) {
        List<N> allCustomers = new ArrayList<>(plans.stream().flatMap(p -> p.getCustomers().stream()).toList());
        allCustomers.addAll(unplannedNodes);
        requireDistinctCodes(allCustomers);
        List<List<N>> historyMedoids = new ArrayList<>();
        while (!isInterrupted()) {
            List<N> medoids = plans.stream().map(costCalculation::getMedoid).toList();

            historyMedoids.add(medoids);
            // Seed one plan per medoid, each against the units the earlier seeds left: a seed no resource can take
            // is skipped without dropping the seeds after it, and its medoid stays a customer the others may absorb.
            List<P> initialPlans = new ArrayList<>();
            List<N> seededMedoids = new ArrayList<>();
            for (N medoid : medoids) {
                List<P> candidatePlans = new ArrayList<>(initialPlans);
                candidatePlans.add(getNewPlan(origin, medoid));
                if (trackableAssignment.assign(candidatePlans)) {
                    initialPlans = candidatePlans;
                    seededMedoids.add(medoid);
                }
                // a refused attempt re-assigns the earlier seeds exactly as before (same order, same pool) and only
                // leaves the new seed without a resource, so the accepted set stays consistently assigned
            }

            List<N> otherNodes = new ArrayList<>(plans.stream().flatMap(p -> p.getCustomers().stream()).toList());
            otherNodes.addAll(unplannedNodes);
            otherNodes.removeAll(seededMedoids);

            // insertion order (not hash order) so equal-cost candidates are considered in input order
            Map<N, Map<N, Long>> nodeMedoidsCostMatrix = new LinkedHashMap<>();
            for (N node : otherNodes) {
                List<N> nodeMedoids = getMedoids(node, seededMedoids);
                if (!nodeMedoids.isEmpty()) {
                    nodeMedoidsCostMatrix.put(node, nodeMedoids.stream()
                            .collect(Collectors.toMap(m -> m, m -> costCalculation.calculateCost(node, m), (a, b) -> b,
                                    LinkedHashMap::new)));   // seed order, so equally close medoids keep it
                }
            }

            List<Map.Entry<N, List<N>>> nodeMedoids = nodeMedoidsCostMatrix.entrySet().stream()
                    .sorted(Comparator.comparingLong(e -> e.getValue().entrySet().stream()
                            .min(Map.Entry.comparingByValue()).orElseThrow().getValue()))
                    .map(e -> Map.entry(e.getKey(), e.getValue().entrySet().stream()
                            .sorted(Map.Entry.comparingByValue()).toList().stream().map(Map.Entry::getKey)
                            .toList())).toList();

            for (Map.Entry<N, List<N>> nMedoids : nodeMedoids) {
                for (N medoid : nMedoids.getValue()) {
                    List<P> initialPlan = new ArrayList<>();
                    P plan = initialPlans.stream().filter(p -> p.getCustomers().stream()
                            .anyMatch(c -> c.equals(medoid))).findAny().orElse(null);
                    if (plan == null) {
                        continue;
                    }
                    initialPlan.add(plan);
                    if (!insertionAlgorithm.insert(initialPlan, nMedoids.getKey())) {
                        continue;
                    }
                    // Validate the plan set *with* the enlarged plan: resources are assigned against the candidate,
                    // not against the set it is about to replace, so a vehicle can never end up over capacity.
                    List<P> candidatePlans = new ArrayList<>(initialPlans);
                    candidatePlans.set(initialPlans.indexOf(plan), initialPlan.get(0));
                    if (trackableAssignment.assign(candidatePlans)) {
                        initialPlans = candidatePlans;
                        plans = initialPlans;
                        break;
                    }
                }
            }

            log.debug(getMessage(plans));

            List<P> finalPlans = plans;
            Set<N> finalMedoids = new HashSet<>(finalPlans.stream().map(costCalculation::getMedoid).toList());
            if (historyMedoids.stream().anyMatch(history -> new HashSet<>(history).equals(finalMedoids))) {
                return plans;
            }
        }
        return plans;
    }

    /**
     * The medoids whose zone neighbours the zone of {@code node}: the clusters it may join.
     */
    protected List<N> getMedoids(N node, List<N> medoids) {
        return medoids.stream().filter(m -> costCalculation.isNeighbor(m.getZone(), node.getZone())).toList();
    }

    /**
     * The debug message logged after a round.
     */
    protected String getMessage(List<P> plans) {
        return "Cluster plans";
    }

    /**
     * A seed plan from {@code origin} serving only {@code customer}.
     */
    protected abstract P getNewPlan(N origin, N customer);

    /**
     * Propagates cancellation to the nested insertion algorithm before cancelling itself.
     */
    @Override
    public void cancel() {
        insertionAlgorithm.cancel();
        super.cancel();
    }

    /**
     * Propagates the graceful stop to the nested insertion algorithm before interrupting itself; {@link #apply} then
     * returns the plans of the round in progress.
     */
    @Override
    public void interrupt() {
        insertionAlgorithm.interrupt();
        super.interrupt();
    }

}
