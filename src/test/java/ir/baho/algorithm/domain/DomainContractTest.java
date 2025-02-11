package ir.baho.algorithm.domain;

import ir.baho.algorithm.support.TestInvalidNode;
import ir.baho.algorithm.support.TestNode;
import ir.baho.algorithm.support.TestPlan;
import ir.baho.algorithm.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code domain} interfaces are the contract a host application implements, and their {@code default}
 * methods are shipped behaviour the algorithms depend on: node classification ({@code getOrigin},
 * {@code getCustomers}, …), the multi-unit resource expansion inputs ({@code getCount}), and the packing
 * geometry helpers. A host app overriding none of them still gets these semantics, so they are worth pinning
 * against a deliberately minimal implementation rather than against the richer test doubles.
 */
class DomainContractTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode customerA = TestNode.customer("A", 0, 10, 1);
    private final TestNode customerB = TestNode.customer("B", 0, 20, 2);
    private final TestNode destination = TestNode.destination("D", 0, 0);

    @Test
    void planClassifiesItsNodesByType() {
        MinimalPlan plan = new MinimalPlan("p",
                List.of(origin, customerA, customerB, destination), new TestTrackable("T1", 5));

        assertThat(plan.getCount()).isEqualTo(4);
        assertThat(plan.getCustomerCount()).isEqualTo(2);
        assertThat(plan.getCustomers()).containsExactly(customerA, customerB);
        assertThat(plan.getOrigin()).isEqualTo(origin);
        assertThat(plan.getDestination()).isEqualTo(destination);
    }

    @Test
    void anOpenPlanHasNoDestination() {
        MinimalPlan plan = new MinimalPlan("p", List.of(origin, customerA), new TestTrackable("T1", 5));

        assertThat(plan.getDestination()).isNull();
        assertThat(plan.getOrigin()).isEqualTo(origin);
        assertThat(plan.getCustomers()).containsExactly(customerA);
    }

    @Test
    void anEmptyPlanReportsNothing() {
        MinimalPlan plan = new MinimalPlan("p", List.of(), new TestTrackable("T1", 5));

        assertThat(plan.getCount()).isZero();
        assertThat(plan.getCustomerCount()).isZero();
        assertThat(plan.getCustomers()).isEmpty();
        assertThat(plan.getOrigin()).isNull();
        assertThat(plan.getDestination()).isNull();
    }

    // ---------------------------------------------------------------- Planable defaults

    @Test
    void zoneAndSecondDispatchDefaultToTheSharedNoZoneAndFalse() {
        MinimalPlan plan = new MinimalPlan("p", List.of(origin, customerA), new TestTrackable("T1", 5));

        assertThat(plan.getZone()).isEqualTo("NoZone");
        assertThat(plan.isSecondDispatch()).isFalse();
        assertThat(new MinimalNode("n", Nodeable.Type.CUSTOMER).getZone()).isEqualTo("NoZone");
    }

    @Test
    void closednessComesFromTheAssignedResource() {
        assertThat(new MinimalPlan("closed", List.of(origin, customerA),
                new TestTrackable("T", 5, 1, true)).isClosed()).isTrue();
        assertThat(new MinimalPlan("open", List.of(origin, customerA),
                new TestTrackable("T", 5, 1, false)).isClosed()).isFalse();

        // Documented consequence: closedness is a property of the resource, so it cannot be asked of a plan
        // that has none — callers must assign before querying.
        MinimalPlan unassigned = new MinimalPlan("unassigned", List.of(origin, customerA), null);
        assertThatThrownBy(unassigned::isClosed).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aNodeCopyEqualsItsSourceAndIsIndependent() {
        TestNode copy = customerA.copy();

        assertThat(copy).isEqualTo(customerA).hasSameHashCodeAs(customerA).isNotSameAs(customerA);
        copy.setDemand(99);
        assertThat(customerA.getDemand()).as("mutating a copy must not touch the source").isEqualTo(1);
    }

    @Test
    void aPlanCopyIsADeepIndependentCopy() {
        TestPlan plan = new TestPlan("p", List.of(origin, customerA));
        plan.setTrackable(new TestTrackable("T1", 5));
        TestPlan copy = plan.copy();

        assertThat(copy).isEqualTo(plan).isNotSameAs(plan);
        assertThat(copy.getNodes()).isNotSameAs(plan.getNodes());
        copy.getNodes().add(customerB);
        assertThat(plan.getNodes()).as("the source node list is untouched").containsExactly(origin, customerA);
        assertThat(copy).as("a mutated copy no longer equals its source").isNotEqualTo(plan);
    }

    @Test
    void anInvalidNodeIsACustomerCarryingItsCause() {
        TestInvalidNode invalid = new TestInvalidNode("X", "Z1", "demand.must.be.positive");

        assertThat(invalid.getType()).isEqualTo(Nodeable.Type.CUSTOMER);
        assertThat(invalid.getCause()).isEqualTo("demand.must.be.positive");
        assertThat(invalid.copy()).isEqualTo(invalid);
    }

    // ---------------------------------------------------------------- Nodeable / InvalidNodeable

    @Test
    void aSingleUnitResourceHasTheExpectedDefaults() {
        MinimalTrackable trackable = new MinimalTrackable("T");

        assertThat(trackable.getIndex()).as("the index of the only unit a count-1 resource expands to").isZero();
        assertThat(trackable.getCount()).isEqualTo(1);
        assertThat(trackable.isReturns()).isFalse();
        assertThat(trackable.isSupportSecondDispatch()).isFalse();
    }

    @Test
    void settingAnIndexIsANoOpForASingletonResource() {
        MinimalTrackable trackable = new MinimalTrackable("T");

        trackable.setIndex(7);

        assertThat(trackable.getIndex()).as("a singleton has no unit to address").isZero();
    }

    @Test
    void secondDispatchSupportFollowsReturningByDefault() {
        assertThat(new TestTrackable("returning", 5, 1, true).isSupportSecondDispatch()).isTrue();
        assertThat(new TestTrackable("one-way", 5, 1, false).isSupportSecondDispatch()).isFalse();
    }

    // ---------------------------------------------------------------- Trackable defaults

    @Test
    void binGeometryHelpersUseLongArithmetic() {
        // 100000 × 100000 = 10^10 overflows an int; the defaults must widen before multiplying.
        TestBin large = new TestBin("large", 100_000, 100_000, 100_000);

        assertThat(large.getArea()).isEqualTo(10_000_000_000L);
        assertThat(large.getVolume()).isEqualTo(1_000_000_000_000_000L);

        TestBin small = new TestBin("small", 2, 3, 4);
        assertThat(small.getArea()).isEqualTo(6);
        assertThat(small.getVolume()).isEqualTo(24);
    }

    @Test
    void aBinCopyEqualsItsSourceAndKeepsItsIndex() {
        TestBin bin = new TestBin("b", 1, 2, 3);
        bin.setIndex(4);

        TestBin copy = bin.copy();

        assertThat(copy).isEqualTo(bin).isNotSameAs(bin);
        assertThat(copy.getIndex()).isEqualTo(4);
    }

    @Test
    void aContainerReportsItsDimensionsAndCopiesByValue() {
        TestContainer container = new TestContainer(10, 20, 30);

        assertThat(container.getLength()).isEqualTo(10);
        assertThat(container.getWidth()).isEqualTo(20);
        assertThat(container.getHeight()).isEqualTo(30);
        assertThat(container.copy()).isEqualTo(container);
    }

    // ---------------------------------------------------------------- Bin / Container defaults

    @Test
    void aBinThatWasNeverIndexedReportsTheIndexOfTheFirstPackedCopy() {
        // BinPacking#getCount numbers its copies 0 .. k-1, as the assignment layer numbers trackable units; the Bin
        // default used to be 1, matching no packed copy.
        record UnitBox(int side) implements Bin<UnitBox> {
            @Override
            public int getLength() {
                return side;
            }

            @Override
            public int getWidth() {
                return side;
            }

            @Override
            public int getHeight() {
                return side;
            }

            @Override
            public UnitBox copy() {
                return new UnitBox(side);
            }
        }

        UnitBox bin = new UnitBox(1);

        assertThat(bin.getIndex()).isZero();
    }

    @Test
    void planModifiersAreAppliedToEveryPlanAndTolerateANullList() {
        List<String> touched = new ArrayList<>();
        PlanModifier<TestPlan> modifier = plan -> touched.add(plan.getCode());

        modifier.modify(List.of(new TestPlan("p1", List.of(origin, customerA)),
                new TestPlan("p2", List.of(origin, customerB))));
        assertThat(touched).containsExactly("p1", "p2");

        touched.clear();
        modifier.modify((List<TestPlan>) null);
        assertThat(touched).as("a null plan list is tolerated so callers need not guard it").isEmpty();

        modifier.modify(List.of());
        assertThat(touched).isEmpty();
    }

    /**
     * Bare-bones {@link Planable}: implements only the four abstract methods so every default is exercised.
     */
    private record MinimalPlan(String code, List<TestNode> nodes,
                               TestTrackable trackable) implements Planable<MinimalPlan, TestNode, TestTrackable> {

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
            return 0;
        }

        @Override
        public MinimalPlan copy() {
            return new MinimalPlan(code, new ArrayList<>(nodes), trackable);
        }
    }

    /**
     * Bare-bones {@link Trackable}: only {@code getCode}/{@code copy}, so the defaults show through.
     */
    private record MinimalTrackable(String code) implements Trackable<MinimalTrackable> {

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public MinimalTrackable copy() {
            return new MinimalTrackable(code);
        }
    }

    // ---------------------------------------------------------------- PlanModifier

    /**
     * Bare-bones {@link Nodeable}.
     */
    private record MinimalNode(String code, Type type) implements Nodeable<MinimalNode> {

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public Type getType() {
            return type;
        }

        @Override
        public MinimalNode copy() {
            return new MinimalNode(code, type);
        }
    }

}
