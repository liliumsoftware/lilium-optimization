package ir.baho.algorithm;

import ir.baho.algorithm.domain.Nodeable;
import ir.baho.algorithm.optimization.ExchangeRelocateType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The persisted {@code value} of these enums is an API/DB contract: reordering constants or changing a value
 * would silently corrupt stored data. These round-trip tests lock the mapping.
 */
class EnumValueContractTest {

    @Test
    void nodeableTypeValuesAreStable() {
        assertThat(Nodeable.Type.ORIGIN.getValue()).isZero();
        assertThat(Nodeable.Type.CUSTOMER.getValue()).isEqualTo(1);
        assertThat(Nodeable.Type.DESTINATION.getValue()).isEqualTo(2);
        for (Nodeable.Type type : Nodeable.Type.values()) {
            assertThat(Nodeable.Type.of(type.getValue())).isEqualTo(type);
        }
        assertThatThrownBy(() -> Nodeable.Type.of(99)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void optimizationTypeValuesAreStable() {
        assertThat(OptimizationType.LOCATION_ZONE.getValue()).isZero();
        assertThat(OptimizationType.NO_RESTRICTION.getValue()).isEqualTo(1);
        for (OptimizationType type : OptimizationType.values()) {
            assertThat(OptimizationType.of(type.getValue())).isEqualTo(type);
        }
        assertThatThrownBy(() -> OptimizationType.of(7)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exchangeRelocateTypeValuesAreStable() {
        assertThat(ExchangeRelocateType.SIMPLE.getValue()).isZero();
        assertThat(ExchangeRelocateType.TWO_OPT.getValue()).isEqualTo(1);
        assertThat(ExchangeRelocateType.INSERTION.getValue()).isEqualTo(2);
        for (ExchangeRelocateType type : ExchangeRelocateType.values()) {
            assertThat(ExchangeRelocateType.of(type.getValue())).isEqualTo(type);
        }
        assertThatThrownBy(() -> ExchangeRelocateType.of(-1)).isInstanceOf(IllegalArgumentException.class);
    }

}
