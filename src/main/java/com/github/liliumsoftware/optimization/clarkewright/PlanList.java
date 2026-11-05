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

import com.github.liliumsoftware.optimization.domain.Planable;

import java.util.List;

/**
 * A candidate plan set together with the {@link LambdaBeta} parameters that produced it during the sweep.
 *
 * @param plans      the plans one merge pass produced; the list is held as given, not copied
 * @param lambdaBeta the parameters of that pass
 * @param <P>        the plan type
 */
public record PlanList<P extends Planable<P, ?, ?>>(List<P> plans, LambdaBeta lambdaBeta) {

}
