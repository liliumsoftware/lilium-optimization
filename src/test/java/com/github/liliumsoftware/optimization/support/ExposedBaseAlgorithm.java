package com.github.liliumsoftware.optimization.support;

import com.github.liliumsoftware.optimization.BaseAlgorithm;

import java.util.Collection;

/**
 * Exposes {@link BaseAlgorithm}'s {@code protected} multiset equality helper for direct testing.
 */
public final class ExposedBaseAlgorithm extends BaseAlgorithm {

    public boolean equalCollections(Collection<?> a, Collection<?> b) {
        return isEqualCollection(a, b);
    }

    public void triggerCheckCancel() {
        checkCancel();
    }

}
