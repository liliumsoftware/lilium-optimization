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

import com.github.liliumsoftware.optimization.domain.Planable;

import java.util.ArrayList;
import java.util.List;

/**
 * Produces a fresh plan list in which {@code firstPlan} and {@code secondPlan} are replaced by their
 * already-computed {@code mergeResult}. Every plan of {@code plans} is copied with {@link Planable#copy()}, so neither
 * the input list nor its plans are modified; {@code mergeResult} itself is inserted as is, not copied. The merged plan
 * takes the position of {@code firstPlan} when that one holds a resource, otherwise of {@code secondPlan} when it does
 * (preserving the order resource assignment depends on), and is appended when neither does. The source plans are
 * located, and their copies removed, by identity, so plan {@code equals} plays no part: the result is kept even when it
 * {@code equals} one of the source plans (for example by code).
 *
 * @param plans       the current plan list; contains {@code firstPlan} and {@code secondPlan}
 * @param firstPlan   the plan whose route comes first in the merged route; an element of {@code plans}
 * @param secondPlan  the plan whose route comes second; another element of {@code plans}
 * @param mergeResult the merged plan replacing both
 * @param <P>         the plan type
 */
public record PlanMerger<P extends Planable<P, ?, ?>>(List<P> plans, P firstPlan, P secondPlan, P mergeResult) {

    private static <P> int indexOfInstance(List<P> plans, P plan) {
        for (int i = 0; i < plans.size(); i++) {
            if (plans.get(i) == plan) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Performs the merge.
     *
     * @return a new, modifiable list of copies with the two source plans replaced by {@code mergeResult}
     * @throws IllegalStateException if {@code firstPlan} or {@code secondPlan} is not an element of {@code plans}
     *                               (by identity), or both are the same object
     */
    public List<P> merge() {
        List<P> tempPlans = new ArrayList<>();
        P tempFirst = null;
        P tempSecond = null;
        for (P plan : plans) {
            P tempPlan = plan.copy();
            tempPlans.add(tempPlan);
            if (plan == firstPlan) {
                tempFirst = tempPlan;
            } else if (plan == secondPlan) {
                tempSecond = tempPlan;
            }
        }
        if (tempFirst == null || tempSecond == null) {
            throw new IllegalStateException("Temp variables cannot be null");
        }
        tempPlans.add(this.findMergeIndex(plans, firstPlan, secondPlan), mergeResult);
        // Remove the two stale copies by identity: the merge result usually keeps the first plan's code, so a
        // consumer whose plans compare by code would otherwise lose the freshly inserted result instead.
        P staleFirst = tempFirst;
        P staleSecond = tempSecond;
        tempPlans.removeIf(p -> p == staleFirst || p == staleSecond);
        return tempPlans;
    }

    private int findMergeIndex(List<P> plans, P firstPlan, P secondPlan) {
        if (firstPlan.getTrackable() == null && secondPlan.getTrackable() == null) {
            return plans.size();
        }
        return indexOfInstance(plans, firstPlan.getTrackable() != null ? firstPlan : secondPlan);
    }

}
