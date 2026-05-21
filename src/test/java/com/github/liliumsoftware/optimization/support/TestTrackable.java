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

package com.github.liliumsoftware.optimization.support;

import com.github.liliumsoftware.optimization.domain.Trackable;
import lombok.Getter;

import java.util.Objects;

/**
 * Concrete {@link Trackable} for tests. Has a capacity used by capacity-aware feasibility/assignment fixtures
 * and a {@code count} that the assignment layer expands into indexed units. Identity is {@code code + index}
 * so expanded units are distinct. A unit that was never expanded reports the interface default index
 * ({@link Trackable#getIndex()}), as a consumer that only stores an index once one is set would.
 */
public final class TestTrackable implements Trackable<TestTrackable> {

    private final String code;
    @Getter
    private final int capacity;
    private final int count;
    private final boolean returns;
    private Integer index;

    public TestTrackable(String code, int capacity) {
        this(code, capacity, 1, false);
    }

    public TestTrackable(String code, int capacity, int count, boolean returns) {
        this.code = code;
        this.capacity = capacity;
        this.count = count;
        this.returns = returns;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public int getCount() {
        return count;
    }

    @Override
    public boolean isReturns() {
        return returns;
    }

    @Override
    public int getIndex() {
        return index != null ? index : Trackable.super.getIndex();
    }

    @Override
    public void setIndex(int index) {
        this.index = index;
    }

    @Override
    public TestTrackable copy() {
        TestTrackable copy = new TestTrackable(code, capacity, count, returns);
        copy.index = index;
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TestTrackable t)) {
            return false;
        }
        return getIndex() == t.getIndex() && Objects.equals(code, t.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, getIndex());
    }

    @Override
    public String toString() {
        return code + "#" + getIndex();
    }

}
