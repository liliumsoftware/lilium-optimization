package ir.baho.algorithm.feasibility;

import ir.baho.algorithm.domain.Planable;
import ir.baho.algorithm.exception.ObjectError;

import java.util.ArrayList;
import java.util.List;

/**
 * Judges whether a single plan is feasible (capacity, time windows, whatever the domain enforces). Called
 * from inside the construction and local-search loops for every candidate move, so implementations belong
 * on the hot path and must be cheap. {@link #getErrors(List)} turns infeasible plans into user-facing
 * {@link ObjectError}s after the fact.
 */
public interface FeasibilityChecker<P extends Planable<P, ?, ?>> {

    /**
     * Judges {@code plan}; called for every candidate move, so keep it cheap.
     */
    FeasibilityResult getResult(P plan);

    /**
     * Resolves a {@link FeasibilityResult#getCause() cause key} and its parameters to a user-facing message.
     */
    String getMessage(String key, Object... params);

    /**
     * One {@link ObjectError} per infeasible plan, bound to the assigned resource's code, or to the plan's own
     * code when the plan has no resource yet.
     */
    default List<ObjectError> getErrors(List<P> plans) {
        List<ObjectError> errors = new ArrayList<>();
        for (P plan : plans) {
            FeasibilityResult result = getResult(plan);
            if (!result.isFeasible()) {
                // An unassigned plan is legitimate before assignment; bind the error to the plan itself then.
                String objectName = plan.getTrackable() != null ? plan.getTrackable().getCode() : plan.getCode();
                errors.add(new ObjectError(getMessage(result.getCause(), result.getParams()), objectName));
            }
        }
        return errors;
    }

}
