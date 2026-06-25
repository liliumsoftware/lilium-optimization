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

import com.github.liliumsoftware.optimization.domain.InvalidNodeable;

/**
 * Concrete {@link InvalidNodeable}: a node validation rejected, carrying the joined causes. Inherits the
 * interface's {@code CUSTOMER} type default, since only customers are validated before planning.
 */
public record TestInvalidNode(String code, String zone, String cause) implements InvalidNodeable<TestInvalidNode> {

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getZone() {
        return zone;
    }

    @Override
    public String getCause() {
        return cause;
    }

    @Override
    public TestInvalidNode copy() {
        return new TestInvalidNode(code, zone, cause);
    }

    @Override
    public String toString() {
        return code + "!" + cause;
    }

}
