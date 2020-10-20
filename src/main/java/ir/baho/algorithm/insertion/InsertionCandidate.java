package ir.baho.algorithm.insertion;

import ir.baho.algorithm.domain.Nodeable;
import ir.baho.algorithm.domain.Planable;
import lombok.Data;

/**
 * The result of trying to insert one node into one plan: the {@code originalPlan}, the {@code candidatePlan}
 * with the node inserted, and the {@code deltaCost} between them. Subclasses extend this to carry
 * domain-specific data the insertion heuristics compare on.
 *
 * <p>A candidate built with the {@code (originalPlan, newNode)} constructor is the "cannot insert" sentinel:
 * its {@code candidatePlan} is {@code null} and its delta cost is {@link #MONTH}, a value large enough to
 * sort such candidates last without overflowing when summed.
 */
@Data
public class InsertionCandidate<P extends Planable<P, N, ?>, N extends Nodeable<N>> {

    /**
     * Sentinel delta cost (~30 days in ms) marking a node that cannot be inserted into a plan.
     */
    protected static final long MONTH = 2592000000L;

    private final P originalPlan;
    private final P candidatePlan;
    private final N newNode;
    private final long deltaCost;

    /**
     * A real candidate: {@code candidatePlan} is {@code originalPlan} with {@code newNode} inserted and recosted;
     * the delta is the cost difference.
     */
    public InsertionCandidate(P originalPlan, P candidatePlan, N newNode) {
        this.originalPlan = originalPlan;
        this.candidatePlan = candidatePlan;
        this.newNode = newNode;
        this.deltaCost = candidatePlan.getCost() - originalPlan.getCost();
    }

    /**
     * The "cannot insert" sentinel for {@code newNode} into {@code originalPlan} ({@code candidatePlan == null},
     * delta {@link #MONTH}).
     */
    public InsertionCandidate(P originalPlan, N newNode) {
        this.originalPlan = originalPlan;
        this.candidatePlan = null;
        this.newNode = newNode;
        this.deltaCost = MONTH;
    }

}
