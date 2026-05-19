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
