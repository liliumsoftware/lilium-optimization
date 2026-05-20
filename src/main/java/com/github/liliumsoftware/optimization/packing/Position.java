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

package com.github.liliumsoftware.optimization.packing;

import com.github.liliumsoftware.optimization.domain.Bin;

/**
 * Placement of one {@link Bin} inside a container: its bottom-left corner ({@code xbl}, {@code ybl}), the
 * stacking {@code level} (shelf) it sits on, and whether it was {@code rotate}d 90° in the horizontal plane
 * to fit.
 */
public record Position<B extends Bin<B>>(B bin, int xbl, int ybl, int level, boolean rotate) {

}
