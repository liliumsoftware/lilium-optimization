package com.github.liliumsoftware.optimization.generator;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the plan-code layout: 2-digit year, month, day, 14-digit nano-of-day, then a random 5-digit suffix.
 */
class DateTimePlanCodeGeneratorTest {

    private final DateTimePlanCodeGenerator generator = new DateTimePlanCodeGenerator();

    @Test
    void codeHasTimestampPrefixAndFiveDigitRandomSuffix() {
        String code = generator.generate(LocalDateTime.of(2026, 7, 25, 0, 0, 0));
        assertThat(code).hasSize(25);                       // 2 + 2 + 2 + 14 + 5
        assertThat(code).startsWith("260725");              // yy MM dd
        assertThat(code.substring(6, 20)).isEqualTo("00000000000000");   // midnight nano-of-day
        int suffix = Integer.parseInt(code.substring(20));
        assertThat(suffix).isBetween(10_000, 99_999);
    }

    @Test
    void timestampPrefixIsStableForTheSameInstant() {
        LocalDateTime instant = LocalDateTime.of(2026, 12, 31, 13, 45, 59);
        String first = generator.generate(instant);
        String second = generator.generate(instant);
        assertThat(first).hasSize(25);
        assertThat(first.substring(0, 20)).isEqualTo(second.substring(0, 20));
    }

    @Test
    void noArgGenerateProducesAWellFormedCode() {
        String code = generator.generate();
        assertThat(code).hasSize(25);
        assertThat(code).containsOnlyDigits();
    }

}
