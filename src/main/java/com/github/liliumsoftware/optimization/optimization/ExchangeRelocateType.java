package com.github.liliumsoftware.optimization.optimization;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.stream.Stream;

/**
 * How exchange/relocate moves re-add a removed segment to the target plan: {@code SIMPLE} splices it in at
 * the fixed position; {@code TWO_OPT} re-optimizes each touched plan with local search; {@code INSERTION}
 * finds the cheapest insertion point via the insertion algorithm. Carries a persisted {@code Integer value};
 * use {@link #of(int)}.
 */
@RequiredArgsConstructor
@Getter
public enum ExchangeRelocateType {

    SIMPLE(0), TWO_OPT(1), INSERTION(2);

    private final Integer value;

    /**
     * The constant persisted as {@code value}.
     *
     * @throws IllegalArgumentException if no constant carries the given persisted {@code value}
     */
    public static ExchangeRelocateType of(int value) {
        return Stream.of(ExchangeRelocateType.values()).filter(type -> type.value == value).findAny()
                .orElseThrow(() -> new IllegalArgumentException("No ExchangeRelocateType found with value: " + value));
    }

}
