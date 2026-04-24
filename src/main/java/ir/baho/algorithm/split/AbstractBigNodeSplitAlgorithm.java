package ir.baho.algorithm.split;

import ir.baho.algorithm.domain.Nodeable;
import ir.baho.algorithm.domain.Planable;
import ir.baho.algorithm.domain.Trackable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Splits nodes too large for any single resource by carving them against the <em>biggest</em> trackables
 * first. For each oversized node it peels off a chunk sized to the largest free trackable, emits a dedicated
 * single-node plan for that chunk (consuming the trackable), and leaves the remainder to be planned normally.
 * Contrast {@link AbstractSplitAlgorithm}, which carves against the smallest trackable and emits no plans.
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractBigNodeSplitAlgorithm<P
        extends Planable<P, N, T>, N extends Nodeable<N>, T extends Trackable<T>> {

    protected final List<T> trackables;

    protected final List<P> singleNodePlans = new ArrayList<>();

    /**
     * Snapshot of every single-node plan emitted so far by {@link #split(List)} on this instance (accumulates
     * across calls); the returned list is an independent, unmodifiable copy.
     */
    public synchronized List<P> getSingleNodePlans() {
        return List.copyOf(singleNodePlans);
    }

    /**
     * Rewrites {@code nodes} in place (the list must be mutable), appending remainders, and records one single-node
     * plan per carved chunk in {@code getSingleNodePlans()}, which accumulates across calls. Synchronized because
     * the accumulated plans are instance state.
     */
    public synchronized List<N> split(List<N> nodes) {
        List<T> sortedTrackables = trackables.stream().sorted(this.comparator()).collect(Collectors.toList());
        Collections.reverse(sortedTrackables);
        for (int i = 0; i < nodes.size(); i++) {
            N node = nodes.get(i);
            if (!sortedTrackables.isEmpty() && trackables.stream().noneMatch(t -> matchBiggestTrackable(t, node))) {
                T trackable = sortedTrackables.getFirst();
                if (supports(trackable, node)) {
                    sortedTrackables.removeFirst();
                    N newNode = node.copy();
                    N remainingNode = createRemainingNode(node, trackable, newNode);
                    requireOwnCode(newNode, remainingNode);
                    // Replace by position: removing "the node" by equals may take out an equal piece carved earlier.
                    nodes.set(i, newNode);
                    nodes.add(remainingNode);
                    P singleNodePlan = getNewPlan(newNode, trackable);
                    if (singleNodePlan != null) {
                        singleNodePlans.add(singleNodePlan);
                        log.debug("Split {}", singleNodePlan.getCustomers().getFirst());
                    } else {
                        log.trace("Can not create plan");
                    }
                }
            }
        }
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
     * Whether {@code trackable} may serve {@code node} at all; {@code true} by default.
     */
    protected boolean supports(T trackable, N node) {
        return true;
    }

    /**
     * The remainder of {@code node} once {@code newNode}, sized for {@code trackable}, has been carved off.
     */
    protected abstract N createRemainingNode(N node, T trackable, N newNode);

    /**
     * Whether {@code node} is too big for {@code trackable}, the biggest resource of the fleet.
     */
    protected abstract boolean matchBiggestTrackable(T trackable, N node);

    /**
     * A single-node plan serving {@code node} with {@code trackable}.
     */
    protected abstract P getNewPlan(N node, T trackable);

    /**
     * Orders the fleet; the last resource in this order is the biggest one.
     */
    protected abstract Comparator<T> comparator();

}
