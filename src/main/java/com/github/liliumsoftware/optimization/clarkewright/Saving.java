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

package com.github.liliumsoftware.optimization.clarkewright;

import com.github.liliumsoftware.optimization.domain.Nodeable;

import java.util.Comparator;

/**
 * The Clarke-Wright saving of joining the route that ends at customer {@code origin} to the route that begins at
 * customer {@code destination}, so that {@code destination} is served immediately after {@code origin}. Merges are
 * attempted in descending {@code value} order, so a higher value is a more attractive merge; a non-positive value is
 * still merged when the routes can be assigned (see {@link AbstractClarkeWrightAlgorithm}).
 *
 * @param origin      the customer that ends the first route (not the depot)
 * @param destination the customer that begins the second route (not the depot)
 * @param value       the saving; may be zero or negative
 */
public record Saving(Nodeable<?> origin, Nodeable<?> destination, double value) {

    /**
     * Orders savings best-first (descending value), breaking ties by origin code. The library itself does not use it:
     * {@link AbstractClarkeWrightAlgorithm#getSavings(com.github.liliumsoftware.optimization.domain.Nodeable,
     * java.util.List, java.util.function.Function, java.util.function.Function, double, double) getSavings} breaks a
     * tie in value first by the better plain (unblended) saving and only then by origin code. The two orders agree
     * whenever value and plain saving coincide, i.e. for {@code beta <= 0}.
     *
     * @return a comparator ordering savings best first
     */
    public static Comparator<Saving> reversedValueComparator() {
        return Comparator.comparingDouble(Saving::value).reversed().thenComparing(o -> o.origin().getCode());
    }

    /**
     * A tab-separated summary of the origin code, destination code and value, for logging.
     *
     * @return the summary
     */
    @Override
    public String toString() {
        return "Origin: " + origin.getCode() + "\t" + "Destination: " + destination.getCode() + "\t" + "Value: "
                + value;
    }

}
