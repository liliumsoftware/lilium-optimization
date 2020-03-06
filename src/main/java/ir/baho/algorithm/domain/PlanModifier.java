package ir.baho.algorithm.domain;

import java.util.List;

/**
 * Post-processing hook applied to finished plans (e.g. renumbering, annotating, code generation). Runners
 * invoke these after planning completes. The {@code List} overload tolerates a {@code null} list so callers
 * need not guard it.
 */
public interface PlanModifier<P extends Planable<P, ?, ?>> {

    /**
     * Post-processes one finished plan in place.
     */
    void modify(P plan);

    /**
     * Applies {@link #modify(Planable)} to every plan; tolerates a {@code null} list.
     */
    default void modify(List<P> plans) {
        if (plans == null) {
            return;
        }
        plans.forEach(this::modify);
    }

}