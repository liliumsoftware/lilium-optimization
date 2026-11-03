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

import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.domain.Planable;
import com.github.liliumsoftware.optimization.domain.Trackable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Splits nodes too large for any single resource by carving them against the <em>biggest</em> trackables first. For
 * each node that no trackable of the fleet can carry whole, it peels off a piece sized to the biggest trackable not yet
 * consumed, emits a dedicated single-node plan for that piece (consuming the trackable), and appends the remainder,
 * which is examined in turn and otherwise left to be planned normally. Each carve consumes one trackable, so the
 * splitting stops once every trackable is consumed; a node still too big then stays as it is. Contrast
 * {@link AbstractSplitAlgorithm}, which carves against the smallest trackable and emits no plans. The fleet is used as
 * given: each element is one unit, {@link Trackable#getCount()} is not expanded, and consumed units are recognised by
 * {@code equals}.
 *
 * <p><strong>State.</strong> The emitted plans accumulate across {@link #split(List)} calls on one instance, and so
 * do the trackables they consume: a unit carved against in one call is never handed out again by a later call. The
 * consumed units are not withdrawn from any {@code TrackableAssignment}; remove them from the pool you plan the
 * remaining nodes with. {@link #split(List)} and {@link #getSingleNodePlans()} are synchronized, and a call publishes
 * its plans only once it has completed, so a failing call adds none. Use one instance per planning run.
 *
 * @param <P> the plan type
 * @param <N> the node type
 * @param <T> the resource type
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractBigNodeSplitAlgorithm<P
        extends Planable<P, N, T>, N extends Nodeable<N>, T extends Trackable<T>> {

    /**
     * The fleet the nodes are carved against, as given to the constructor.
     */
    protected final List<T> trackables;

    /**
     * Every single-node plan emitted so far, in emission order; guarded by {@code this}, so a subclass must hold the
     * instance lock to read it (or use {@link #getSingleNodePlans()}).
     */
    protected final List<P> singleNodePlans = new ArrayList<>();

    /**
     * The trackables consumed by earlier carves, compared by {@code equals}; guarded by {@code this}.
     */
    private final List<T> consumedTrackables = new ArrayList<>();

    /**
     * Snapshot of every single-node plan emitted so far by {@link #split(List)} on this instance (accumulates
     * across calls); the returned list is an independent, unmodifiable copy.
     *
     * @return the emitted plans, in emission order
     */
    public synchronized List<P> getSingleNodePlans() {
        return List.copyOf(singleNodePlans);
    }

    /**
     * Rewrites {@code nodes} in place: every node that no trackable of the fleet can carry whole
     * ({@link #matchBiggestTrackable} holds for none, consumed trackables included) and that the biggest still
     * unconsumed trackable {@link #supports} is carved against that trackable. The carve copies the node
     * ({@link Nodeable#copy()}), lets {@link #createRemainingNode} size that copy and build the remainder, puts the
     * copy at the node's position and appends the remainder, which is examined in turn. The trackable is consumed for
     * good, and the plan {@link #getNewPlan} builds for the piece is recorded in {@link #getSingleNodePlans()}; when
     * that hook returns {@code null} no plan is recorded but the trackable stays consumed. A node the biggest
     * unconsumed trackable does not support is left as it is, and that trackable stays available for the next node.
     * The plans and consumed trackables of the call are published only once it has completed.
     *
     * @param nodes the nodes to split; must be mutable ({@code set} and {@code add}), mutated
     * @return {@code nodes}
     * @throws IllegalStateException if {@link #createRemainingNode} gives a remainder the carved piece's code; the
     *                               list may then be partly rewritten, but no plan or consumed trackable of this call
     *                               is recorded
     */
    public synchronized List<N> split(List<N> nodes) {
        List<T> sortedTrackables = trackables.stream().sorted(this.comparator()).collect(Collectors.toList());
        Collections.reverse(sortedTrackables);
        sortedTrackables.removeIf(consumedTrackables::contains);
        List<P> newPlans = new ArrayList<>();
        List<T> newlyConsumed = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            N node = nodes.get(i);
            if (!sortedTrackables.isEmpty() && trackables.stream().noneMatch(t -> matchBiggestTrackable(t, node))) {
                T trackable = sortedTrackables.getFirst();
                if (supports(trackable, node)) {
                    sortedTrackables.removeFirst();
                    newlyConsumed.add(trackable);
                    N newNode = node.copy();
                    N remainingNode = createRemainingNode(node, trackable, newNode);
                    requireOwnCode(newNode, remainingNode);
                    // Replace by position: removing "the node" by equals may take out an equal piece carved earlier.
                    nodes.set(i, newNode);
                    nodes.add(remainingNode);
                    P singleNodePlan = getNewPlan(newNode, trackable);
                    if (singleNodePlan != null) {
                        newPlans.add(singleNodePlan);
                        log.debug("Split {}", newNode.getCode());
                    } else {
                        log.trace("Can not create plan");
                    }
                }
            }
        }
        singleNodePlans.addAll(newPlans);
        consumedTrackables.addAll(newlyConsumed);
        return nodes;
    }

    /**
     * The carved piece keeps the node's code (it is the node that stays in place), so the remainder must get a code of
     * its own: codes identify customers, and two pieces sharing one would be rejected much later by the cost matrix
     * and every construction entry point, far from the cause.
     *
     * @throws IllegalStateException if {@link #createRemainingNode} returned a remainder with the carved piece's code
     */
    private void requireOwnCode(N piece, N remainder) {
        if (remainder.getCode().equals(piece.getCode())) {
            throw new IllegalStateException("createRemainingNode must give the remainder of node " + piece.getCode()
                    + " a code of its own; the carved piece keeps the node's code");
        }
    }

    /**
     * Whether {@code trackable} may serve {@code node} at all; a node the biggest unconsumed trackable may not serve is
     * left unsplit.
     *
     * @param trackable the biggest trackable not yet consumed
     * @param node      the node
     * @return {@code true} to allow the split; {@code true} by default
     */
    protected boolean supports(T trackable, N node) {
        return true;
    }

    /**
     * Carves {@code newNode} off {@code node}: resizes {@code newNode}, a fresh copy of {@code node}, to what
     * {@code trackable} can carry and returns the rest as a new node. {@code newNode} keeps the node's code and takes
     * its place in the list, so the remainder must get a code of its own.
     *
     * @param node      the node being split
     * @param trackable the resource the piece is sized for
     * @param newNode   a copy of {@code node} to resize into the carved piece; mutated
     * @return the remainder; appended to the list and examined in turn
     */
    protected abstract N createRemainingNode(N node, T trackable, N newNode);

    /**
     * Whether {@code trackable} can carry {@code node} whole. A node is split only when this holds for no trackable of
     * the fleet.
     *
     * @param trackable a resource of the fleet
     * @param node      a node to plan
     * @return {@code true} if {@code trackable} can serve {@code node} without splitting it
     */
    protected abstract boolean matchBiggestTrackable(T trackable, N node);

    /**
     * A single-node plan serving the carved piece {@code node} with {@code trackable}.
     *
     * @param node      the carved piece, already in the node list
     * @param trackable the trackable the piece was sized for; consumed by this carve
     * @return the plan, or {@code null} if none can be built (the trackable stays consumed regardless)
     */
    protected abstract P getNewPlan(N node, T trackable);

    /**
     * Orders the fleet from smallest to biggest; carves take the unconsumed trackables from the end of this order.
     *
     * @return the fleet order, biggest last
     */
    protected abstract Comparator<T> comparator();

}
