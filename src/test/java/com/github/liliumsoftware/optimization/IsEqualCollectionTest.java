package com.github.liliumsoftware.optimization;

import com.github.liliumsoftware.optimization.exception.CancelException;
import com.github.liliumsoftware.optimization.support.ExposedBaseAlgorithm;
import org.junit.jupiter.api.Test;

import java.util.List;

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

}
