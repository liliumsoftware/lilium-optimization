package ir.baho.algorithm.feasibility;

import ir.baho.algorithm.exception.ObjectError;
import ir.baho.algorithm.support.AlwaysFeasibleChecker;
import ir.baho.algorithm.support.DemandFeasibilityChecker;
import ir.baho.algorithm.support.MaxCustomersFeasibilityChecker;
import ir.baho.algorithm.support.TestNode;
import ir.baho.algorithm.support.TestPlan;
import ir.baho.algorithm.support.TestTrackable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feasibility is evaluated for every candidate move, so its boundary behaviour decides which solutions the
 * search can even reach: "load equal to capacity" must be accepted and "one over" rejected, or the algorithms
 * quietly lose (or illegally use) one unit of capacity everywhere.
 */
class FeasibilityCheckerTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);

    private TestPlan plan(String code, TestNode... customers) {
        java.util.List<TestNode> nodes = new java.util.ArrayList<>();
        nodes.add(origin);
        nodes.addAll(List.of(customers));
        TestPlan plan = new TestPlan(code, nodes);
        plan.setTrackable(new TestTrackable(code + "-t", 99));
        return plan;
    }

    @Test
    void theSharedFeasibleResultReportsNoCause() {
        assertThat(FeasibilityResult.FEASIBLE.isFeasible()).isTrue();
        assertThat(FeasibilityResult.FEASIBLE.getCause()).isNull();
        assertThat(FeasibilityResult.FEASIBLE.getParams()).isEmpty();
    }

    @Test
    void aCustomResultIsInfeasibleUnlessItSaysOtherwise() {
        // The interface default is "not feasible", so a checker only has to describe the failure.
        FeasibilityResult custom = new FeasibilityResult() {
            @Override
            public String getCause() {
                return "some.cause";
            }

            @Override
            public Object[] getParams() {
                return new Object[] {1};
            }
        };

        assertThat(custom.isFeasible()).isFalse();
    }

    @Test
    void demandExactlyAtCapacityIsFeasibleAndOneMoreIsNot() {
        DemandFeasibilityChecker checker = new DemandFeasibilityChecker(10);

        TestPlan atCapacity = plan("at", TestNode.customer("A", 0, 10, 4), TestNode.customer("B", 0, 20, 6));
        TestPlan overCapacity = plan("over", TestNode.customer("A", 0, 10, 4), TestNode.customer("B", 0, 20, 7));

        assertThat(checker.getResult(atCapacity).isFeasible()).isTrue();
        assertThat(checker.getResult(overCapacity).isFeasible()).isFalse();
        assertThat(checker.getResult(overCapacity).getCause()).isEqualTo("capacity.exceeded");
        assertThat(checker.getResult(overCapacity).getParams()).containsExactly(11L, 10L);
    }

    @Test
    void anEmptyOrDepotOnlyPlanIsFeasible() {
        DemandFeasibilityChecker checker = new DemandFeasibilityChecker(0);

        assertThat(checker.getResult(plan("depot-only")).isFeasible()).isTrue();
        assertThat(checker.getResult(new TestPlan("empty", List.of())).isFeasible()).isTrue();
    }

    @Test
    void customerCountLimitsAreAppliedAtTheBoundary() {
        MaxCustomersFeasibilityChecker checker = new MaxCustomersFeasibilityChecker(2);

        assertThat(checker.getResult(plan("two",
                TestNode.customer("A", 0, 10, 1), TestNode.customer("B", 0, 20, 1))).isFeasible()).isTrue();
        assertThat(checker.getResult(plan("three",
                TestNode.customer("A", 0, 10, 1), TestNode.customer("B", 0, 20, 1),
                TestNode.customer("C", 0, 30, 1))).isFeasible()).isFalse();
    }

    @Test
    void aZeroLimitRejectsEveryPlanThatServesAnyone() {
        MaxCustomersFeasibilityChecker checker = new MaxCustomersFeasibilityChecker(0);

        assertThat(checker.getResult(plan("empty")).isFeasible()).isTrue();
        assertThat(checker.getResult(plan("one", TestNode.customer("A", 0, 10, 1))).isFeasible()).isFalse();
    }

    @Test
    void errorsAreReportedPerInfeasiblePlanAndBoundToItsResource() {
        DemandFeasibilityChecker checker = new DemandFeasibilityChecker(5);
        TestPlan feasible = plan("ok", TestNode.customer("A", 0, 10, 2));
        TestPlan infeasible = plan("bad", TestNode.customer("B", 0, 20, 9));

        List<ObjectError> errors = checker.getErrors(List.of(feasible, infeasible));

        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().objectName()).isEqualTo("bad-t");
        assertThat(errors.getFirst().defaultMessage()).isEqualTo("capacity.exceeded");
    }

    @Test
    void aFullyFeasiblePlanSetReportsNoErrors() {
        assertThat(new AlwaysFeasibleChecker().getErrors(List.of(
                plan("p1", TestNode.customer("A", 0, 10, 1)),
                plan("p2", TestNode.customer("B", 0, 20, 1))))).isEmpty();
        assertThat(new AlwaysFeasibleChecker().getErrors(List.of())).isEmpty();
    }

    @Test
    void errorsForAPlanWithoutAResourceAreBoundToThePlanCode() {
        // Regression: getErrors dereferenced the trackable unconditionally and threw NullPointerException for a
        // plan that had not been assigned yet.
        DemandFeasibilityChecker checker = new DemandFeasibilityChecker(5);
        TestPlan unassigned = plan("loose", TestNode.customer("B", 0, 20, 9));
        unassigned.setTrackable(null);

        List<ObjectError> errors = checker.getErrors(List.of(unassigned));

        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().objectName()).isEqualTo("loose");
    }

}
