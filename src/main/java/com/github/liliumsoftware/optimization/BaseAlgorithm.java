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

package com.github.liliumsoftware.optimization;

import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.exception.CancelException;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shared base for every algorithm in the library: two independent stop signals plus a multiset
 * equality helper.
 *
 * <p>The two signals are deliberately different in kind:
 * <ul>
 *   <li>{@link #cancel()} is an <em>abort</em> — {@link #checkCancel()} throws {@link CancelException}
 *       from inside the hot loops, discarding any partial result.</li>
 *   <li>{@link #interrupt()} is a <em>graceful stop</em> for <em>improvement</em> algorithms (2-opt, exchange,
 *       relocate, destroy-repair, merge, un-merge, balancing, k-medoids, the hand-overs of partial Clarke-Wright):
 *       {@link #isInterrupted()} is polled at loop boundaries so they return their best-so-far result.
 *       <em>Construction</em> always completes: Clarke-Wright merging and insertion batches never drop or skip a
 *       customer because of an interrupt (a regret batch finishes by cheapest insertion), so a spent time budget
 *       never makes construction lose customers.</li>
 * </ul>
 *
 * <p>Composite algorithms that own nested algorithms override {@link #cancel()} and {@link #interrupt()} to propagate
 * the signals. Note {@link #checkCancel()} also treats a pending thread interrupt as a cancel; the thread's interrupt
 * status is left set, so code further up the stack still sees it. Both signals are safe to raise from a different
 * thread than the one running the algorithm.
 *
 * <p><strong>Thread-safety.</strong> Algorithm instances hold only immutable configuration plus these two flags, so
 * one instance may run concurrently on independent plan lists. The flags are shared, however: {@link #cancel()} or
 * {@link #interrupt()} stops <em>every</em> run in progress on that instance, and neither flag is ever reset.
 */
public abstract class BaseAlgorithm {

    // volatile: both flags are set from another thread (the one that owns the run) and polled from inside
    // the algorithm's hot loops; without volatile the poll may never observe the write.
    private volatile boolean cancel = false;
    private volatile boolean interrupt = false;

    /**
     * Rejects a customer batch in which two nodes share a code. Codes identify customers (see {@link Nodeable}), and
     * a duplicate used to be planned twice by insertion but silently once by Clarke-Wright; every entry point that
     * receives a customer list now refuses it up front, as {@code PathCostCalculation#initializeCostMatrix} does.
     *
     * @throws IllegalArgumentException naming the first duplicated code
     */
    protected static void requireDistinctCodes(Collection<? extends Nodeable<?>> nodes) {
        Set<String> codes = new HashSet<>();
        for (Nodeable<?> node : nodes) {
            if (!codes.add(node.getCode())) {
                throw new IllegalArgumentException("Duplicate customer code: " + node.getCode());
            }
        }
    }

    /**
     * Requests an abort: the next {@link #checkCancel()} inside this algorithm (and, for composite algorithms, its
     * nested ones) throws {@link CancelException}. Never reset; safe to call from any thread.
     */
    public void cancel() {
        cancel = true;
    }

    /**
     * Requests a graceful stop: improvement loops polling {@link #isInterrupted()} return their best result so far,
     * while construction (Clarke-Wright, insertion) still completes and keeps every customer. Never reset; safe to call
     * from any thread.
     */
    public void interrupt() {
        interrupt = true;
    }

    /**
     * Stops the run when it was cancelled or its thread interrupted; algorithms call it between units of work.
     *
     * @throws CancelException if {@link #cancel()} was called or the current thread was interrupted (the interrupt
     *                         status stays set)
     */
    protected void checkCancel() throws CancelException {
        if (cancel || Thread.currentThread().isInterrupted()) {
            throw new CancelException("Algorithm cancelled");
        }
    }

    /**
     * Whether {@link #interrupt()} has been called.
     */
    protected boolean isInterrupted() {
        return interrupt;
    }

    /**
     * Multiset (bag) equality: {@code true} iff the two collections contain the same elements with the
     * same multiplicities, regardless of order. Used by the fixed-point checks that decide when a search
     * loop has converged, where two plan lists are "equal" if they hold the same plans in any order.
     */
    protected boolean isEqualCollection(final Collection<?> a, final Collection<?> b) {
        if (a.size() != b.size()) {
            return false;
        }
        final CardinalityHelper<Object> helper = new CardinalityHelper<>(a, b);
        if (helper.cardinalityA.size() != helper.cardinalityB.size()) {
            return false;
        }
        for (final Object obj : helper.cardinalityA.keySet()) {
            if (helper.freqA(obj) != helper.freqB(obj)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Element frequencies of the two collections compared by {@link #isEqualCollection}.
     */
    private record CardinalityHelper<O>(Map<O, Integer> cardinalityA, Map<O, Integer> cardinalityB) {

        public CardinalityHelper(Iterable<? extends O> a, Iterable<? extends O> b) {
            this(getCardinalityMap(a), getCardinalityMap(b));
        }

        private static <O> Map<O, Integer> getCardinalityMap(Iterable<? extends O> coll) {
            Map<O, Integer> count = new HashMap<>();
            for (O obj : coll) {
                count.merge(obj, 1, Integer::sum);
            }
            return count;
        }

        public int freqA(Object obj) {
            return getFreq(obj, cardinalityA);
        }

        public int freqB(Object obj) {
            return getFreq(obj, cardinalityB);
        }

        private int getFreq(Object obj, Map<?, Integer> freqMap) {
            Integer count = freqMap.get(obj);
            return Objects.requireNonNullElse(count, 0);
        }

    }

}
