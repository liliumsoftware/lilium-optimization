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

import org.junit.jupiter.api.Test;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the uniqueness guarantee of one {@link DateTimePlanCodeGenerator} instance: codes minted in a tight loop or from
 * several threads are all distinct and their timestamps strictly increase, even when the clock does not advance
 * between calls; plus the input contract and layout of the explicit-date {@code generate}.
 */
class DateTimePlanCodeUniquenessTest {

    private final DateTimePlanCodeGenerator generator = new DateTimePlanCodeGenerator();

    @Test
    void codesMintedInATightLoopAreAllDistinct() {
        // 20 000 calls take far less than the clock's resolution allows distinct readings for, so the timestamp alone
        // would repeat; the random suffix alone (90 000 values) would collide with near certainty as well.
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            codes.add(generator.generate());
        }

        assertThat(codes).hasSize(20_000);
    }

    @Test
    void theTimestampPartStrictlyIncreasesFromOneCodeToTheNext() {
        String previous = generator.generate();
        for (int i = 0; i < 5_000; i++) {
            String code = generator.generate();
            assertThat(code).hasSize(25).containsOnlyDigits();
            // equal-length digit strings: lexicographic order is numeric order
            assertThat(code.substring(0, 20)).as("code %d", i).isGreaterThan(previous.substring(0, 20));
            previous = code;
        }
    }

    @Test
    void codesMintedConcurrentlyFromOneInstanceAreAllDistinct() throws InterruptedException {
        Set<String> codes = ConcurrentHashMap.newKeySet();
        List<Throwable> failures = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 5_000; i++) {
                        codes.add(generator.generate());
                    }
                } catch (Throwable e) {
                    synchronized (failures) {
                        failures.add(e);
                    }
                }
            }));
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(failures).isEmpty();
        assertThat(codes).hasSize(4 * 5_000);
    }

    @Test
    void aTemporalWithoutLocalDateTimeFieldsIsRejected() {
        assertThatThrownBy(() -> generator.generate(Instant.now())).isInstanceOf(DateTimeException.class);
        assertThatThrownBy(() -> generator.generate(LocalDate.of(2026, 1, 1))).isInstanceOf(DateTimeException.class);
    }

    @Test
    void aYearOutsideTheTwoDigitWindowIsWrittenInFull() {
        // 2000..2099 print as two digits (25 characters in all); any other year in full, four digits: 27.
        assertThat(generator.generate(LocalDateTime.of(2099, 12, 31, 0, 0))).hasSize(25).startsWith("991231");
        assertThat(generator.generate(LocalDateTime.of(2000, 1, 1, 0, 0))).hasSize(25).startsWith("000101");
        assertThat(generator.generate(LocalDateTime.of(1999, 12, 31, 0, 0))).hasSize(27).startsWith("19991231");
        assertThat(generator.generate(LocalDateTime.of(2100, 1, 1, 0, 0))).hasSize(27).startsWith("21000101");
    }

    @Test
    void theNanoOfDayIsWrittenAsFourteenDigits() {
        // 23:59:59.999999999 is 86 399 999 999 999 ns, the largest nano-of-day: exactly 14 digits.
        String code = generator.generate(LocalDateTime.of(2026, 10, 31, 23, 59, 59, 999_999_999));

        assertThat(code.substring(0, 20)).isEqualTo("261031" + "86399999999999");
        assertThat(generator.generate(LocalDateTime.of(2026, 10, 31, 0, 0, 0, 1)).substring(6, 20))
                .isEqualTo("00000000000001");
    }

}
