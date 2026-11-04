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

package com.github.liliumsoftware.optimization.generator;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Generates plan codes as a timestamp (2-digit year, 2-digit month, 2-digit day, 14-digit nano-of-day) followed by a
 * random 5-digit suffix, for example {@code 2610311234567890123412345}; see {@link #generate(TemporalAccessor)} for
 * the exact layout.
 *
 * <p><strong>Uniqueness.</strong> {@link #generate()} stamps each code with the current local date-time, but never
 * with one it has already used: when the clock has not advanced since the previous code (a coarse system clock
 * ticks only every few milliseconds on some platforms) the timestamp is moved one nanosecond past the previous one.
 * Codes minted by one generator instance are therefore distinct and increase in timestamp order, at any rate and from
 * any number of threads. Across generator instances or JVMs only the random suffix separates codes minted at the same
 * instant, so share one instance per process. The timestamp is the system zone's local time: after a daylight-saving
 * fall-back the clock repeats an hour, and the codes then keep counting up from the last one used.
 *
 * <p>The suffix comes from {@link ThreadLocalRandom}, so codes are not reproducible between runs.
 *
 * <p><strong>Thread-safety.</strong> Thread-safe: the last timestamp is kept in an atomic reference and the formatter
 * is immutable.
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

    private final AtomicReference<LocalDateTime> lastTimestamp = new AtomicReference<>(LocalDateTime.MIN);

    /**
     * Creates a generator.
     */
    public DateTimePlanCodeGenerator() {
    }

    /**
     * A code for the current instant ({@link LocalDateTime#now()} in the system zone), moved one nanosecond past the
     * timestamp of the previous code this instance generated when the clock has not advanced since (see the class
     * description).
     *
     * @return a new code whose timestamp is later than that of every code {@code generate()} returned before on this
     *         instance
     */
    @Override
    public String generate() {
        LocalDateTime now = LocalDateTime.now();
        return this.generate(lastTimestamp.updateAndGet(last -> now.isAfter(last) ? now : last.plusNanos(1)));
    }

    /**
     * A code for the given date-time: {@code yyMMdd} + 14-digit nano-of-day + 5 random digits (25 characters for
     * years 2000 to 2099; other years are written in full, so a four-digit year gives 27). Unlike {@link #generate()}
     * this does not track earlier codes: the same {@code date} gives codes that differ only in the random suffix.
     *
     * @param date a date-time with a year, month, day and nano-of-day, such as a {@link LocalDateTime}
     * @return the code
     * @throws DateTimeException    if {@code date} lacks one of those fields (for example an
     *                              {@link java.time.Instant} or a {@link java.time.LocalDate})
     * @throws NullPointerException if {@code date} is {@code null}
     */
    public String generate(TemporalAccessor date) {
        return DATETIME_FORMATTER.format(date).replace(PLACEHOLDER_LITERAL, this.getRandomNumber().toString());
    }

    private Integer getRandomNumber() {
        return ThreadLocalRandom.current().nextInt(10_000, 100_000);
    }

}
