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

package com.github.liliumsoftware.optimization.insertion;

import com.github.liliumsoftware.optimization.support.AlwaysFeasibleChecker;
import com.github.liliumsoftware.optimization.support.MatrixCostCalculation;
import com.github.liliumsoftware.optimization.support.TestInsertion;
import com.github.liliumsoftware.optimization.support.TestNode;
import com.github.liliumsoftware.optimization.support.TestPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that {@link AbstractInsertionAlgorithm} replaces the chosen plan by identity: two plans that compare equal (here
 * by code) are still told apart, so the enlarged copy replaces the plan it was built from and the other one survives.
 *
 * <p>Both open plans are coded {@code "same"}: the first serves {@code F(0,-100)}, the second {@code N(0,10)}. The new
 * customer {@code X(0,20)} costs 10 behind N but at least 40 in the first plan, so it goes into the <em>second</em>
 * element; {@code indexOf} found the first one and replaced it, losing F.
 */
class InsertionPlanIdentityTest {

    private final TestNode origin = TestNode.origin("O", 0, 0);
    private final TestNode far = TestNode.customer("F", 0, -100, 1);
    private final TestNode near = TestNode.customer("N", 0, 10, 1);
    private final TestNode added = TestNode.customer("X", 0, 20, 1);

    private TestInsertion insertion(boolean regret) {
        MatrixCostCalculation calculation = new MatrixCostCalculation();
        calculation.initializeCostMatrix(List.of(origin, far, near, added));
        return new TestInsertion(calculation, new AlwaysFeasibleChecker(), regret);
    }

    private List<TestPlan> plans() {
        TestPlan farPlan = new TestPlan("same", List.of(origin, far));
        TestPlan nearPlan = new TestPlan("same", List.of(origin, near));
        farPlan.setEquality(TestPlan.Equality.CODE);
        nearPlan.setEquality(TestPlan.Equality.CODE);
        assertThat(farPlan).isEqualTo(nearPlan);
        return new ArrayList<>(List.of(farPlan, nearPlan));
    }

    @Test
    void aSingleInsertionReplacesThePlanItWasBuiltFrom() {
        List<TestPlan> plans = plans();
        TestPlan farPlan = plans.getFirst();

        assertThat(insertion(false).insert(plans, added)).isTrue();

        assertThat(plans.get(0)).isSameAs(farPlan);
        assertThat(plans.get(1).getCustomers()).containsExactly(near, added);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aBatchInsertionReplacesThePlanItWasBuiltFrom(boolean regret) {
        List<TestPlan> plans = plans();
        TestPlan farPlan = plans.getFirst();

        assertThat(insertion(regret).insert(plans, List.of(added))).containsExactly(added);

        assertThat(plans.get(0)).isSameAs(farPlan);
        assertThat(plans.get(1).getCustomers()).containsExactly(near, added);
    }

}
