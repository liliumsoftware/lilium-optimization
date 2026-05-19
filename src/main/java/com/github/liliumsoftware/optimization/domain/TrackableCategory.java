package com.github.liliumsoftware.optimization.domain;

/**
 * Marker for a resource classification. The assignment layer only exchanges/reassigns trackables across
 * <em>different</em> categories, so implementations define whatever grouping (vehicle type, depot, skill)
 * should be treated as interchangeable-versus-not.
 */
public interface TrackableCategory {
}
