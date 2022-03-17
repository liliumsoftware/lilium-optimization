package ir.baho.algorithm.clarkewright;

import ir.baho.algorithm.domain.Planable;

import java.util.List;

/**
 * A candidate plan set together with the {@link LambdaBeta} parameters that produced it during the sweep.
 */
public record PlanList<P extends Planable<P, ?, ?>>(List<P> plans, LambdaBeta lambdaBeta) {

}
