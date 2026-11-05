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

package com.github.liliumsoftware.optimization.optimization;

import com.github.liliumsoftware.optimization.BaseAlgorithm;
import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.calculation.CostCalculation;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.domain.Planable;
import com.github.liliumsoftware.optimization.exception.CancelException;
import com.github.liliumsoftware.optimization.insertion.AbstractInsertionAlgorithm;
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;
import com.github.liliumsoftware.optimization.split.ZoneGroups;
import com.github.liliumsoftware.optimization.split.ZoneSplitAlgorithm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Inter-plan improvement skeleton shared by {@link AbstractExchangeAlgorithm} (swap segments between two plans) and
 * {@link AbstractRelocateAlgorithm} (move a segment from one plan to another): it enumerates plan pairs, start
 * positions and segment lengths, builds every candidate move on copies and keeps a move only when it is assignable and
 * cheaper.
 *
 * <p><strong>Search.</strong> {@link #apply(List, List)} visits every unordered plan pair once as (earlier plan, later
 * plan) in list order; {@link #apply(List, List, Planable, Planable)} enumerates the start positions in both plans and
 * {@link #apply(List, List, Planable, Planable, int, int)} the segment lengths, longest first: up to {@code chain}
 * stops on the first plan's side and, with {@code cross}, up to {@code chain} stops on the second plan's side too
 * (otherwise a single position). Both subclasses try every start pair in both orientations (each plan of the pair as
 * the first plan), so the neighbour test runs from either plan's segment and, without {@code cross}, either plan may
 * give up the multi-stop segment.
 *
 * <p><strong>Neighbours.</strong> The subclasses only try a move when its anchor in the second plan (the first stop of
 * the second segment, or the stop a relocated segment is placed against) is among the {@code neighbors} nearest nodes
 * of the moved segment's first stop: the first {@code neighbors} entries of
 * {@link PathCostCalculation#getClosestNodes(String)} for that stop, after leaving out every node whose code appears in
 * the source plan (the stop itself and a depot whose code it shares included) and every node whose zone does not list
 * the stop's zone as a neighbour. Other nodes of the matrix, such as other plans' depots and destinations and extra
 * nodes, count toward the limit. The test only depends on the start positions, so it is evaluated through
 * {@link #supportsNeighbor} at most once per start pair and orientation. A relocation under
 * {@link ExchangeRelocateType#INSERTION}, and a relocation into a plan without customers, skip this test.
 *
 * <p><strong>Acceptance.</strong> Every candidate copies the whole plan list, applies {@link #doOperation} to the
 * copies, drops the first plan if it was left without customers, and is accepted only if
 * {@link TrackableAssignment#assign(List, List) trackableAssignment.assign(otherPlans, candidate)} succeeds and
 * {@link #compare} reports an improvement over the best set accepted so far. When the move is accepted the list
 * contents are replaced by the accepted copies, every plan is run through the local search, and the scan restarts from
 * the first pair. The touched plans also go through the local search earlier, depending on the
 * {@link ExchangeRelocateType}: before the move is judged ({@code TWO_OPT}), right after it is judged an improvement
 * ({@code SIMPLE}), or not separately ({@code INSERTION}). The search terminates because every accepted move
 * strictly lowers the summed cost; an override of {@link #compare} that is not a strict improvement could keep
 * accepting moves forever, so one call of {@link #apply(List, List)} stops after 100&nbsp;000 accepted moves (logged
 * once at WARN) and returns the plans as they are.
 *
 * <p><strong>Mutation and signals.</strong> The list passed in is mutated by replacing its elements; the plan objects
 * the caller put in it are never modified, because every move works on copies. {@link #cancel()} aborts with a
 * {@link CancelException}; {@link #interrupt()} is polled before every plan pair and returns the list with the moves
 * accepted so far. Both signals are forwarded to the local search and insertion algorithms.
 *
 * <p><strong>Thread-safety.</strong> No mutable state beyond the signal flags; one instance may run on independent
 * lists concurrently. {@link #apply(List, int)} runs zone groups in parallel, so the collaborators must be thread-safe
 * as well (the library's own are).
 *
 * @param <P> the plan type
 * @param <N> the node type
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractExchangeRelocateAlgorithm<P
        extends Planable<P, N, ?>, N extends Nodeable<N>> extends BaseAlgorithm {

    /**
     * Upper bound on the moves one call of {@link #apply(List, List)} accepts; only reached when {@link #compare} keeps
     * accepting moves that do not converge (for example by accepting ties).
     */
    private static final int MAX_MOVES = 100_000;

    /**
     * Supplies the nearest-node ranking for the neighbour test and the zone neighbourhood; must be initialized with
     * every node of the plans.
     */
    protected final PathCostCalculation<P, N, ?> costCalculation;
    /**
     * Re-assigns the resources of every candidate plan set; a move whose plans cannot all be assigned is rejected.
     */
    protected final TrackableAssignment<P, ?> trackableAssignment;
    /**
     * Improves the touched plans and, after every accepted move, every plan.
     */
    protected final LocalSearchAlgorithm<P> localSearchAlgorithm;
    /**
     * Re-inserts removed segments under {@link ExchangeRelocateType#INSERTION}; unused by the other types.
     */
    protected final AbstractInsertionAlgorithm<P, N, ?> insertionAlgorithm;
    /**
     * How removed segments are re-added and when the touched plans are locally searched.
     */
    protected final ExchangeRelocateType type;
    /**
     * How many nearest nodes of a segment's first stop qualify as move targets (see the class description).
     */
    protected final int neighbors;
    /**
     * The maximum number of stops in a moved segment.
     */
    protected final int chain;
    /**
     * Whether the second plan's segment may also be up to {@link #chain} stops long, rather than a single position.
     */
    protected final boolean cross;

    /**
     * Partitions {@code plans} into at most {@code maxSplitCount} independent zone groups (see
     * {@link ZoneSplitAlgorithm}) and optimizes them through {@link ZoneGroups}: first in parallel, each group limited
     * to the resources its own plans hold, then, for the groups that wanted an idle resource, once more one after the
     * other in input order against the current plans of the other groups. No resource is booked twice and the result
     * does not depend on thread timing.
     *
     * @param plans         the plans to improve; not modified, nor are the plan objects in it
     * @param maxSplitCount the maximum number of zone groups
     * @return a new list holding every group's result, groups in the order of their first plan in {@code plans}
     * @throws CancelException     if the search was cancelled
     * @throws ArithmeticException if a summed cost does not fit a {@code long}
     */
    public List<P> apply(List<P> plans, int maxSplitCount) {
        return ZoneGroups.apply(costCalculation, trackableAssignment, maxSplitCount, plans, this::apply);
    }

    /**
     * Optimizes the whole list as one group, without zone partitioning and with no plans of other groups to honour.
     *
     * @param plans the plans to improve; a mutable list owned by the caller, whose elements are replaced by improved
     *              copies (the plan objects themselves are not modified)
     * @return {@code plans}
     * @throws CancelException     if the search was cancelled
     * @throws ArithmeticException if a summed cost does not fit a {@code long}
     */
    public List<P> apply(List<P> plans) {
        return apply(new ArrayList<>(), plans);
    }

    /**
     * Scans every unordered plan pair, in list order as (earlier plan, later plan), and lets
     * {@link #apply(List, List, Planable, Planable)} try the moves between them; after every accepted move the scan
     * restarts from the first pair so that newly created opportunities are re-examined. Ends when a full scan accepts
     * nothing, at the next pair once {@link #interrupt()} has been called, or right after the 100&nbsp;000th accepted
     * move (logged at WARN; only an override of {@link #compare} that is not a strict improvement gets there). Plans
     * rejected by {@link #supports(Planable)} are skipped.
     *
     * @param otherPlans the plans of the other zone groups, whose resources the assignment must leave alone; not
     *                   optimized and not modified here
     * @param plans      the plans to improve; its elements are replaced by improved copies
     * @return {@code plans}
     * @throws CancelException     if the search was cancelled
     * @throws ArithmeticException if a summed cost does not fit a {@code long}
     */
    protected List<P> apply(List<P> otherPlans, List<P> plans) {
        int moves = 0;
        for (int i = 0; i < plans.size(); i++) {
            if (!this.supports(plans.get(i))) {
                continue;
            }
            for (int j = i + 1; j < plans.size(); j++) {
                if (isInterrupted()) {
                    return plans;
                }
                if (i == j) {
                    continue;
                }
                if (!this.supports(plans.get(j))) {
                    continue;
                }
                boolean done = apply(otherPlans, plans, plans.get(i), plans.get(j));
                if (done) {
                    if (++moves >= MAX_MOVES) {
                        log.warn("Exchange/relocate stopped after {} accepted moves without converging; check that"
                                + " compare() is a strict improvement", MAX_MOVES);
                        return plans;
                    }
                    i = -1;
                    break;
                }
            }
        }
        return plans;
    }

    /**
     * Tries the moves between two plans: every start position {@code i} of {@code firstPlan} against every start
     * position {@code j} of {@code secondPlan}, delegating each pair to
     * {@link #apply(List, List, Planable, Planable, int, int)} and stopping at the first accepted move. Positions count
     * every stop, depots included; {@link #supports(Planable, Planable, int, int, int, int)} rejects segments that
     * touch a depot.
     *
     * @param otherPlans the plans of the other zone groups, honoured by the resource assignment
     * @param plans      the plans being improved; its elements are replaced when a move is accepted
     * @param firstPlan  an element of {@code plans}, the source of the first segment
     * @param secondPlan another element of {@code plans}, the target or source of the second segment
     * @return {@code true} if a move was accepted and {@code plans} changed
     * @throws CancelException     if the search was cancelled
     * @throws ArithmeticException if a summed cost does not fit a {@code long}
     */
    protected boolean apply(List<P> otherPlans, List<P> plans, P firstPlan, P secondPlan) {
        if (firstPlan.equals(secondPlan)) {
            return false;
        }
        if (!this.supports(firstPlan) || !this.supports(secondPlan)) {
            return false;
        }
        for (int i = 0; i < firstPlan.getCount(); i++) {
            for (int j = 0; j < secondPlan.getCount(); j++) {
                if (this.apply(otherPlans, plans, firstPlan, secondPlan, i, j)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The move/accept step for a fixed pair of start positions. It sweeps the first segment's length from
     * {@code min(chain, firstPlan.getCount())} down to one and, for each, the second side's length from
     * {@code min(chain, secondPlan.getCount())} (or one, without {@code cross}) down to one. Every combination admitted
     * by {@link #supportsSecondPlanRange} and {@link #supports(Planable, Planable, int, int, int, int)} is built on a
     * copy of the whole list by {@link #doOperation}, provided {@link #supportsNeighbor} admits the start pair (asked
     * once, at the first admitted combination; when it refuses, the call returns {@code false} without building
     * anything); the first plan is dropped when left without customers (for
     * every type: it needs no resource and its depot-to-depot cost must not count against the move), and the candidate
     * is accepted when the resources can be assigned against {@code otherPlans} and {@link #compare} prefers it to the
     * best set accepted so far. Under {@link ExchangeRelocateType#SIMPLE} the two touched plans of an accepted
     * candidate are then run through the local search. The longest first-side length that yields an accepted
     * candidate ends the sweep (all its second-side lengths are still tried). If a candidate was accepted, the
     * contents of {@code plans} are replaced by the best one and every plan is run through the local search.
     *
     * @param otherPlans the plans of the other zone groups, honoured by the resource assignment
     * @param plans      the plans being improved; its elements are replaced when a move is accepted
     * @param firstPlan  an element of {@code plans}, the source of the first segment
     * @param secondPlan another element of {@code plans}, the target or source of the second segment
     * @param i          the start position of the first segment in {@code firstPlan}
     * @param j          the start position of the second segment (or target position) in {@code secondPlan}
     * @return {@code true} if a move was accepted and {@code plans} changed
     * @throws CancelException     if the search was cancelled
     * @throws ArithmeticException if a summed cost does not fit a {@code long}
     */
    protected boolean apply(List<P> otherPlans, List<P> plans, P firstPlan, P secondPlan, int i, int j) {
        List<P> bestPlans = plans;
        // The neighbour test depends on the start positions only, so it is asked once, when the first segment
        // combination passes the other checks; a failed test rules out every combination of this (i, j).
        boolean neighborChecked = false;

        for (int x = Math.min(chain, firstPlan.getCount()) - 1; x >= 0; x--) {
            int firstPlanEnd = i + x + 1;
            if (firstPlanEnd > firstPlan.getCount()) {
                continue;
            }

            boolean done = false;
            for (int y = (cross ? Math.min(chain, secondPlan.getCount()) : 1) - 1; y >= 0; y--) {
                checkCancel();

                int secondPlanEnd = j + y + 1;
                if (!supportsSecondPlanRange(secondPlan, j, secondPlanEnd)) {
                    continue;
                }

                if (!this.supports(firstPlan, secondPlan, i, j, firstPlanEnd, secondPlanEnd)) {
                    continue;
                }
                if (!neighborChecked) {
                    if (!supportsNeighbor(firstPlan, secondPlan, i, j)) {
                        return false;
                    }
                    neighborChecked = true;
                }

                int firstIndex = plans.indexOf(firstPlan);
                int secondIndex = plans.indexOf(secondPlan);
                List<P> tempPlans = plans.stream().map(P::copy).collect(Collectors.toList());
                P tempFirstPlan = tempPlans.get(firstIndex);
                P tempSecondPlan = tempPlans.get(secondIndex);

                if (!doOperation(tempPlans, tempFirstPlan, tempSecondPlan, i, j, firstPlanEnd, secondPlanEnd)) {
                    continue;
                }
                // The insertion strategy replaces plans inside tempPlans, so re-read by position rather than trusting
                // the references captured before the operation.
                P movedFirst = tempPlans.get(firstIndex);
                boolean empty = movedFirst.getCustomerCount() == 0;
                // An emptied plan is dropped before the move is judged, for every type: it needs no resource and its
                // depot-to-depot cost must not count against the move.
                if (empty) {
                    tempPlans.remove(firstIndex);
                }
                if (trackableAssignment.assign(otherPlans, tempPlans) && compare(tempPlans, bestPlans)) {
                    if (type == ExchangeRelocateType.SIMPLE) {
                        int movedSecondIndex = empty && firstIndex < secondIndex ? secondIndex - 1 : secondIndex;
                        tempPlans.set(movedSecondIndex, localSearchAlgorithm.apply(tempPlans.get(movedSecondIndex)));
                        if (!empty) {
                            tempPlans.set(firstIndex, localSearchAlgorithm.apply(tempPlans.get(firstIndex)));
                        }
                    }
                    bestPlans = tempPlans;
                    done = true;
                }
            }
            if (done) {
                break;
            }
        }
        if (plans == bestPlans) {
            return false;
        }
        if (log.isDebugEnabled()) {
            log.debug(getMessage(firstPlan, secondPlan));
        }
        plans.clear();
        plans.addAll(bestPlans);
        plans.replaceAll(localSearchAlgorithm::apply);
        return true;
    }

    /**
     * Whether {@code plan} may take part in a move at all. The default implementation returns {@code true}.
     *
     * @param plan an element of the list being improved
     * @return {@code true} if moves into and out of {@code plan} may be tried
     */
    protected boolean supports(P plan) {
        return true;
    }

    /**
     * Whether the segments {@code [firstPlanStart, firstPlanEnd)} of {@code firstPlan} and
     * {@code [secondPlanStart, secondPlanEnd)} of {@code secondPlan} may be moved. The default implementation only
     * checks that {@code secondPlan}'s zone is a neighbour of {@code firstPlan}'s
     * ({@link PathCostCalculation#isNeighbor}); the subclasses add the customer tests. The nearest-neighbour test is
     * not part of this hook but of {@link #supportsNeighbor}, which is asked separately.
     *
     * @param firstPlan       the plan the first segment comes from
     * @param secondPlan      the other plan
     * @param firstPlanStart  the first position of the first segment
     * @param secondPlanStart the first position of the second segment, or the target position
     * @param firstPlanEnd    the exclusive end of the first segment
     * @param secondPlanEnd   the exclusive end of the second segment
     * @return {@code true} if the move may be built and judged
     */
    protected boolean supports(P firstPlan, P secondPlan, int firstPlanStart, int secondPlanStart, int firstPlanEnd,
            int secondPlanEnd) {
        return costCalculation.isNeighbor(firstPlan.getZone(), secondPlan.getZone());
    }

    /**
     * The neighbour test of a move whose first segment starts at {@code firstPlanStart} of {@code firstPlan} and whose
     * second segment (or target position) is {@code secondPlanStart} of {@code secondPlan}. It does not depend on the
     * segment lengths, so {@link #apply(List, List, Planable, Planable, int, int)} asks it at most once per start
     * pair and orientation, and only after a length combination passed
     * {@link #supports(Planable, Planable, int, int, int, int)} (so the positions are valid and the segments hold only
     * customers); when it fails, no combination of that start pair is built. The default implementation returns
     * {@code true}; the subclasses apply the nearest-neighbour limit described in the class description.
     *
     * @param firstPlan       the plan the first segment comes from
     * @param secondPlan      the other plan
     * @param firstPlanStart  the first position of the first segment
     * @param secondPlanStart the first position of the second segment, or the target position
     * @return {@code true} if the moves anchored at these positions may be built and judged
     */
    protected boolean supportsNeighbor(P firstPlan, P secondPlan, int firstPlanStart, int secondPlanStart) {
        return true;
    }

    /**
     * Whether {@code target} is among the {@link #neighbors} nearest nodes of {@code from}, ranked by
     * {@link PathCostCalculation#getClosestNodes(String)} after leaving out every node whose code appears in
     * {@code sourcePlan} and every node whose zone does not list {@code from}'s zone as a neighbour.
     */
    boolean isAmongNeighbors(P sourcePlan, N from, N target) {
        Set<String> sourceCodes = new HashSet<>();
        sourcePlan.getNodes().forEach(n -> sourceCodes.add(n.getCode()));
        return costCalculation.getClosestNodes(from.getCode()).stream()
                .filter(n -> !sourceCodes.contains(n.getCode())) // exclude nodes on the same plan
                .filter(n -> costCalculation.isNeighbor(n.getZone(), from.getZone())) // exclude non-neighbours
                .limit(neighbors) // use only the first neighbours
                .anyMatch(n -> Objects.equals(n.getCode(), target.getCode())); // check if the target matches
    }

    /**
     * Whether {@code [start, end)} is a position range of {@code secondPlan} the move may use; checked before
     * {@link #supports(Planable, Planable, int, int, int, int)}. The default implementation requires
     * {@code end <= secondPlan.getCount()}; the relocate variant also admits the slot right behind the last customer.
     *
     * @param secondPlan the plan the range belongs to
     * @param start      the first position of the range
     * @param end        the exclusive end of the range
     * @return {@code true} if the range may be used
     */
    protected boolean supportsSecondPlanRange(P secondPlan, int start, int end) {
        return end <= secondPlan.getCount();
    }

    /**
     * Whether the candidate set {@code newPlans} is accepted over {@code oldPlans}. The default implementation accepts
     * a strictly lower {@link CostCalculation#totalCost summed cost}. An override must also be a strict improvement
     * (irreflexive and transitive over the sets the search visits): the scan restarts after every accepted move, so
     * accepting moves that are not better only stops at the cap of 100&nbsp;000 accepted moves per call of
     * {@link #apply(List, List)}.
     *
     * @param newPlans the candidate set
     * @param oldPlans the best set accepted so far (initially the list being improved)
     * @return {@code true} to accept {@code newPlans}
     * @throws ArithmeticException if a summed cost does not fit a {@code long} (default implementation)
     */
    protected boolean compare(List<P> newPlans, List<P> oldPlans) {
        return CostCalculation.totalCost(newPlans) < CostCalculation.totalCost(oldPlans);
    }

    /**
     * Applies the move to copies: removes the segment {@code [firstPlanStart, firstPlanEnd)} of {@code firstPlan} and,
     * for an exchange, {@code [secondPlanStart, secondPlanEnd)} of {@code secondPlan}, and re-adds them as
     * {@link #type} prescribes. Implementations may mutate the copies in place and may replace elements of
     * {@code plans} (the insertion algorithm and the local search do); the caller re-reads the plans by position.
     *
     * @param plans           a fresh copy of the whole list, owned by this move
     * @param firstPlan       the copy of the plan the first segment comes from, an element of {@code plans}
     * @param secondPlan      the copy of the other plan, an element of {@code plans}
     * @param firstPlanStart  the first position of the first segment
     * @param secondPlanStart the first position of the second segment, or the target position
     * @param firstPlanEnd    the exclusive end of the first segment
     * @param secondPlanEnd   the exclusive end of the second segment
     * @return {@code false} if the move cannot be made (for example an insertion that cannot place every customer)
     */
    protected abstract boolean doOperation(List<P> plans, P firstPlan, P secondPlan, int firstPlanStart,
            int secondPlanStart, int firstPlanEnd, int secondPlanEnd);

    /**
     * The message logged at DEBUG level for an accepted move; only called when DEBUG is enabled.
     *
     * @param firstPlan  the plan the first segment came from (the original element, not the accepted copy)
     * @param secondPlan the other plan (the original element)
     * @return the log message
     */
    protected abstract String getMessage(P firstPlan, P secondPlan);

    /**
     * Requests an abort of this algorithm and of its local search and insertion algorithms; see
     * {@link BaseAlgorithm#cancel()}.
     */
    @Override
    public void cancel() {
        localSearchAlgorithm.cancel();
        insertionAlgorithm.cancel();
        super.cancel();
    }

    /**
     * Requests a graceful stop of this algorithm and of its local search and insertion algorithms; see
     * {@link BaseAlgorithm#interrupt()}.
     */
    @Override
    public void interrupt() {
        localSearchAlgorithm.interrupt();
        insertionAlgorithm.interrupt();
        super.interrupt();
    }

}
