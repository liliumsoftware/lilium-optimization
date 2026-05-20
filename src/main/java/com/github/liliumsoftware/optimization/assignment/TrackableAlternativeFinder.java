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
