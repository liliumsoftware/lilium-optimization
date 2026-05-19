package com.github.liliumsoftware.optimization.support;

import com.github.liliumsoftware.optimization.assignment.TrackableAlternativeFinder;

import java.util.List;

/**
 * Concrete {@link TrackableAlternativeFinder} treating equal-capacity resources as interchangeable.
 */
public final class TestAlternativeFinder extends TrackableAlternativeFinder<TestTrackable> {

    public TestAlternativeFinder(List<TestTrackable> trackables) {
        super(trackables);
    }

    @Override
    protected boolean isAlternative(TestTrackable trackable, TestTrackable alternative) {
        return trackable.getCapacity() == alternative.getCapacity();
    }

}
