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

package com.github.liliumsoftware.optimization.validation;

import com.github.liliumsoftware.optimization.domain.InvalidNodeable;
import com.github.liliumsoftware.optimization.domain.Nodeable;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Partitions raw nodes into valid ({@link #getValidNodes()}) and invalid ({@link #getInvalidNodes()}) sets
 * <em>before</em> planning begins, so structurally unplannable customers are filtered out up front rather
 * than repeatedly rejected inside the hot search loops. A node accumulating no {@link ValidationResult}s is
 * valid; otherwise it is converted to an {@link InvalidNodeable} carrying its causes.
 *
 * <p>Stateful by design: each {@link #validate(List)} replaces the partition returned by the getters. The method
 * is synchronized, the partition is built completely before it is published, and both lists are published together
 * through a single volatile reference, so a reader on another thread sees the valid and invalid nodes of the same
 * run, never the valid nodes of one run with the invalid nodes of another. The published lists are unmodifiable.
 * Callers that need several partitions at once should still use one instance per run.
 *
 * @param <N> the input node type
 * @param <I> the invalid-node type produced for rejected nodes
 */
@RequiredArgsConstructor
public abstract class ValidationChecker<N extends Nodeable<N>, I extends InvalidNodeable<I>> {

    private volatile Partition<N, I> partition;

    /**
     * Runs {@link #validate(Nodeable, Consumer)} over every node, in order, converts each rejected node with
     * {@link #toInvalidNode}, and publishes the resulting valid/invalid partition, replacing the previous one. The
     * hooks run while this instance's lock is held. If a hook throws, the previous partition stays published.
     *
     * @param nodes the nodes to check; not modified
     * @throws NullPointerException if {@code nodes} is {@code null}
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
        partition = new Partition<>(Collections.unmodifiableList(valid), Collections.unmodifiableList(invalid));
    }

    /**
     * Checks one node, handing every violated rule to {@code consumer}; no call means the node is valid. Called once
     * per node by {@link #validate(List)}.
     *
     * @param node     the node to check
     * @param consumer receives one {@link ValidationResult} per violated rule
     */
    protected abstract void validate(N node, Consumer<ValidationResult> consumer);

    /**
     * The nodes the last {@link #validate(List)} accepted, in input order.
     *
     * @return an unmodifiable list, or {@code null} before the first {@link #validate(List)}
     */
    public List<N> getValidNodes() {
        Partition<N, I> current = partition;
        return current == null ? null : current.valid();
    }

    /**
     * The invalid-node conversions of the nodes the last {@link #validate(List)} rejected, in input order.
     *
     * @return an unmodifiable list, or {@code null} before the first {@link #validate(List)}
     */
    public List<I> getInvalidNodes() {
        Partition<N, I> current = partition;
        return current == null ? null : current.invalid();
    }

    /**
     * Resolves one result to its user-facing message through {@link #getMessage(String, Object...)}. Not called by
     * this class; a convenience for {@link #toInvalidNode} implementations.
     *
     * @param result a violated rule
     * @return the message for {@code result}
     */
    protected String getCause(ValidationResult result) {
        return getMessage(result.getCause(), result.getParams());
    }

    /**
     * Converts a rejected node and its violations into the invalid-node type.
     *
     * @param node    the rejected node
     * @param results every rule it violated, in the order {@link #validate(Nodeable, Consumer)} reported them
     * @return the invalid-node conversion of {@code node}
     */
    protected abstract I toInvalidNode(N node, List<ValidationResult> results);

    /**
     * Resolves a message key and parameters to text (for example through a message bundle).
     *
     * @param key    the message key
     * @param params the message parameters
     * @return the resolved message
     */
    protected abstract String getMessage(String key, Object... params);

    /**
     * One complete, immutable validation result.
     */
    private record Partition<N, I>(List<N> valid, List<I> invalid) {
    }

}
