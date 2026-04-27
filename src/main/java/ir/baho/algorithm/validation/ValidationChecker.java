package ir.baho.algorithm.validation;

import ir.baho.algorithm.domain.InvalidNodeable;
import ir.baho.algorithm.domain.Nodeable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Partitions raw nodes into valid ({@code getValidNodes()}) and invalid ({@code getInvalidNodes()}) sets
 * <em>before</em> planning begins, so structurally unplannable customers are filtered out up front rather
 * than repeatedly rejected inside the hot search loops. A node accumulating no {@link ValidationResult}s is
 * valid; otherwise it is converted to an {@link InvalidNodeable} carrying its causes.
 *
 * <p>Stateful by design: each {@link #validate(List)} replaces the partition returned by the getters. The method
 * is synchronized, the partition is built completely before it is published through volatile fields, and the
 * published lists are unmodifiable, so a shared instance never exposes a half-built or externally mutated
 * partition. Callers that need several partitions at once should still use one instance per run.
 *
 * @param <N> the input node type
 * @param <I> the invalid-node type produced for rejected nodes
 */
@RequiredArgsConstructor
@Getter
public abstract class ValidationChecker<N extends Nodeable<N>, I extends InvalidNodeable<I>> {

    private volatile List<N> validNodes;
    private volatile List<I> invalidNodes;

    /**
     * Runs {@link #validate(Nodeable, Consumer)} over every node, populating the valid/invalid partitions.
     */
    public synchronized void validate(List<N> nodes) {
        List<N> valid = new ArrayList<>();
        List<I> invalid = new ArrayList<>();
        for (N node : nodes) {
            List<ValidationResult> results = new ArrayList<>();
            validate(node, results::add);
            if (results.isEmpty()) {
                valid.add(node);
            } else {
                invalid.add(toInvalidNode(node, results));
            }
        }
        validNodes = Collections.unmodifiableList(valid);
        invalidNodes = Collections.unmodifiableList(invalid);
    }

    /**
     * Checks one node, handing every violated rule to {@code consumer}; no call means the node is valid.
     */
    protected abstract void validate(N node, Consumer<ValidationResult> consumer);

    /**
     * Resolves one result to its user-facing message.
     */
    protected String getCause(ValidationResult result) {
        return getMessage(result.getCause(), result.getParams());
    }

    /**
     * Converts a rejected node and its violations into the invalid-node type.
     */
    protected abstract I toInvalidNode(N node, List<ValidationResult> results);

    /**
     * Resolves a message key and parameters to text.
     */
    protected abstract String getMessage(String key, Object... params);

}
