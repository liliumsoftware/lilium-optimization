package com.github.liliumsoftware.optimization.generator;

/**
 * Strategy for minting unique plan codes during finalization.
 */
public interface PlanCodeGenerator {

    /**
     * A new, unique plan code.
     */
    String generate();

}
