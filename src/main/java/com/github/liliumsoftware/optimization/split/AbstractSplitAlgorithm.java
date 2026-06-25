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

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits nodes that are too large to be served by a single trackable: each oversized node is carved into a piece
 * that fits (kept in place, via {@link #createRemainingNode}) plus a remaining node (appended) that is split again
 * or planned separately, so downstream planning only ever sees plannable-sized nodes. The resource to split against
 * is the first one in {@link #comparator()} order. The default comparator orders by code only; override it to order
 * by size so the <em>smallest</em> resource is chosen, which yields the finest-grained pieces and the tightest
 * packing.
 *
 * <p>Purely a pre-planning node transformation — it produces no plans and assigns no trackables;
 * contrast with {@link AbstractBigNodeSplitAlgorithm}, which splits against the biggest trackable and
 * emits single-node plans. Concrete subclasses supply the domain-specific "how big does the carved
 * piece / remainder end up" logic through the abstract hooks, and {@link #isShrinking} tells whether a carve made
 * progress: a resource that can carry nothing would otherwise leave a remainder as big as the node and repeat the
 * same split forever.
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractSplitAlgorithm<P
        extends Planable<P, N, T>, N extends Nodeable<N>, T extends Trackable<T>> {

    /**
     * Upper bound on the pieces one input node may be carved into; only reached when a split never converges and
     * {@link #isShrinking} cannot tell.
     */
    private static final int MAX_PIECES = 100_000;

    protected final List<T> trackables;

    /**
     * Rewrites {@code nodes} in place: every node the smallest trackable can partially serve is replaced, at its
     * position, by a carved piece plus an appended remainder. The remainder is added at the end, so a single pass
     * naturally revisits it and keeps carving until nothing further fits. A carve whose remainder does not shrink
     * ({@link #isShrinking}) is discarded and the node is left as it was. Returns the same (mutated) list; returns it
     * untouched when no trackable is available.
     *
     * @throws IllegalStateException if one input node would be carved into more than 100 000 pieces — the sign of a
     *                               resource that can carry nothing when {@link #isShrinking} is not overridden
     */
    public List<N> split(List<N> nodes) {
        List<T> sortedTrackables = trackables.stream().sorted(this.comparator()).toList();
        if (sortedTrackables.isEmpty()) {
            log.trace("Can not split, no trackable available");
            return nodes;
        }
        T smallestTrackable = sortedTrackables.getFirst();
        Map<N, Integer> pieces = new IdentityHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            N node = nodes.get(i);
            if (matchSmallestTrackable(smallestTrackable, node) && supports(smallestTrackable, node)) {
                int piece = pieces.getOrDefault(node, 1) + 1;
                if (piece > MAX_PIECES) {
                    throw new IllegalStateException("Splitting node " + node.getCode() + " against trackable "
                            + smallestTrackable.getCode() + " did not converge after " + MAX_PIECES
                            + " pieces; a resource"
                            + " that can carry nothing cannot be split against (override isShrinking, comparator()"
                            + " or supports())");
                }
                N newNode = node.copy();
                N remainingNode = createRemainingNode(node, smallestTrackable, newNode);
                requireOwnCode(newNode, remainingNode);
                if (!isShrinking(node, remainingNode)) {
                    log.warn("Node {} is not split against trackable {}: the remainder would not be smaller",
                            node.getCode(), smallestTrackable.getCode());
                    continue;
                }
                // Replace by position: removing "the node" by equals may take out an equal piece carved earlier.
                nodes.set(i, newNode);
                nodes.add(remainingNode);
                pieces.put(remainingNode, piece);
                log.debug("Split {}", newNode);
            }
        }
        return nodes;
    }

    /**
     * Whether carving {@code node} left a {@code remainingNode} strictly smaller than {@code node}. A split whose
     * remainder does not shrink — typically against a resource that can carry nothing — is discarded and the node is
     * kept unsplit instead of being carved again forever. The library cannot measure node sizes, so the default
     * accepts every carve (a runaway split then stops with an {@link IllegalStateException}); override it with the
     * domain's size comparison.
     */
    protected boolean isShrinking(N node, N remainingNode) {
        return true;
    }

    /**
     * Orders the fleet; nodes are split against the first resource in this order. By code unless overridden.
     */
    protected Comparator<T> comparator() {
        return Comparator.comparing(T::getCode);
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
     * Whether {@code node} is too big for {@code trackable}, the resource it is split against.
     */
    protected abstract boolean matchSmallestTrackable(T trackable, N node);

}
