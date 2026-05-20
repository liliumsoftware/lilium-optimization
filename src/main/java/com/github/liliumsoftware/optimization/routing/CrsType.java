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

package com.github.liliumsoftware.optimization.routing;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Kind of GeoJSON CRS reference: a named system ({@code NAME}) or a linked one ({@code LINK}). In JSON the values are
 * written in lower case, {@code "name"} and {@code "link"}, as the GeoJSON (2008) specification defines them; the
 * upper-case names earlier versions wrote are still accepted on input.
 */
public enum CrsType {

    @JsonProperty("name")
    @JsonAlias("NAME")
    NAME,

    @JsonProperty("link")
    @JsonAlias("LINK")
    LINK

}
