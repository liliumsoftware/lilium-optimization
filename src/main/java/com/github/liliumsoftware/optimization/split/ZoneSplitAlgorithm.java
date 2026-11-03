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

package com.github.liliumsoftware.optimization.split;

import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.domain.Planable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

/**
 * Partitions plans (or nodes) into groups that cannot interact, so inter-plan optimization can run each group
 * independently without cross-group moves. Two plans join the same group if any of their customers lie in
 * neighbouring zones, in either direction of {@link PathCostCalculation#isNeighbor}; groups are merged transitively
 * until stable. The neighbour relation is taken literally: under {@code LOCATION_ZONE} two plans in the same zone are
 * grouped only if that zone lists itself as a neighbour, a {@code null} zone neighbours every zone, and under
 * {@code NO_RESTRICTION} everything ends up in one group. The groups are then capped at {@code maxSplitCount}: the
 * largest groups are kept and each surplus group, largest first, is folded into the currently smallest kept group (a
 * greedy bin-fit; sizes count nodes). {@link #groups} returns the groups in the order of their first plan;
 * {@link ZoneGroups} runs an optimizer over them in parallel against one shared fleet.
 *
 * <p>Holds no mutable state: one instance may be used from several threads at once.
 *
 * @param <P> the plan type
 * @param <N> the node type
 */
public class ZoneSplitAlgorithm<P extends Planable<P, N, ?>, N extends Nodeable<N>> {

    private final PathCostCalculation<P, N, ?> costCalculation;
    private final int maxSplitCount;

    /**
     * Creates a splitter over the zone predicates of {@code costCalculation}.
     *
     * @param costCalculation the source of the zone neighbour relation; only its zone predicates are used, so its cost
     *                        matrix need not be initialized; not checked for {@code null} here
     * @param maxSplitCount   upper bound on the number of groups returned; at least {@code 1}
     * @throws IllegalArgumentException if {@code maxSplitCount < 1}
     */
    public ZoneSplitAlgorithm(PathCostCalculation<P, N, ?> costCalculation, int maxSplitCount) {
        if (maxSplitCount < 1) {
            throw new IllegalArgumentException("maxSplitCount must be at least 1, was " + maxSplitCount);
        }
        this.costCalculation = costCalculation;
        this.maxSplitCount = maxSplitCount;
    }

    /**
     * The zones a plan occupies: its customers' zones, or its own zone when it has no customers.
     */
    private static <P extends Planable<P, N, ?>, N extends Nodeable<N>> List<String> zonesOf(P plan) {
        List<N> customers = plan.getCustomers();
        if (customers.isEmpty()) {
            return Collections.singletonList(plan.getZone());
        }
        return customers.stream().map(Nodeable::getZone).toList();
    }

