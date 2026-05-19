package com.github.liliumsoftware.optimization.generator;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates plan codes as a timestamp (2-digit year, month, day, nano-of-day) followed by a random 5-digit
 * suffix. The timestamp gives roughly sortable, collision-resistant codes; the random suffix guards against
 * collisions when several are minted within the same instant (e.g. across threads).
 */
public class DateTimePlanCodeGenerator implements PlanCodeGenerator {

    private static final String PLACEHOLDER_LITERAL = "#####";

    private static final DateTimeFormatter DATETIME_FORMATTER = new DateTimeFormatterBuilder()
            .appendValueReduced(ChronoField.YEAR, 2, 5, 2000)
            .appendValue(ChronoField.MONTH_OF_YEAR, 2)
            .appendValue(ChronoField.DAY_OF_MONTH, 2)
            .appendValue(ChronoField.NANO_OF_DAY, 14)
            .appendLiteral(PLACEHOLDER_LITERAL)
            .toFormatter();

    /**
     * A code for the current instant ({@link LocalDateTime#now()}).
     */
    @Override
    public String generate() {
        return this.generate(LocalDateTime.now());
    }

    /**
     * A code for the given date-time: {@code yyMMdd} + 14-digit nano-of-day + 5 random digits (25 characters for
     * years 2000 to 2099; other years are written in full, so a four-digit year gives 27).
     */
    public String generate(TemporalAccessor date) {
        return DATETIME_FORMATTER.format(date).replace(PLACEHOLDER_LITERAL, this.getRandomNumber().toString());
    }

    private Integer getRandomNumber() {
        return ThreadLocalRandom.current().nextInt(10_000, 100_000);
    }

}
