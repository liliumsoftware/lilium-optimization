package ir.baho.algorithm;

import ir.baho.algorithm.calculation.PathCostCalculation;
import ir.baho.algorithm.domain.Nodeable;
import ir.baho.algorithm.domain.PlanModifier;
import ir.baho.algorithm.domain.Planable;
import ir.baho.algorithm.generator.PlanCodeGenerator;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Orchestration skeleton that wires the individual algorithms into an end-to-end planning run.
 *
 * <p>Subclasses implement {@link #plan(Nodeable, List)} to drive the pipeline (initialize the cost matrix,
 * construct, optimize, finalize) and {@link #algorithms()} to enumerate every {@link BaseAlgorithm} they
 * own — the latter is what makes {@link #cancel()} / {@link #interrupt()} propagate to the whole run, so a
 * newly added algorithm <em>must</em> appear there. {@link #interrupt(Duration)} turns the graceful stop
 * into a wall-clock budget measured from {@link #startInstant} (construction time).
 *
 * @param <C> the caller's raw customer input type, mapped to {@code N} by the concrete runner
 */
@Slf4j
public abstract class BaseAlgorithmRunner<P extends Planable<P, N, ?>, N extends Nodeable<N>, C> {

    protected final PathCostCalculation<P, N, ?> costCalculation;
    protected final PlanCodeGenerator planCodeGenerator;
    protected final List<PlanModifier<P>> planModifiers;
    protected final Instant startInstant = Instant.now();

    /**
     * Creates a runner with the given plan modifiers.
     *
     * @param planModifiers post-processing hooks applied, in order, by {@link #finalizePlans(List)}
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
     * @param planModifiers post-processing hooks applied, in order, by {@link #finalizePlans(List)}; copied
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
     * Runs the whole pipeline for {@code node} (the depot) and {@code customers}; the concrete runner decides how
     * the result is exposed.
     */
    public abstract void plan(N node, List<C> customers);

    /**
     * Aborts the run: every algorithm in {@link #algorithms()} will throw {@link
     * ir.baho.algorithm.exception.CancelException}
     * at its next check. Safe to call from another thread.
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
     */
    public void interrupt(Duration duration) {
        if (Instant.now().isAfter(startInstant.plus(duration))) {
            interrupt();
        }
    }

    protected boolean isInterrupted() {
        return algorithms().stream().anyMatch(BaseAlgorithm::isInterrupted);
    }

    /**
     * Initializes the cost matrix over the depot, every customer and {@code otherNodes} (e.g. a separate
     * destination depot). Must run before any algorithm.
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
     * Applies every {@link PlanModifier} to the finished plans, in constructor order.
     */
    protected void finalizePlans(List<P> plans) {
        log.debug("Finalizing plans");
        planModifiers.forEach(m -> m.modify(plans));
    }

    /**
     * The customers not present in any of {@code plans} (matched by {@code equals}), in input order.
     */
    protected List<N> getUnplannedNodes(List<P> plans, List<N> customers) {
        List<N> plannedNodes = plans.stream().flatMap(p -> p.getCustomers().stream()).toList();
        List<N> nodes = new ArrayList<>(customers);
        nodes.removeAll(plannedNodes);
        return nodes;
    }

    /**
     * Every algorithm this runner owns; {@link #cancel()} and {@link #interrupt()} are fanned out to exactly this list.
     */
    protected abstract List<BaseAlgorithm> algorithms();

}
