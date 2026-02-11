package ir.baho.algorithm.packing;

import ir.baho.algorithm.domain.Bin;
import ir.baho.algorithm.support.TestBin;
import ir.baho.algorithm.support.TestContainer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Pins the shelf bin-packing heuristic: edge cases, fit/no-fit, rotation, stacking, and {@code getCount}.
 */
class BinPackingTest {

    private BinPacking<TestContainer, TestBin> packer(int l, int w, int h) {
        return new BinPacking<>(new TestContainer(l, w, h));
    }

    @Test
    void emptyInputPacksNothing() {
        assertThat(packer(100, 100, 100).pack(List.of())).isEmpty();
        assertThat(packer(100, 100, 100).isValid(List.of())).isTrue();
    }

    @Test
    void nonPositiveDimensionsAreRejected() {
        assertThat(packer(100, 100, 100).pack(List.of(new TestBin("z", 0, 10, 10)))).isEmpty();
        assertThat(packer(100, 100, 100).pack(List.of(new TestBin("z", 10, -1, 10)))).isEmpty();
    }

    @Test
    void singleFittingBinIsPlacedAtOrigin() {
        List<Position<TestBin>> positions = packer(100, 100, 100).pack(List.of(new TestBin("a", 10, 10, 10)));
        assertThat(positions).hasSize(1);
        assertThat(positions.getFirst().level()).isEqualTo(1);
    }

    @Test
    void positionsAreTheBottomLeftCornersOfEachBin() {
        // One row along the length: the larger bin first at x 0, the next one starting where it ends.
        TestBin small = new TestBin("small", 4, 5, 10);
        TestBin large = new TestBin("large", 6, 5, 10);
        assertThat(packer(10, 10, 10).pack(List.of(small, large)))
                .extracting(Position::bin, Position::xbl, Position::ybl)
                .containsExactly(tuple(large, 0, 0), tuple(small, 6, 0));

        // Full-length bins: each opens a new row, and rows stack along the width by the previous row's width.
        TestBin a = new TestBin("a", 10, 3, 10);
        TestBin b = new TestBin("b", 10, 2, 10);
        TestBin c = new TestBin("c", 10, 2, 10);
        assertThat(packer(10, 10, 10).pack(List.of(a, b, c)))
                .extracting(Position::bin, Position::xbl, Position::ybl)
                .containsExactly(tuple(a, 0, 0), tuple(b, 0, 3), tuple(c, 0, 5));
    }

    @Test
    void binLargerThanContainerDoesNotFit() {
        assertThat(packer(10, 10, 10).isValid(List.of(new TestBin("big", 20, 5, 5)))).isFalse();
    }

    @Test
    void binsThatFillOneRowAreAllPlaced() {
        List<TestBin> bins = List.of(
                new TestBin("a", 25, 40, 10), new TestBin("b", 25, 40, 10),
                new TestBin("c", 25, 40, 10), new TestBin("d", 25, 40, 10));
        assertThat(packer(100, 40, 10).isValid(bins)).isTrue();
    }

    @Test
    void rotationLetsAnOtherwiseTooLongBinFit() {
        // 40 long > container length 30, but rotating to 40 wide (<= container width 50) fits.
        assertThat(packer(30, 50, 10).isValid(List.of(new TestBin("r", 40, 20, 10)))).isTrue();
    }

    @Test
    void rotationIsTriedWhenTheRowsCutWidthBlocksTheUnrotatedBin() {
        // The first bin sets the row's cut width to 20; r (15 x 30) fits the free space unrotated but is wider than
        // the cut, so it used to be skipped without trying the rotated 30 x 15, which fits beside the first bin.
        BinPacking<TestContainer, TestBin> packer = packer(100, 34, 10);
        TestBin big = new TestBin("big", 60, 20, 10);
        TestBin r = new TestBin("r", 15, 30, 10);

        assertThat(packer.isValid(List.of(big, r))).isTrue();
        assertThat(packer.pack(List.of(big, r)))
                .extracting(Position::bin, Position::xbl, Position::ybl, Position::rotate)
                .containsExactly(tuple(big, 0, 0, false), tuple(r, 60, 0, true));
    }

