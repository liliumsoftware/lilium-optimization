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

package com.github.liliumsoftware.optimization.merge;

import com.github.liliumsoftware.optimization.BaseAlgorithm;
import com.github.liliumsoftware.optimization.OptimizationType;
import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.domain.Planable;
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Redistributes customers between two plans across a single shared zone: when one plan is confined to exactly
 * one zone that the other also serves, that zone's customers are pulled onto the single-zone plan (which may
 * empty and remove the other). The inverse of merging along one zone boundary; accepted only if the resulting
 * assignment succeeds (there is no cost check). Only real zones qualify: a plan whose customers carry no zone or the
 * default {@code "NoZone"} of {@link Nodeable#getZone()} is never un-merged — otherwise every pair of plans would look
 * like "one zone, shared" and be folded together — and nothing is un-merged under
 * {@link OptimizationType#NO_RESTRICTION}.
 */
@Slf4j
public abstract class AbstractSingleUnMergeAlgorithm<P
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
    public AbstractSingleUnMergeAlgorithm(TrackableAssignment<P, ?> trackableAssignment,
            LocalSearchAlgorithm<P> localSearchAlgorithm) {
        this(trackableAssignment, localSearchAlgorithm, OptimizationType.LOCATION_ZONE);
    }

    /**
     * Creates an un-merge for the given zone policy.
     *
     * @param optimizationType the zone policy of the run; under {@link OptimizationType#NO_RESTRICTION} zones do not
     *                         restrict anything, so no plan is ever un-merged
     */
    public AbstractSingleUnMergeAlgorithm(TrackableAssignment<P, ?> trackableAssignment,
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
     * Moves the customers of one foreign zone out of {@code firstPlan} into {@code secondPlan}, on copies, and commits
     * the result when the resources can still be assigned.
     */
    protected boolean unMerge(List<P> plans, P firstPlan, P secondPlan) {
        if (supports(firstPlan, secondPlan)) {
            List<P> tempPlans = plans.stream().map(P::copy).collect(Collectors.toList());
            P tempFirstPlan = tempPlans.get(plans.indexOf(firstPlan));
            P tempSecondPlan = tempPlans.get(plans.indexOf(secondPlan));

            String zone = firstPlan.getCustomers().getFirst().getZone();

            List<N> secondNodes = tempSecondPlan.getCustomers().stream().filter(n -> zone.equals(n.getZone())).toList();
            tempSecondPlan.getNodes().removeAll(secondNodes);

            N firstPlanDestination = tempFirstPlan.getDestination();
            if (firstPlanDestination != null) {
                tempFirstPlan.getNodes().remove(firstPlanDestination);
                tempFirstPlan.getNodes().addAll(secondNodes);
                tempFirstPlan.getNodes().add(firstPlanDestination);
            } else {
                tempFirstPlan.getNodes().addAll(secondNodes);
            }

            tempPlans.set(plans.indexOf(firstPlan), localSearchAlgorithm.apply(tempFirstPlan));
            if (tempSecondPlan.getCustomerCount() != 0) {
                tempPlans.set(plans.indexOf(secondPlan), localSearchAlgorithm.apply(tempSecondPlan));
            } else {
                tempPlans.remove(plans.indexOf(secondPlan));
            }

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
     * Whether {@code firstPlan} serves exactly one real zone that {@code secondPlan} also serves; never under
     * {@link OptimizationType#NO_RESTRICTION}, and never for customers without a zone or in the default
     * {@code "NoZone"}.
     */
    protected boolean supports(P firstPlan, P secondPlan) {
        if (optimizationType == OptimizationType.NO_RESTRICTION) {
            return false;
        }
        Set<String> firstZones = zones(firstPlan);
        Set<String> secondZones = zones(secondPlan);
        if (firstZones.size() != 1) {
            return false;
        }
        String zone = firstZones.iterator().next();
        return zone != null && !DEFAULT_ZONE.equals(zone) && secondZones.contains(zone);
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
        return "SingleUnMerge plans";
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
