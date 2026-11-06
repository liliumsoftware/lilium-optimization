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

package com.github.liliumsoftware.optimization.packing;

import com.github.liliumsoftware.optimization.support.TestBin;
import com.github.liliumsoftware.optimization.support.TestContainer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Seeded property checks of the shelf heuristic plus the edge cases behind them: {@code getCount} (found by doubling)
 * equals the count found by packing 1, 2, 3, … copies until one is left out; every placement lies inside the container
 * footprint (with the rotated dimensions when rotated), on a level its bin may occupy, and never overlaps another
 * placement on the same level; an over-tall bin cannot overflow the height check; and the unrotated orientation wins
 * whenever both fit.
 */
class BinPackingPropertyTest {

    private static BinPacking<TestContainer, TestBin> packer(int length, int width, int height) {
        return new BinPacking<>(new TestContainer(length, width, height));
    }

    private static List<TestBin> copies(TestBin bin, int count) {
        List<TestBin> copies = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            TestBin copy = bin.copy();
            copy.setIndex(i);
            copies.add(copy);
        }
        return copies;
    }

    /**
     * The count by its definition: the largest k such that k copies can all be placed (stops at the first failure).
     */
    private static int linearCount(BinPacking<TestContainer, TestBin> packer, TestBin bin) {
        int k = 0;
        while (packer.isValid(copies(bin, k + 1))) {
            k++;
        }
        return k;
    }

    private static int footprintLength(Position<TestBin> p) {
        return p.rotate() ? p.bin().getWidth() : p.bin().getLength();
    }

    private static int footprintWidth(Position<TestBin> p) {
        return p.rotate() ? p.bin().getLength() : p.bin().getWidth();
    }

    @Test
    void getCountEqualsTheLinearReferenceOverRandomInputs() {
        Random random = new Random(42);
        for (int trial = 0; trial < 300; trial++) {
            TestContainer container = new TestContainer(5 + random.nextInt(36), 5 + random.nextInt(36),
                    5 + random.nextInt(36));
            TestBin bin = new TestBin("b" + trial, 3 + random.nextInt(18), 3 + random.nextInt(18),
                    2 + random.nextInt(20), 1 + random.nextInt(4));
            BinPacking<TestContainer, TestBin> packer = new BinPacking<>(container);

            assertThat(packer.getCount(bin)).as("%s in %s", bin, container).isEqualTo(linearCount(packer, bin));
        }
    }

    @Test
    void placementsStayInsideTheFootprintAndNeverOverlapOnALevel() {
        Random random = new Random(7);
        for (int trial = 0; trial < 300; trial++) {
            TestContainer container = new TestContainer(10 + random.nextInt(60), 10 + random.nextInt(60),
                    10 + random.nextInt(40));
            List<TestBin> bins = new ArrayList<>();
            int count = 1 + random.nextInt(25);
            for (int i = 0; i < count; i++) {
                bins.add(new TestBin("b" + i, 1 + random.nextInt(40), 1 + random.nextInt(40), 1 + random.nextInt(25),
                        1 + random.nextInt(3)));
            }

            List<Position<TestBin>> positions = new BinPacking<TestContainer, TestBin>(container).pack(bins);

            assertThat(positions.stream().map(Position::bin).distinct()).as("each bin placed at most once")
                    .hasSameSizeAs(positions);
            for (Position<TestBin> p : positions) {
                assertThat(p.xbl()).as("trial %d: %s", trial, p).isNotNegative();
                assertThat(p.ybl()).as("trial %d: %s", trial, p).isNotNegative();
                assertThat(p.xbl() + footprintLength(p)).as("trial %d: %s", trial, p)
                        .isLessThanOrEqualTo(container.length());
                assertThat(p.ybl() + footprintWidth(p)).as("trial %d: %s", trial, p)
                        .isLessThanOrEqualTo(container.width());
                assertThat(p.bin().getHeight()).isLessThanOrEqualTo(container.height());
                assertThat(p.level()).isBetween(1, p.bin().getStackCount());
            }
            for (int i = 0; i < positions.size(); i++) {
                for (int j = i + 1; j < positions.size(); j++) {
                    Position<TestBin> p = positions.get(i);
                    Position<TestBin> q = positions.get(j);
                    if (p.level() != q.level()) {
                        continue;
                    }
                    boolean apart = p.xbl() + footprintLength(p) <= q.xbl() || q.xbl() + footprintLength(q) <= p.xbl()
                            || p.ybl() + footprintWidth(p) <= q.ybl() || q.ybl() + footprintWidth(q) <= p.ybl();
                    assertThat(apart).as("trial %d: %s overlaps %s", trial, p, q).isTrue();
                }
            }
        }
    }

    @Test
    void aBinTallerThanTheIntRangeCanStackNeverOverflowsTheHeightCheck() {
        // A fills the 10 x 10 footprint at height 5; on level 2, B (1 x 1 x Integer.MAX_VALUE) would need
        // 5 + MAX_VALUE, which wraps negative in int arithmetic and used to pass the "fits" check.
        TestBin a = new TestBin("A", 10, 10, 5, 1);
        TestBin b = new TestBin("B", 1, 1, Integer.MAX_VALUE, 2);
        BinPacking<TestContainer, TestBin> packer = packer(10, 10, 10);

        assertThat(packer.isValid(List.of(a, b))).isFalse();
        assertThat(packer.pack(List.of(a, b))).extracting(Position::bin).containsExactly(a);
        assertThat(packer.getCount(b)).isZero();
    }

    @Test
    void aBinWithANonPositiveDimensionCountsZero() {
        BinPacking<TestContainer, TestBin> packer = packer(10, 10, 10);

        assertThat(packer.getCount(new TestBin("l", 0, 5, 5))).isZero();
        assertThat(packer.getCount(new TestBin("w", 5, -1, 5))).isZero();
        assertThat(packer.getCount(new TestBin("h", 5, 5, 0))).isZero();
        // one bad bin among good ones is skipped; the good ones are still packed
        TestBin ok = new TestBin("ok", 5, 5, 5);
        assertThat(packer.pack(List.of(ok, new TestBin("h", 5, 5, -3)))).extracting(Position::bin).containsExactly(ok);
    }

    @Test
    void theUnrotatedOrientationIsUsedWhenBothFit() {
        // 4 x 6 fits a 10 x 10 container either way round; it must not be rotated.
        TestBin bin = new TestBin("b", 4, 6, 5);

        assertThat(packer(10, 10, 10).pack(List.of(bin)))
                .extracting(Position::xbl, Position::ybl, Position::level, Position::rotate)
                .containsExactly(tuple(0, 0, 1, false));
    }

    @Test
    void aRotatedBinIsStillBoundByTheRowsCutWidth() {
        // The first bin (8 x 3) sets the row's cut width to 3. The second (5 x 4) fits neither way within that row:
        // unrotated it is 4 wide, rotated 5 wide, both > 3; so it opens the next row at y 3, unrotated.
        TestBin first = new TestBin("first", 8, 3, 5);
        TestBin second = new TestBin("second", 5, 4, 5);

        assertThat(packer(20, 10, 5).pack(List.of(first, second)))
                .extracting(Position::bin, Position::xbl, Position::ybl, Position::rotate)
                .containsExactly(tuple(first, 0, 0, false), tuple(second, 0, 3, false));
    }

    @Test
    void getCountNumbersTheCopiesItPacksAndLeavesTheBinAlone() {
        // 2 x 2 footprints in a 4 x 4 x 3 container, height 1, stackable 3 high: 4 per level x 3 levels = 12.
        TestBin bin = new TestBin("u", 2, 2, 1, 3);
        BinPacking<TestContainer, TestBin> packer = packer(4, 4, 3);

        assertThat(packer.getCount(bin)).isEqualTo(12);
        assertThat(bin.getIndex()).as("the probe bin itself is not renumbered").isZero();
        assertThat(packer.pack(copies(bin, 12))).extracting(Position::level).containsOnly(1, 2, 3);
    }

}
