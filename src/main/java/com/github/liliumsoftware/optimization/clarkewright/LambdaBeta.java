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

/**
 * One point in the Clarke-Wright parameter sweep. The sweep evaluates a grid of these and keeps the best plan set
 * (see {@link AbstractClarkeWrightAlgorithm}). Neither component is validated here; the algorithm's constructors
 * reject non-finite values, and β is never range-checked.
 *
 * @param lambda the weight of the direct edge in the saving formula; any finite value, it only reorders the merges
 * @param beta   the weight of the better orientation in the blended saving; {@code beta <= 0} selects the plain
 *               saving, {@code beta > 1} extrapolates beyond the better orientation
 */
public record LambdaBeta(double lambda, double beta) {

    /**
     * A λ-only point ({@code beta = 0}, i.e. the plain saving).
     *
     * @param lambda the weight of the direct edge in the saving formula
     */
    public LambdaBeta(double lambda) {
        this(lambda, 0);
    }

}
