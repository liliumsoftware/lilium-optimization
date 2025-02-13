package ir.baho.algorithm.support;

import ir.baho.algorithm.domain.Container;

/**
 * Concrete {@link Container} — a fixed 3D box for packing tests.
 */
public record TestContainer(int length, int width, int height) implements Container<TestContainer> {

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
    public TestContainer copy() {
        return new TestContainer(length, width, height);
    }

}
