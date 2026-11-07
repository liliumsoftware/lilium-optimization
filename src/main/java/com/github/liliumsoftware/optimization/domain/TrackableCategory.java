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

package com.github.liliumsoftware.optimization.domain;

/**
 * Marker for a resource classification (vehicle type, depot, skill, …), returned by
 * {@code TrackableAssignment#getCategory}. The assignment repair passes only exchange resources between two plans,
 * or reassign a plan to an idle unit, when the categories <em>differ</em>: units of one category are treated as
 * interchangeable, so swapping them cannot help. Categories are compared with {@code equals}, so implementations need
 * value equality (an {@code enum} is the natural choice).
 */
public interface TrackableCategory {
}
