package com.github.liliumsoftware.optimization.domain;

/**
 * A node that pre-planning validation rejected, carrying the {@link #getCause() reason} it was excluded.
 * Defaults to {@link Type#CUSTOMER} since only customers are validated before planning.
 */
public interface InvalidNodeable<N extends InvalidNodeable<N>> extends Nodeable<N> {

    /**
     * Human-readable reason the node was rejected.
     */
    String getCause();

    default Type getType() {
        return Type.CUSTOMER;
    }

}
