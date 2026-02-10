package ir.baho.algorithm.split;

import ir.baho.algorithm.calculation.PathCostCalculation;
import ir.baho.algorithm.domain.Nodeable;
import ir.baho.algorithm.domain.Planable;

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
 * neighbouring zones (in either direction of the neighbour relation); groups are merged transitively until stable,
 * then capped at {@code maxSplitCount} by folding the smallest surplus groups into the largest ones (a greedy
 * bin-fit). {@link #groups} returns the groups in the order of their first plan; {@link ZoneGroups} runs an optimizer
 * over them in parallel against one shared fleet.
 */
public class ZoneSplitAlgorithm<P extends Planable<P, N, ?>, N extends Nodeable<N>> {

    private final PathCostCalculation<P, N, ?> costCalculation;
    private final int maxSplitCount;

    /**
     * Creates a splitter over the zone predicates of {@code costCalculation}.
     *
     * @param maxSplitCount upper bound on the number of groups returned; at least {@code 1}
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
     * configured maximum: the largest groups are kept and every smaller one is folded into the currently smallest kept
     * group. A plan without customers is placed by its own {@link Planable#getZone() zone} (a {@code null} zone
     * neighbours every zone), so an empty plan can still receive work from the plans around it. The groups are
     * returned in the order of their first plan in {@code plans}, each as a new mutable list. Empty input yields an
     * empty list. O(m³) worst case for m plans.
     */
    public List<List<P>> groups(List<P> plans) {
        List<List<P>> groups = plans.stream().map(p -> {
            List<P> ps = new ArrayList<>();
            ps.add(p);
            return ps;
        }).collect(Collectors.toList());
        for (int i = 0; i < groups.size(); i++) {
            List<P> ps1 = groups.get(i);
            for (int j = i + 1; j < groups.size(); j++) {
                List<P> ps2 = groups.get(j);
                if (ps1.stream().anyMatch(p1 -> ps2.stream()
                        .anyMatch(p2 -> zonesOf(p1).stream()
                                .anyMatch(z1 -> zonesOf(p2).stream()
                                        .anyMatch(z2 -> interact(z1, z2)))))) {
                    groups.remove(ps1);
                    groups.remove(ps2);
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
     * Groups {@code nodes} transitively by neighbouring zones, capped at the configured maximum number of groups.
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
                    groups.remove(ns1);
                    groups.remove(ns2);
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
        List<List<N>> largestGroups = groups.stream().limit(maxSplitCount).toList();
        groups.removeAll(largestGroups);
        while (!groups.isEmpty()) {
            List<N> group = groups.removeFirst();
            largestGroups.stream().min(Comparator.comparing(List::size)).orElse(largestGroups.getFirst()).addAll(group);
        }
        return largestGroups;
    }

}
