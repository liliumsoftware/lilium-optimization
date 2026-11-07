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

package com.github.liliumsoftware.optimization;

import com.github.liliumsoftware.optimization.exception.CancelException;
import com.github.liliumsoftware.optimization.support.ExposedBaseAlgorithm;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins {@code BaseAlgorithm.isEqualCollection} (multiset equality) plus the cancel signal.
 */
class IsEqualCollectionTest {

    private final ExposedBaseAlgorithm base = new ExposedBaseAlgorithm();

    @Test
    void sameElementsInDifferentOrderAreEqual() {
        assertThat(base.equalCollections(List.of(1, 2, 2, 3), List.of(3, 2, 1, 2))).isTrue();
    }

    @Test
    void differentSizesAreNotEqual() {
        assertThat(base.equalCollections(List.of(1, 2, 3), List.of(1, 2, 3, 3))).isFalse();
    }

    @Test
    void sameSizeDifferentMultiplicitiesAreNotEqual() {
        assertThat(base.equalCollections(List.of(1, 2, 2), List.of(1, 1, 2))).isFalse();
    }

    @Test
    void emptyCollectionsAreEqual() {
        assertThat(base.equalCollections(List.of(), List.of())).isTrue();
    }

    @Test
    void singletonsCompareByValue() {
        assertThat(base.equalCollections(List.of("a"), List.of("a"))).isTrue();
        assertThat(base.equalCollections(List.of("a"), List.of("b"))).isFalse();
    }

    @Test
    void checkCancelThrowsAfterCancel() {
        base.cancel();
        assertThatThrownBy(base::triggerCheckCancel)
                .isInstanceOf(CancelException.class)
                .hasMessageContaining("cancelled");
    }

    @Test
    // Thread.interrupted() below is called for its side effect
    @SuppressWarnings("ResultOfMethodCallIgnored")
    void aThreadInterruptCancelsButStaysSetForTheCaller() {
        // Thread.interrupted() used to clear the flag before throwing, so code up the stack could no longer see that
        // its thread had been interrupted.
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(new ExposedBaseAlgorithm()::triggerCheckCancel).isInstanceOf(CancelException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // clears the flag so later tests on this thread are not cancelled
        }
    }

    @Test
    void checkCancelIsQuietBeforeCancel() {
        new ExposedBaseAlgorithm().triggerCheckCancel();   // must not throw
    }

    @Test
    void sameSizeWithADifferentNumberOfDistinctElementsIsNotEqual() {
        // Both have three elements, but {1, 1, 2} has two distinct values and {1, 2, 3} three.
        assertThat(base.equalCollections(List.of(1, 1, 2), List.of(1, 2, 3))).isFalse();
        assertThat(base.equalCollections(List.of(1, 2, 3), List.of(1, 1, 2))).isFalse();
    }

    @Test
    void nullElementsAreCountedLikeAnyOther() {
        assertThat(base.equalCollections(Arrays.asList(null, "a", null), Arrays.asList("a", null,
                null))).isTrue();
        assertThat(base.equalCollections(Arrays.asList(null, "a"), Arrays.asList("a", "a")))
                .isFalse();
    }

    @Test
    // a distinct String instance proves that elements are matched by equals, not identity
    @SuppressWarnings("StringOperationCanBeSimplified")
    void elementsAreMatchedByEqualsNotIdentityAndAnyCollectionTypeWorks() {
        assertThat(base.equalCollections(List.of(new String("zone")), Set.of("zone"))).isTrue();
    }

}
