package com.github.liliumsoftware.optimization.clarkewright;

import com.github.liliumsoftware.optimization.domain.Planable;

import java.util.ArrayList;
import java.util.List;

/**
 * Produces a fresh plan list in which {@code firstPlan} and {@code secondPlan} are replaced by their
 * already-computed {@code mergeResult}, working on deep copies so the input list is left untouched. The
 * merged plan is inserted at the position of whichever input already had a trackable assigned (preserving
 * ordering that assignment depends on), or appended when neither did. The replaced copies are removed by
 * identity, so the result is kept even when it {@code equals} one of the source plans (for example by code).
 */
public record PlanMerger<P extends Planable<P, ?, ?>>(List<P> plans, P firstPlan, P secondPlan, P mergeResult) {

    /**
     * Performs the merge.
     *
     * @return a new list of copies with the two source plans replaced by {@code mergeResult}
     */
    public List<P> merge() {
        List<P> tempPlans = new ArrayList<>();
        P tempFirst = null;
        P tempSecond = null;
        for (P plan : plans) {
            P tempPlan = plan.copy();
            tempPlans.add(tempPlan);
            if (plan.equals(firstPlan)) {
                tempFirst = tempPlan;
            } else if (plan.equals(secondPlan)) {
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
        } else {
            if (firstPlan.getTrackable() != null) {
                return plans.indexOf(firstPlan);
            } else {
                return plans.indexOf(secondPlan);
            }
        }
    }

}
