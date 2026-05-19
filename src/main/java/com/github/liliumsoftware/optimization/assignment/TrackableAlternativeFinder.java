package com.github.liliumsoftware.optimization.assignment;

import com.github.liliumsoftware.optimization.domain.Trackable;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists, for a given resource, the codes of other resources that could stand in for it — the domain defines
 * interchangeability via {@link #isAlternative}. Used to offer substitute vehicles/drivers when a preferred
 * one is unavailable.
 */
@RequiredArgsConstructor
public abstract class TrackableAlternativeFinder<T extends Trackable<T>> {

    private final List<T> trackables;

    /**
     * Codes of every other resource that {@link #isAlternative} accepts as a stand-in for {@code trackable}, in
     * fleet order.
     */
    public List<String> getAlternatives(T trackable) {
        List<String> alternatives = new ArrayList<>();
        for (T t : trackables) {
            if (!trackable.getCode().equals(t.getCode()) && isAlternative(trackable, t)) {
                alternatives.add(t.getCode());
            }
        }
        return alternatives;
    }

    /**
     * Whether {@code alternative} may stand in for {@code trackable}.
     */
    protected abstract boolean isAlternative(T trackable, T alternative);

}
