package com.github.liliumsoftware.optimization.split;

import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.domain.Planable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Runs an inter-plan optimizer over the independent zone groups of a plan set, in parallel and deterministically,
 * while the groups share one fleet.
 *
 * <p><strong>Phase 1, parallel.</strong> Every group is optimized at the same time, each limited to the units its own
 * plans hold ({@link TrackableAssignment#withoutIdleUnits}): units held by other groups and idle units are both out of
 * reach. The groups therefore never compete for a unit, and each group's result depends only on its own input.
 *
 * <p><strong>Phase 2, ordered.</strong> Every group that could have used an idle unit in phase 1 (an assignment was
 * refused, or left more expensive, for want of one; see {@link TrackableAssignment#wantedIdleUnits}) is optimized
 * once more while the fleet still has idle units, one after the other in the order of its first plan in the input,
 * now against the current plans of all other groups: a group may take an idle unit, and the groups after it see that
 * unit as taken. A group that never wanted an idle unit would only repeat its phase-1 result, so it is not run again.
 * Phase 2 is skipped altogether when one unit may back several plans ({@link TrackableAssignment#sharesUnits()}).
 *
 * <p>Both phases have a fixed outcome for a given input, so the result never depends on thread timing or hash order;
 * no unit is ever booked twice. Groups come back in the order of their first plan in the input. A single group runs
 * directly, with an empty {@code otherPlans}.
 */
public final class ZoneGroups {

    private ZoneGroups() {
    }

    /**
     * Partitions {@code plans} into at most {@code maxSplitCount} zone groups (see {@link ZoneSplitAlgorithm#groups})
     * and runs {@code optimizer} over them in the two phases described above.
     *
     * @param optimizer {@code (otherPlans, group) -> optimized group}; may mutate and return {@code group}, must assign
     *                  resources against {@code otherPlans} through {@link TrackableAssignment}
     * @return a new list holding every group's result, groups in input order
     */
    public static <P extends Planable<P, N, ?>, N extends Nodeable<N>> List<P> apply(
            PathCostCalculation<P, N, ?> costCalculation, TrackableAssignment<P, ?> trackableAssignment,
            int maxSplitCount, List<P> plans, BiFunction<List<P>, List<P>, List<P>> optimizer) {
        List<List<P>> groups = new ZoneSplitAlgorithm<>(costCalculation, maxSplitCount).groups(plans);
        if (groups.isEmpty()) {
            return new ArrayList<>();
        }
        if (groups.size() == 1) {
            return new ArrayList<>(optimizer.apply(new ArrayList<>(), groups.getFirst()));
        }
        List<List<P>> heldUnitsOnly = new ArrayList<>(groups.size());
        for (int g = 0; g < groups.size(); g++) {
            heldUnitsOnly.add(trackableAssignment.withoutIdleUnits(others(groups, g), groups.get(g)));
        }
        List<List<P>> results = IntStream.range(0, groups.size()).parallel()
                .mapToObj(g -> (List<P>) new ArrayList<>(optimizer.apply(heldUnitsOnly.get(g), groups.get(g))))
                .collect(Collectors.toCollection(ArrayList::new));
        if (!trackableAssignment.sharesUnits()) {
            for (int g = 0; g < results.size(); g++) {
                if (trackableAssignment.wantedIdleUnits(heldUnitsOnly.get(g))
                        && trackableAssignment.hasIdleUnits(flatten(results))) {
                    results.set(g, new ArrayList<>(optimizer.apply(others(results, g), results.get(g))));
                }
            }
        }
        return flatten(results);
    }

    private static <P> List<P> others(List<List<P>> groups, int group) {
        List<P> others = new ArrayList<>();
        for (int h = 0; h < groups.size(); h++) {
            if (h != group) {
                others.addAll(groups.get(h));
            }
        }
        return others;
    }

    private static <P> List<P> flatten(List<List<P>> groups) {
        return groups.stream().flatMap(List::stream).collect(Collectors.toCollection(ArrayList::new));
    }

}