    @Test
    void equalBinsAreEachPlacedRatherThanCountedOnce() {
        // Placed bins used to be matched by equals, so once one box was placed every equal box counted as placed too.
        BinPacking<TestContainer, Box> packer = new BinPacking<>(new TestContainer(100, 100, 10));

        assertThat(packer.pack(List.of(new Box(100, 50, 10), new Box(100, 50, 10)))).hasSize(2);
        assertThat(packer.isValid(List.of(new Box(100, 50, 10), new Box(100, 50, 10)))).isTrue();
        assertThat(packer.isValid(List.of(new Box(100, 50, 10), new Box(100, 50, 10), new Box(100, 50, 10)))).isFalse();
        assertThat(packer.getCount(new Box(100, 50, 10))).isEqualTo(2);
    }

    @Test
    void fullFootprintBinsStackOnSeparateLevels() {
        // Two bins that each fill the whole footprint can only coexist by stacking on separate levels.
        List<TestBin> bins = List.of(new TestBin("a", 100, 100, 10), new TestBin("b", 100, 100, 10));
        assertThat(packer(100, 100, 100).isValid(bins)).isTrue();
    }

    @Test
    void stackCountCapsBinToLevelOne() {
        // A stackCount of 1 forbids the bin from any level above 1, so only one can ever be placed.
        TestBin flat = new TestBin("f", 100, 100, 10, 1);
        assertThat(packer(100, 100, 100).getCount(flat)).isEqualTo(1);
    }

    @Test
    void getCountFindsHowManyIdenticalBinsFit() {
        TestContainer container = new TestContainer(100, 40, 10);
        // Footprint 25x40 => 4 per level; height 10 == container height => exactly one level fits.
        assertThat(new BinPacking<TestContainer, TestBin>(container).getCount(new TestBin("u", 25, 40, 10)))
                .isEqualTo(4);
    }

    @Test
    void binTallerThanContainerDoesNotFit() {
        // Length and width fit, but height 200 > container height 100 => must be rejected.
        assertThat(packer(100, 100, 100).isValid(List.of(new TestBin("tall", 10, 10, 200)))).isFalse();
    }

    @Test
    void stackedBinsCannotExceedContainerHeight() {
        // 10-tall full-footprint bins in a height-25 container: two stack (0-10, 10-20); a third (20-30)
        // would exceed the container, so getCount is 2 and three bins are not all placeable.
        BinPacking<TestContainer, TestBin> packer = packer(100, 100, 25);
        assertThat(packer.getCount(new TestBin("s", 100, 100, 10))).isEqualTo(2);
        assertThat(packer.isValid(List.of(
                new TestBin("a", 100, 100, 10), new TestBin("b", 100, 100, 10), new TestBin("c", 100, 100, 10))))
                .isFalse();
    }

    @Test
    void mixedHeightLevelsStackByTheirOwnHeight() {
        // Levels of 20 + 5 + 5 = 30 exactly fill a height-30 container, so all three full-footprint bins fit
        // (each level's offset counts only its own height, not the tallest bin overall).
        List<TestBin> bins = List.of(
                new TestBin("t", 100, 100, 20),
                new TestBin("s1", 100, 100, 5),
                new TestBin("s2", 100, 100, 5));
        assertThat(packer(100, 100, 30).isValid(bins)).isTrue();
        // One unit shorter and the last 5-tall level (25..30) no longer fits.
        assertThat(packer(100, 100, 29).isValid(bins)).isFalse();
    }

    @Test
    void isValidIsConsistentWithPack() {
        List<TestBin> bins = List.of(new TestBin("a", 30, 30, 10), new TestBin("b", 30, 30, 10));
        BinPacking<TestContainer, TestBin> packer = packer(60, 30, 10);
        assertThat(packer.isValid(bins)).isEqualTo(bins.size() == packer.pack(bins).size());
    }

    /**
     * A bin with value equality only, as a record gives it: two boxes of the same size are equal.
     */
    private record Box(int length, int width, int height) implements Bin<Box> {

        @Override
        public int getLength() {
            return length;
        }

        @Override
        public int getWidth() {
            return width;
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public Box copy() {
            return new Box(length, width, height);
        }
    }

}
