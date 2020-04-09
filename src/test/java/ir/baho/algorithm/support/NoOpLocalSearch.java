package ir.baho.algorithm.support;

import ir.baho.algorithm.localsearch.LocalSearchAlgorithm;

/**
 * Identity local search — returns plans untouched, so tests can isolate the caller's own behaviour.
 */
public final class NoOpLocalSearch extends LocalSearchAlgorithm<TestPlan> {

    @Override
    public TestPlan apply(TestPlan plan) {
        return plan;
    }

}