    /**
     * Groups {@code plans} transitively by neighbouring customer zones and caps the number of groups at the
     * configured maximum: the largest groups (by total node count, origin and destination included) are kept and each
     * surplus group, largest first, is folded into the currently smallest kept group. A plan without customers is
     * placed by its own {@link Planable#getZone() zone} (a {@code null} zone neighbours every zone), so an empty plan
     * can still receive work from the plans around it. The plans within a group keep their order in {@code plans}, and
     * the groups are returned in the order of their first plan in {@code plans}.
     *
     * <p>Worst case O(m³·z²) {@link PathCostCalculation#isNeighbor} checks for m plans with at most z distinct customer
     * zones each: up to m merges, each found by a rescan that compares every pair of plans zone by zone.
     *
     * @param plans the plans to group; not modified, plans are not copied
     * @return a new mutable list of new mutable groups; empty for empty input
     */
    public List<List<P>> groups(List<P> plans) {
        List<List<P>> groups = plans.stream().map(p -> {
            List<P> ps = new ArrayList<>();
            ps.add(p);
            return ps;
        }).collect(Collectors.toList());
        // Each plan's distinct zones, computed once: the merge loop below compares every pair of groups again after
        // each merge, and recomputing the zones there made every comparison rebuild the customer lists.
        Map<P, List<String>> zones = new IdentityHashMap<>();
        for (P plan : plans) {
            zones.computeIfAbsent(plan, p -> zonesOf(p).stream().distinct().toList());
        }
        for (int i = 0; i < groups.size(); i++) {
            List<P> ps1 = groups.get(i);
            for (int j = i + 1; j < groups.size(); j++) {
                List<P> ps2 = groups.get(j);
                if (ps1.stream().anyMatch(p1 -> ps2.stream()
                        .anyMatch(p2 -> zones.get(p1).stream()
                                .anyMatch(z1 -> zones.get(p2).stream()
                                        .anyMatch(z2 -> interact(z1, z2)))))) {
                    // by position: the groups are lists, and List.remove(Object) would compare them element-wise
                    groups.remove(j);
                    groups.remove(i);
                    List<P> mergedPlans = new ArrayList<>();
                    mergedPlans.addAll(ps1);
                    mergedPlans.addAll(ps2);
                    groups.add(mergedPlans);
                    i = -1;
                    break;
                }
            }
        }
        if (groups.size() > maxSplitCount) {
            groups.sort(Comparator.comparingLong((ToLongFunction<List<P>>) ps -> ps.stream()
                    .mapToLong(p -> p.getNodes().size()).sum()).reversed());
            List<List<P>> largestGroups = new ArrayList<>(groups.subList(0, maxSplitCount));
            for (List<P> group : groups.subList(maxSplitCount, groups.size())) {
                largestGroups.stream()
                        .min(Comparator.comparingLong(ps -> ps.stream().mapToLong(p -> p.getNodes().size()).sum()))
                        .orElseThrow()
                        .addAll(group);
            }
            groups = largestGroups;
        }
        Map<P, Integer> positions = new IdentityHashMap<>();
        for (int i = 0; i < plans.size(); i++) {
            positions.putIfAbsent(plans.get(i), i);
        }
        for (List<P> group : groups) {
            group.sort(Comparator.comparingInt(positions::get));
        }
        groups.sort(Comparator.comparingInt(group -> positions.get(group.getFirst())));
        return groups;
    }

    /**
     * Whether two zones can take part in the same move: they neighbour each other in either direction. The neighbour
     * relation is directed, and a move may cross a boundary that only one side lists, so asking one direction only
     * would make the grouping depend on the input order.
     */
    private boolean interact(String first, String second) {
        return costCalculation.isNeighbor(first, second) || costCalculation.isNeighbor(second, first);
    }

    /**
     * Groups {@code nodes} transitively by neighbouring zones (in either direction of
     * {@link PathCostCalculation#isNeighbor}) and caps the number of groups at the configured maximum: the largest
     * groups (by node count) are kept and each surplus group, largest first, is folded into the currently smallest kept
     * group. Unlike {@link #groups}, the result is not restored to input order: merged groups are appended as they form
     * and nodes keep their merge order, and after a cap the kept groups come largest first. Worst case O(n³)
     * {@link PathCostCalculation#isNeighbor} checks for n nodes.
     *
     * @param nodes the nodes to group; not modified, nodes are not copied
     * @return a new mutable list of new mutable groups; empty for empty input
     */
    public List<List<N>> split(List<N> nodes) {
        List<List<N>> groups = nodes.stream().map(p -> {
            List<N> ns = new ArrayList<>();
            ns.add(p);
            return ns;
        }).collect(Collectors.toList());
        for (int i = 0; i < groups.size(); i++) {
            List<N> ns1 = groups.get(i);
            for (int j = i; j < groups.size(); j++) {
                if (i == j) {
                    continue;
                }
                List<N> ns2 = groups.get(j);
                if (ns1.stream().anyMatch(n1 -> ns2.stream().anyMatch(n2 -> interact(n1.getZone(), n2.getZone())))) {
                    // by position: the groups are lists, and List.remove(Object) would compare them element-wise
                    groups.remove(j);
                    groups.remove(i);
                    List<N> mergedNodes = new ArrayList<>();
                    mergedNodes.addAll(ns1);
                    mergedNodes.addAll(ns2);
                    groups.add(mergedNodes);
                    i = -1;
                    break;
                }
            }
        }
        if (groups.size() <= maxSplitCount) {
            return groups;
        }
        groups.sort(Comparator.comparingInt((ToIntFunction<List<N>>) List::size).reversed());
        List<List<N>> largestGroups = new ArrayList<>(groups.subList(0, maxSplitCount));
        for (List<N> group : groups.subList(maxSplitCount, groups.size())) {
            largestGroups.stream().min(Comparator.comparing(List::size)).orElseThrow().addAll(group);
        }
        return largestGroups;
    }

}
