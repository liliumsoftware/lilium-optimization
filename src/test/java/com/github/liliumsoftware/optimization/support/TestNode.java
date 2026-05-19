package com.github.liliumsoftware.optimization.support;

import com.github.liliumsoftware.optimization.domain.Nodeable;
import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

/**
 * Concrete {@link Nodeable} for tests. Carries an integer 2-D position so a metric (Manhattan) cost model can
 * be derived deterministically, plus a zone for zone-restriction tests. Identity is value-based over all
 * fields so a {@link #copy()} equals its source.
 *
 * <p>{@code demand} is a domain quantity used by the capacity/split fixtures. It is deliberately
 * <em>mutable</em>: {@code AbstractSplitAlgorithm}/{@code AbstractBigNodeSplitAlgorithm} hand a subclass the
 * freshly copied node and expect it to be carved down in place, which is impossible for a fully immutable
 * node. Real domain entities (JPA and friends) are mutable in the same way.
 *
 * <p>{@link #withIdentityEquality()} produces a node without value equality (only the very same instance is equal),
 * modelling consumers whose {@code copy()} is not {@code equals} to its source; {@link #withCodeEquality()} one that is
 * equal to every node with the same code, as {@code Nodeable}'s identity-by-code describes. Copies keep the contract.
 */
public final class TestNode implements Nodeable<TestNode> {

    private final String code;
    private final Type type;
    private final String zone;
    @Getter
    private final int x;
    @Getter
    private final int y;
    @Getter
    @Setter
    private int demand;
    private boolean identityEquality;
    private boolean codeEquality;

    public TestNode(String code, Type type, String zone, int x, int y) {
        this(code, type, zone, x, y, 0);
    }

    public TestNode(String code, Type type, String zone, int x, int y, int demand) {
        this.code = code;
        this.type = type;
        this.zone = zone;
        this.x = x;
        this.y = y;
        this.demand = demand;
    }

    public static TestNode customer(String code, int x, int y) {
        return new TestNode(code, Type.CUSTOMER, "NoZone", x, y);
    }

    public static TestNode customer(String code, int x, int y, int demand) {
        return new TestNode(code, Type.CUSTOMER, "NoZone", x, y, demand);
    }

    public static TestNode customer(String code, String zone, int x, int y) {
        return new TestNode(code, Type.CUSTOMER, zone, x, y);
    }

    public static TestNode customer(String code, String zone, int x, int y, int demand) {
        return new TestNode(code, Type.CUSTOMER, zone, x, y, demand);
    }

    public static TestNode origin(String code, int x, int y) {
        return new TestNode(code, Type.ORIGIN, "NoZone", x, y);
    }

    public static TestNode destination(String code, int x, int y) {
        return new TestNode(code, Type.DESTINATION, "NoZone", x, y);
    }

    /**
     * Manhattan distance — an integer metric (satisfies the triangle inequality), ideal for routing tests.
     */
    public int manhattanTo(TestNode other) {
        return Math.abs(x - other.x) + Math.abs(y - other.y);
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public Type getType() {
        return type;
    }

    @Override
    public String getZone() {
        return zone;
    }

    @Override
    public TestNode copy() {
        TestNode copy = new TestNode(code, type, zone, x, y, demand);
        copy.identityEquality = identityEquality;
        copy.codeEquality = codeEquality;
        return copy;
    }

    /**
     * A copy of this node that is equal only to itself (and whose copies are equal only to themselves).
     */
    public TestNode withIdentityEquality() {
        TestNode copy = copy();
        copy.identityEquality = true;
        return copy;
    }

    /**
     * A copy of this node that is equal to every code-equality node with the same code, whatever its other fields.
     */
    public TestNode withCodeEquality() {
        TestNode copy = copy();
        copy.codeEquality = true;
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TestNode n)) {
            return false;
        }
        if (identityEquality || n.identityEquality) {
            return false;
        }
        if (codeEquality || n.codeEquality) {
            return codeEquality && n.codeEquality && Objects.equals(code, n.code);
        }
        return x == n.x && y == n.y && demand == n.demand && type == n.type
                && Objects.equals(code, n.code) && Objects.equals(zone, n.zone);
    }

    @Override
    public int hashCode() {
        if (identityEquality) {
            return System.identityHashCode(this);
        }
        if (codeEquality) {
            return Objects.hashCode(code);
        }
        return Objects.hash(code, type.name(), zone, x, y, demand);   // enum hashes are identity-based and vary per run
    }

    @Override
    public String toString() {
        return code + "(" + x + "," + y + ")";
    }

}
