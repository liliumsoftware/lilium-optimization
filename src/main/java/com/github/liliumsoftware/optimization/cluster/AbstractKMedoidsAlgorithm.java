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

package com.github.liliumsoftware.optimization.cluster;

import com.github.liliumsoftware.optimization.BaseAlgorithm;
import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.calculation.CostCalculation;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.domain.Planable;
import com.github.liliumsoftware.optimization.insertion.AbstractInsertionAlgorithm;
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
 * K-medoids clustering used as a plan seeding/re-seeding step.
 *
 * <p>Each round takes the current plans' medoids (the customer of each plan with the smallest summed cost to the plan's
 * other customers, see {@link PathCostCalculation#getMedoid}), builds one seed plan per medoid ({@link #getNewPlan})
 * and assigns the seeds resources in order, skipping a seed the fleet cannot serve together with the seeds accepted
 * before it (its medoid then becomes an ordinary customer). Every other customer — those of the current plans, the
 * customers of the input plans an earlier round could not place, and the caller's {@code unplannedNodes} — is then
 * offered to the seeds: customers nearest to some medoid go first, and each
 * tries the medoids its zone neighbours ({@link #getMedoids}) from nearest to farthest, joining the first seed plan the
 * insertion algorithm can extend and the fleet can still serve ({@link TrackableAssignment#assign(List)} over the whole
 * set). Between medoids that are equally close to a customer, the earlier seed is tried first.
 *
 * <p>The rounds repeat until the medoid set stops changing, so the number of clusters is fixed by the number of input
 * plans rather than chosen by the algorithm. "Stops changing" is checked as set equality against every earlier
 * round's medoid set (a repeated set ends the loop, so it always terminates); a round whose medoids are merely a
 * subset of an earlier set — for example because a seed found no resource — is not a fixed point and the loop goes
 * on.
 *
 * <p><strong>The best round is returned.</strong> Among the rounds that placed at least one customer, the result is
 * the plan set serving the most customers, ties broken by the lowest {@link CostCalculation#totalCost total cost} and
 * then by the earliest round. The input plans are not a candidate: they are returned only when no round placed a
 * customer. A customer whose zone neighbours no medoid's zone, or that no seed plan can feasibly absorb, is absent
 * from that round's plans, but it is offered again in every later round. The result may still serve fewer customers
 * than the input even when the input plans were feasible. Callers recover such customers by diffing the result
 * against their input (see {@code BaseAlgorithmRunner#getUnplannedNodes}). No customer is duplicated as long as node
 * copies are {@code equals} to their source (the {@code Nodeable} contract).
 *
 * <p><strong>Signals.</strong> The algorithm itself never calls {@link #checkCancel()}: a {@code cancel()} takes effect
 * inside the nested insertion algorithm, which it propagates to. {@code interrupt()} is polled at the start of each
 * round: an interrupted run returns the best round completed so far (the input plans when none placed a customer).
 *
 * <p><strong>Thread-safety.</strong> The instance holds only its collaborators and the two {@link BaseAlgorithm}
 * flags; {@link #apply} may run concurrently on independent inputs as far as the collaborators allow.
 *
 * @param <P> the plan/route type
 * @param <N> the node/stop type
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractKMedoidsAlgorithm<P
        extends Planable<P, N, ?>, N extends Nodeable<N>> extends BaseAlgorithm {

    /**
     * The cost model: medoids, customer-to-medoid costs and the zone neighbourhood.
     */
    protected final PathCostCalculation<P, N, ?> costCalculation;
    /**
     * Assigns resources to the seed plans and validates every enlarged plan set.
     */
    protected final TrackableAssignment<P, ?> trackableAssignment;
    /**
     * Inserts each customer into a seed plan at its best feasible position.
     */
    protected final AbstractInsertionAlgorithm<P, N, ?> insertionAlgorithm;

    /**
     * Re-seeds {@code plans} (plus {@code unplannedNodes}) around their medoids as described in the class
     * documentation and returns the best round's plans (most customers served, then lowest total cost, then earliest
     * round), which may omit customers that could not be placed. Neither the caller's list nor its plans are
     * modified; the returned list is the caller's {@code plans} itself when no round placed a customer (or the run was
     * interrupted before one did).
     *
     * @param origin         the depot the seed plans start from
     * @param unplannedNodes customers not in any plan that should be offered to the seeds in every round; not modified
     * @param plans          the current plans, each with at least one customer; its size fixes the number of clusters
     * @return the plans of the best round that placed a customer, or {@code plans}
     * @throws IllegalArgumentException if any input plan has no customers (it has no medoid), or if two customers of
     *                                  the plans and {@code unplannedNodes} share a code
     * @throws ArithmeticException      if a plan's medoid cost sum, or the total cost of a round's plans, does not
     *                                  fit a {@code long}
     * @throws com.github.liliumsoftware.optimization.exception.CancelException if the insertion algorithm is cancelled
     *     or the running thread is interrupted during an insertion
     */
    public List<P> apply(N origin, List<N> unplannedNodes, List<P> plans) {
        List<N> allCustomers = new ArrayList<>(plans.stream().flatMap(p -> p.getCustomers().stream()).toList());
        allCustomers.addAll(unplannedNodes);
        requireDistinctCodes(allCustomers);
        List<P> input = plans;
        List<P> best = null;
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

            // The customers of the current plans first, then every other customer in input order: those an earlier
            // round could not place are offered again, just like the caller's unplannedNodes.
            List<N> otherNodes = new ArrayList<>(plans.stream().flatMap(p -> p.getCustomers().stream()).toList());
            Set<String> offered = otherNodes.stream().map(Nodeable::getCode).collect(Collectors.toSet());
            for (N customer : allCustomers) {
                if (!offered.contains(customer.getCode())) {
                    otherNodes.add(customer);
                }
            }
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

            boolean placed = false;
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
                    // On copies of the other plans: assign(List) re-assigns every plan in place, and a refused
                    // attempt must not leave a plan of the accepted set on another unit, or on none.
                    List<P> candidatePlans = new ArrayList<>(initialPlans.size());
                    for (P accepted : initialPlans) {
                        candidatePlans.add(accepted == plan ? initialPlan.getFirst() : accepted.copy());
                    }
                    if (trackableAssignment.assign(candidatePlans)) {
                        initialPlans = candidatePlans;
                        placed = true;
                        break;
                    }
                }
            }

            // A round that placed no customer leaves the plans as they were, so the next medoid set repeats.
            if (placed) {
                plans = initialPlans;
                if (best == null || isBetter(plans, best)) {
                    best = plans;
                }
            }

            if (log.isDebugEnabled()) {
                log.debug(getMessage(plans));
            }

            Set<N> finalMedoids = new HashSet<>(plans.stream().map(costCalculation::getMedoid).toList());
            if (historyMedoids.stream().anyMatch(history -> new HashSet<>(history).equals(finalMedoids))) {
                break;
            }
        }
        return best == null ? input : best;
    }

    /**
     * Whether {@code candidate} is a strictly better round result than {@code best}: more customers served, or as many
     * at a lower {@link CostCalculation#totalCost total cost}. Ties keep the earlier round.
     */
    private boolean isBetter(List<P> candidate, List<P> best) {
        long candidateCustomers = candidate.stream().mapToLong(Planable::getCustomerCount).sum();
        long bestCustomers = best.stream().mapToLong(Planable::getCustomerCount).sum();
        if (candidateCustomers != bestCustomers) {
            return candidateCustomers > bestCustomers;
        }
        return CostCalculation.totalCost(candidate) < CostCalculation.totalCost(best);
    }

    /**
     * The medoids whose zone neighbours the zone of {@code node}: the clusters it may join. The default asks
     * {@link PathCostCalculation#isNeighbor isNeighbor(medoidZone, nodeZone)}, which is directed and literal under
     * {@code LOCATION_ZONE}.
     *
     * @param node    the customer to place
     * @param medoids the seeded medoids, in seed order
     * @return the admissible medoids, in seed order
     */
    protected List<N> getMedoids(N node, List<N> medoids) {
        return medoids.stream().filter(m -> costCalculation.isNeighbor(m.getZone(), node.getZone())).toList();
    }

    /**
     * The debug message logged after a round; only called when debug logging is enabled.
     *
     * @param plans the plans after the round
     * @return the message
     */
    protected String getMessage(List<P> plans) {
        return "Cluster plans";
    }

    /**
     * Builds a seed plan from {@code origin} serving only {@code customer}; its resource is assigned afterwards.
     *
     * @param origin   the depot
     * @param customer the medoid; the plan's customers must contain an object {@code equals} to it
     * @return a new plan
     */
    protected abstract P getNewPlan(N origin, N customer);

    /**
     * Propagates cancellation to the nested insertion algorithm before cancelling itself; the cancel takes effect at
     * the insertion's next probe.
     */
    @Override
    public void cancel() {
        insertionAlgorithm.cancel();
        super.cancel();
    }

    /**
     * Propagates the graceful stop to the nested insertion algorithm before interrupting itself; {@link #apply}
     * finishes the round in progress and returns the best round so far (see {@link #apply}) instead of starting
     * another.
     */
    @Override
    public void interrupt() {
        insertionAlgorithm.interrupt();
        super.interrupt();
    }

}
