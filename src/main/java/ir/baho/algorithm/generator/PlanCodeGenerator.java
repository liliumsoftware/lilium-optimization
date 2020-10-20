package ir.baho.algorithm.generator;

/**
 * Strategy for minting unique plan codes during finalization.
 */
public interface PlanCodeGenerator {

    /**
     * A new, unique plan code.
     */
    String generate();

}
