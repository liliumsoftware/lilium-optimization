package com.github.liliumsoftware.optimization.support;

import com.github.liliumsoftware.optimization.domain.Bin;

import java.util.Objects;

/**
 * Concrete {@link Bin}; identity is {@code id + index} so the packer's indexed copies stay distinct.
 */
public final class TestBin implements Bin<TestBin> {

    private final String id;
    private final int length;
    private final int width;
    private final int height;
    private final int stackCount;
    private Integer index;

    public TestBin(String id, int length, int width, int height) {
        this(id, length, width, height, Integer.MAX_VALUE);
    }

    public TestBin(String id, int length, int width, int height, int stackCount) {
        this.id = id;
        this.length = length;
        this.width = width;
        this.height = height;
        this.stackCount = stackCount;
    }

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
    public int getStackCount() {
        return stackCount;
    }

    @Override
    public int getIndex() {
        return index != null ? index : Bin.super.getIndex();
    }

    @Override
    public void setIndex(int index) {
        this.index = index;
    }

    @Override
    public TestBin copy() {
        TestBin copy = new TestBin(id, length, width, height, stackCount);
        copy.index = index;
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TestBin b)) {
            return false;
        }
        return getIndex() == b.getIndex() && Objects.equals(id, b.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, getIndex());
    }

    @Override
    public String toString() {
        return id + "#" + getIndex() + "[" + length + "x" + width + "x" + height + "]";
    }

}
