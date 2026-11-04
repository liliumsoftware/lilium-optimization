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

package com.github.liliumsoftware.optimization.clarkewright;

import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.domain.TrackableCategory;
import com.github.liliumsoftware.optimization.support.CapacityRules;
import com.github.liliumsoftware.optimization.support.TestPlan;
import com.github.liliumsoftware.optimization.support.TestTrackable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * A {@link TrackableAssignment} for the Clarke-Wright tests whose fit rule and current pool are chosen per test: a unit
 * fits a plan when its capacity covers the plan's customer count and the extra rule accepts the pair; the current pool
 * may be narrowed below the expanded fleet, as a second dispatch would. Units are offered smallest capacity first.
 */
final class RuleAssignment extends TrackableAssignment<TestPlan, TestTrackable> {

    private final BiPredicate<TestPlan, TestTrackable> rule;

    RuleAssignment(List<TestTrackable> fleet, BiPredicate<TestPlan, TestTrackable> rule) {
        super(fleet);
        this.rule = rule;
    }

    /**
     * Cold chain: only the units whose code is listed in {@code refrigerated} may serve a plan holding one of the
     * {@code cold} customers.
     */
    static RuleAssignment coldChain(List<TestTrackable> fleet, Set<String> refrigerated, Set<String> cold) {
        return new RuleAssignment(fleet, (plan, unit) -> refrigerated.contains(unit.getCode())
                || plan.getCustomers().stream().noneMatch(c -> cold.contains(c.getCode())));
    }

    /**
     * Narrows the current pool to {@code pool} (units of the expanded fleet), as a second dispatch would.
     */
    RuleAssignment withPool(List<TestTrackable> pool) {
        this.currentTrackables = new ArrayList<>(pool);
        return this;
    }

    private boolean fits(TestPlan plan, TestTrackable trackable) {
        return CapacityRules.fits(plan, trackable) && rule.test(plan, trackable);
    }

    @Override
    protected boolean assign(TestPlan plan, TestTrackable trackable) {
        if (fits(plan, trackable)) {
            plan.setTrackable(trackable);
            return true;
        }
        plan.setTrackable(null);
        return false;
    }

    @Override
    protected boolean assign(TestPlan plan, List<TestTrackable> trackables) {
        for (TestTrackable trackable : trackables) {
            if (fits(plan, trackable)) {
                plan.setTrackable(trackable);
                return true;
            }
        }
        plan.setTrackable(null);
        return false;
    }

    @Override
    protected Comparator<TestTrackable> comparator(TestPlan plan) {
        return CapacityRules.comparator();
    }

    @Override
    protected TrackableCategory getCategory(TestTrackable trackable) {
        return CapacityRules.category(trackable);
    }

}
