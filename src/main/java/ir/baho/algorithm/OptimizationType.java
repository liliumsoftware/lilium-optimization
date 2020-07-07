package ir.baho.algorithm;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.stream.Stream;

/**
 * Whether zone restrictions constrain moves. {@code LOCATION_ZONE} keeps moves within/between neighbouring
 * zones (and makes Clarke-Wright plan each zone independently); {@code NO_RESTRICTION} disables zoning so
 * every zone predicate returns {@code true}. Carries a persisted {@code Integer value}; use {@link #of(int)}.
 */
@RequiredArgsConstructor
@Getter
public enum OptimizationType {

    LOCATION_ZONE(0), NO_RESTRICTION(1);

    private final Integer value;

    /**
     * The constant persisted as {@code value}.
     *
     * @throws IllegalArgumentException if no constant carries the given persisted {@code value}
     */
    public static OptimizationType of(int value) {
        return Stream.of(OptimizationType.values()).filter(type -> type.value == value).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("OptimizationType value not found"));
    }

}
