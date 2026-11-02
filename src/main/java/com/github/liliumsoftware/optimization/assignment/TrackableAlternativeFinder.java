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

package com.github.liliumsoftware.optimization.assignment;

import com.github.liliumsoftware.optimization.domain.Trackable;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists, for a given resource, the codes of other resources that could stand in for it; the domain defines
 * interchangeability through {@link #isAlternative}. Used to offer substitute vehicles/drivers when a preferred one is
 * unavailable. The fleet may be given unexpanded (one element per resource) or expanded (such as
 * {@link TrackableAssignment#getTrackables()}): every code is listed at most once either way. Holds no mutable state,
 * so one instance may serve concurrent calls as long as the fleet list is not modified.
 *
 * @param <T> the resource type
 */
@RequiredArgsConstructor
public abstract class TrackableAlternativeFinder<T extends Trackable<T>> {

    /**
     * The fleet the alternatives are drawn from, unexpanded or expanded into units; kept, not copied.
     */
    private final List<T> trackables;

    /**
     * Codes of the resources of the fleet that have a code other than {@code trackable}'s and that
     * {@link #isAlternative} accepts as a stand-in for it, each code once. When several fleet elements share a code
     * (the units of an expanded resource), the code is listed if {@link #isAlternative} accepts one of them; once
     * listed, the later elements with that code are not asked again.
     *
     * @param trackable the resource to replace; need not be an element of the fleet
     * @return a new mutable list of distinct codes, in the fleet order of their first accepted element; empty if there
     *         is no alternative
     */
    public List<String> getAlternatives(T trackable) {
        List<String> alternatives = new ArrayList<>();
        for (T t : trackables) {
            if (!trackable.getCode().equals(t.getCode()) && !alternatives.contains(t.getCode())
                    && isAlternative(trackable, t)) {
                alternatives.add(t.getCode());
            }
        }
        return alternatives;
    }

    /**
     * Whether {@code alternative} may stand in for {@code trackable}. Only called for resources with different codes.
     *
     * @param trackable   the resource to replace
     * @param alternative a resource of the fleet with a different code
     * @return {@code true} if {@code alternative} can do the job of {@code trackable}
     */
    protected abstract boolean isAlternative(T trackable, T alternative);

}
