package ir.baho.algorithm.support;

import ir.baho.algorithm.domain.Planable;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Concrete {@link Planable} for tests. Holds a mutable node list (moves mutate copies in place, as the real
 * algorithms require). Equality is a deep, content-based comparison so a {@link #copy()} equals its source but
 * a mutated copy does not — exactly what the library's {@code indexOf}/fixed-point convergence checks rely on.
 *
 * <p><strong>{@link #getCost()} is live</strong>: it is derived from the current node list every time it is
 * read, as {@code Planable} specifies ("total cost of traversing getNodes() in order"). This matters because
 * {@code ExchangeRelocateType.SIMPLE} splices nodes and then immediately compares {@code getCost()} without
 * recosting; an implementation that cached a stale value would make every SIMPLE move look like a
 * no-improvement and silently disable that whole strategy.
 *
 * <p>{@code costMultiplier} scales that live cost, which is how the resource-dependent cost model in
 * {@link SpeedAwareAssignment} expresses "this vehicle is slower" without breaking liveness.
 *
 * <p>{@link Equality} switches the equality contract for tests that model consumers whose plans do not compare by
 * value: {@link Equality#CODE} (identity is the plan code, as {@code Planable#getCode()} describes) and
 * {@link Equality#IDENTITY} (no {@code equals} override at all, e.g. entities with framework-managed identity). A
 * copy keeps the mode of its source.
 */
public final class TestPlan implements Planable<TestPlan, TestNode, TestTrackable> {

    private final String code;
    private final List<TestNode> nodes;
    private final String zone;
    @Setter
    private TestTrackable trackable;
    @Setter
    private long costMultiplier = 1;
    @Setter
    private boolean secondDispatch;
    @Setter
    private Equality equality = Equality.VALUE;

    public TestPlan(String code, List<TestNode> nodes) {
        this(code, nodes, "NoZone");
    }

    public TestPlan(String code, List<TestNode> nodes, String zone) {
        this.code = code;
        this.nodes = new ArrayList<>(nodes);
        this.zone = zone;
    }

    /**
     * A sibling plan with the same identity/resource but a different node sequence — what the local-search and
     * un-merge hooks need when they hand back a re-ordered plan. Copies the nodes so the two plans never share
     * a list.
     */
    public TestPlan withNodes(List<TestNode> newNodes) {
        TestPlan plan = new TestPlan(code, newNodes, zone);
        plan.trackable = trackable == null ? null : trackable.copy();
        plan.costMultiplier = costMultiplier;
        plan.secondDispatch = secondDispatch;
        plan.equality = equality;
        return plan;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public List<TestNode> getNodes() {
        return nodes;
    }

    @Override
    public TestTrackable getTrackable() {
        return trackable;
    }

    @Override
    public long getCost() {
        return PlanInvariants.referenceCost(nodes) * costMultiplier;
    }

    @Override
    public String getZone() {
        return zone;
    }

    @Override
    public boolean isSecondDispatch() {
        return secondDispatch;
    }

    @Override
    public TestPlan copy() {
        List<TestNode> copiedNodes = new ArrayList<>(nodes.size());
        for (TestNode node : nodes) {
            copiedNodes.add(node.copy());
        }
        TestPlan copy = new TestPlan(code, copiedNodes, zone);
        copy.trackable = trackable == null ? null : trackable.copy();
        copy.costMultiplier = costMultiplier;
        copy.secondDispatch = secondDispatch;
        copy.equality = equality;
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TestPlan)) {
            return false;
        }
        TestPlan p = (TestPlan) o;
        // symmetric whatever the two modes are: plans in different modes are never equal
        if (equality == Equality.IDENTITY || equality != p.equality) {
            return false;
        }
        if (equality == Equality.CODE) {
            return Objects.equals(code, p.code);
        }
        return Objects.equals(code, p.code) && Objects.equals(zone, p.zone)
                && Objects.equals(nodes, p.nodes) && Objects.equals(trackable, p.trackable);
    }

    @Override
    public int hashCode() {
        if (equality == Equality.IDENTITY) {
            return System.identityHashCode(this);
        }
        if (equality == Equality.CODE) {
            return Objects.hashCode(code);
        }
        return Objects.hash(code, zone, nodes, trackable);
    }

    @Override
    public String toString() {
        return code + nodes;
    }

    /**
     * How two plans are compared; {@link #VALUE} unless a test opts into another contract.
     */
    public enum Equality {
        VALUE, CODE, IDENTITY
    }

}
