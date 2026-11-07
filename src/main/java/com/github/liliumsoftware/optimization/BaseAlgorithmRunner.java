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

import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.domain.PlanModifier;
import com.github.liliumsoftware.optimization.domain.Planable;
import com.github.liliumsoftware.optimization.generator.PlanCodeGenerator;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Orchestration skeleton that wires the individual algorithms into an end-to-end planning run.
 *
 * <p>Subclasses implement {@link #plan(Nodeable, List)} to drive the pipeline ({@link #initialize} the cost matrix,
 * construct, optimize, {@link #finalizePlans}) and {@link #algorithms()} to enumerate every {@link BaseAlgorithm} they
 * own; the latter is what makes {@link #cancel()} / {@link #interrupt()} propagate to the whole run, so a newly added
 * algorithm <em>must</em> appear there. {@link #interrupt(Duration)} turns the graceful stop into a wall-clock budget
 * measured from {@link #startInstant} (construction time). The library never instantiates or calls a runner itself.
 *
 * <p><strong>Thread-safety.</strong> The fields are final and the modifier list is unmodifiable. {@link #cancel()},
 * {@link #interrupt()} and {@link #interrupt(Duration)} may be called from any thread while {@link #plan} runs; they
 * only raise the volatile flags of the algorithms (see {@link BaseAlgorithm}). Like the algorithms it owns, a runner
 * is meant for one planning run: the flags are never reset and the wall-clock budget counts from construction.
 *
 * @param <P> the plan type
 * @param <N> the node type
 * @param <C> the caller's raw customer input type, mapped to {@code N} by the concrete runner
 */
@Slf4j
public abstract class BaseAlgorithmRunner<P extends Planable<P, N, ?>, N extends Nodeable<N>, C> {

    /**
     * The cost model shared by every algorithm of the run; {@link #initialize} builds its matrix.
     */
    protected final PathCostCalculation<P, N, ?> costCalculation;
    /**
     * Mints plan codes; the library itself never calls it, the concrete runner does (typically while finalizing).
     */
    protected final PlanCodeGenerator planCodeGenerator;
    /**
     * Post-processing hooks applied by {@link #finalizePlans(List)}, in constructor order; unmodifiable.
     */
    protected final List<PlanModifier<P>> planModifiers;
    /**
     * When this runner was constructed; the reference point of {@link #interrupt(Duration)}.
     */
    protected final Instant startInstant = Instant.now();

    /**
     * Creates a runner with the given plan modifiers.
     *
     * @param costCalculation   the cost model shared by every algorithm of the run
     * @param planCodeGenerator mints plan codes for the concrete runner
     * @param planModifiers     post-processing hooks applied, in order, by {@link #finalizePlans(List)}
     * @throws NullPointerException if any argument or any modifier is {@code null}
     */
    @SafeVarargs
    protected BaseAlgorithmRunner(PathCostCalculation<P, N, ?> costCalculation,
            PlanCodeGenerator planCodeGenerator, PlanModifier<P>... planModifiers) {
        this.costCalculation = Objects.requireNonNull(costCalculation, "costCalculation");
        this.planCodeGenerator = Objects.requireNonNull(planCodeGenerator, "planCodeGenerator");
        List<PlanModifier<P>> modifiers = new ArrayList<>(planModifiers.length);
        for (PlanModifier<P> modifier : planModifiers) {
            modifiers.add(Objects.requireNonNull(modifier, "planModifier"));
        }
        this.planModifiers = Collections.unmodifiableList(modifiers);
    }

    /**
     * Creates a runner with the given plan modifiers.
     *
     * @param costCalculation   the cost model shared by every algorithm of the run
     * @param planCodeGenerator mints plan codes for the concrete runner
     * @param planModifiers     post-processing hooks applied, in order, by {@link #finalizePlans(List)}; copied, so
     *                          later changes to the caller's list have no effect
     * @throws NullPointerException if any argument or any modifier is {@code null}
     */
    protected BaseAlgorithmRunner(PathCostCalculation<P, N, ?> costCalculation,
            PlanCodeGenerator planCodeGenerator, List<PlanModifier<P>> planModifiers) {
        this.costCalculation = Objects.requireNonNull(costCalculation, "costCalculation");
        this.planCodeGenerator = Objects.requireNonNull(planCodeGenerator, "planCodeGenerator");
        List<PlanModifier<P>> modifiers = new ArrayList<>(planModifiers.size());
        for (PlanModifier<P> modifier : planModifiers) {
            modifiers.add(Objects.requireNonNull(modifier, "planModifier"));
        }
        this.planModifiers = Collections.unmodifiableList(modifiers);
    }

    /**
     * Runs the whole pipeline for {@code node} (the depot) and {@code customers}: typically {@link #initialize}, then
     * construction and improvement algorithms, then {@link #finalizePlans}. The concrete runner decides how the
     * result is exposed.
     *
     * @param node      the depot the plans start from
     * @param customers the caller's raw customer input
     */
    public abstract void plan(N node, List<C> customers);

    /**
     * Aborts the run: every algorithm in {@link #algorithms()} is {@link BaseAlgorithm#cancel() cancelled}, so it
     * throws {@link com.github.liliumsoftware.optimization.exception.CancelException} at its next check. Safe to call
     * from another thread.
     */
    public void cancel() {
        log.info("Algorithm cancelled");
        algorithms().forEach(BaseAlgorithm::cancel);
    }

    /**
     * Stops the run gracefully: every improvement algorithm in {@link #algorithms()} returns its best result so far at
     * its next loop boundary, while construction (Clarke-Wright, insertion) still completes and plans every customer it
     * can (see {@link BaseAlgorithm}). Safe to call from another thread.
     */
    public void interrupt() {
        log.info("Algorithm interrupted");
        algorithms().forEach(BaseAlgorithm::interrupt);
    }

    /**
     * {@link #interrupt() Interrupts} the run if more than {@code duration} has elapsed since this runner was
     * constructed; a no-op otherwise. Call it periodically to enforce a wall-clock budget.
     *
     * @param duration the wall-clock budget, measured from {@link #startInstant}
     * @throws NullPointerException if {@code duration} is {@code null}
     */
    public void interrupt(Duration duration) {
        if (Instant.now().isAfter(startInstant.plus(duration))) {
            interrupt();
        }
    }

    /**
     * Whether any algorithm of {@link #algorithms()} has been interrupted.
     *
     * @return {@code true} once {@link #interrupt()} reached at least one of the run's algorithms
     */
    protected boolean isInterrupted() {
        return algorithms().stream().anyMatch(BaseAlgorithm::isInterrupted);
    }

    /**
     * Initializes the cost matrix over the depot, every customer and {@code otherNodes} (e.g. a separate
     * destination depot). Must run before any algorithm.
     *
     * @param node       the depot
     * @param customers  every customer of the run
     * @param otherNodes further nodes the plans may visit, such as a destination depot
     * @throws IllegalArgumentException if two nodes share a code, or there are too many nodes for one matrix (see
     *                                  {@link PathCostCalculation#initializeCostMatrix(List)})
     * @throws IllegalStateException    if the cost provider returns a malformed matrix (see
     *                                  {@link PathCostCalculation#initializeCostMatrix(List)})
     */
    @SafeVarargs
    // Collections.addAll would trip javac's varargs lint
    @SuppressWarnings({"ManualArrayToCollectionCopy", "UseBulkOperation"})
    protected final void initialize(N node, List<N> customers, N... otherNodes) {
        log.debug("Initializing plans");
        List<N> nodes = new ArrayList<>(customers.size() + otherNodes.length + 1);
        nodes.add(node);
        nodes.addAll(customers);
        for (N other : otherNodes) {
            nodes.add(other);
        }
        costCalculation.initializeCostMatrix(nodes);
    }

    /**
     * Hands the finished plans to every {@link PlanModifier}, in constructor order, through
     * {@link PlanModifier#modify(List)}.
     *
     * @param plans the finished plans; the modifiers change the plans in place (with the default
     *              {@link PlanModifier#modify(List)}, a {@code null} list is a no-op)
     */
    protected void finalizePlans(List<P> plans) {
        log.debug("Finalizing plans");
        planModifiers.forEach(m -> m.modify(plans));
    }

    /**
     * The customers not present in any of {@code plans}, in input order. Customers are matched by
     * {@code equals}/{@code hashCode} through a hash set (which is why {@link Nodeable} requires them to be consistent
     * and a copy to be {@code equals} to its source), in time linear in the number of customers.
     *
     * @param plans     the plans built so far
     * @param customers every customer of the run; not modified
     * @return a new list of the customers no plan serves
     */
    protected List<N> getUnplannedNodes(List<P> plans, List<N> customers) {
        Set<N> plannedNodes = new HashSet<>();
        plans.forEach(p -> plannedNodes.addAll(p.getCustomers()));
        List<N> nodes = new ArrayList<>(customers);
        nodes.removeIf(plannedNodes::contains);
        return nodes;
    }

    /**
     * Every algorithm this runner owns; {@link #cancel()} and {@link #interrupt()} are fanned out to exactly this list.
     *
     * @return the run's algorithms
     */
    protected abstract List<BaseAlgorithm> algorithms();

}
