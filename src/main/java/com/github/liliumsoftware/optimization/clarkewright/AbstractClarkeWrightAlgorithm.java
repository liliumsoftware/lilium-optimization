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

import com.github.liliumsoftware.optimization.BaseAlgorithm;
import com.github.liliumsoftware.optimization.OptimizationType;
import com.github.liliumsoftware.optimization.assignment.TrackableAssignment;
import com.github.liliumsoftware.optimization.calculation.CostCalculation;
import com.github.liliumsoftware.optimization.calculation.PathCostCalculation;
import com.github.liliumsoftware.optimization.domain.Nodeable;
import com.github.liliumsoftware.optimization.domain.Planable;
import com.github.liliumsoftware.optimization.domain.Trackable;
import com.github.liliumsoftware.optimization.localsearch.LocalSearchAlgorithm;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Clarke-Wright savings construction: the primary way this library builds an initial plan set from scratch.
 *
 * <p>Each customer starts on its own single-stop route (ORIGIN &rarr; customer, then DESTINATION when the seed plan has
 * one; a consumer's seed plans may also omit the origin). For every ordered pair of customers a <em>saving</em> is
 * computed — the cost avoided by chaining the tail of one route to the head of another instead of ending the first
 * route and starting the second from the depot. The saving uses the actual ends of the seed routes (see
 * {@link #getRouteStarts}, {@link #getRouteEnds}): a route that returns to its origin, one that ends at a separate
 * destination depot, an open route that simply stops at its last customer, and a route without an origin stop each
 * give a different value, so the orientation of a merge never depends on customer codes. Savings are processed in
 * descending order: a saving merges two routes only when the first route <em>ends</em> at its {@code origin} customer
 * and the second <em>begins</em> at its {@code destination} customer, and only when {@link TrackableAssignment} can
 * still assign a resource to every resource-holding plan plus the merged one. Accepted merges replace the two routes in
 * the plan list and every plan holding a resource is re-optimized through {@link LocalSearchAlgorithm}.</p>
 *
 * <p><strong>Non-positive savings are merged too.</strong> By default the merge walk has no threshold: <em>any</em>
 * saving whose two routes can still be assigned is merged, including zero and negative ones, so λ and β only change the
 * <em>order</em> in which merges are tried, never whether a merge is acceptable; override {@link #isMergeable(Saving)}
 * to skip savings (for example the non-positive ones). Fewer routes are preferred over cost
 * (consistent with {@link #plansComparator()}, which ranks the plan set serving the most customers first): a limited
 * fleet can then serve more customers. Only closed tours over a metric matrix with λ &le; 1 guarantee non-negative
 * savings; with open routes, routes without an origin stop, a separate destination depot, λ &gt; 1 or a non-metric
 * matrix a merge may raise the cost.</p>
 *
 * <p>The classic algorithm is generalized along two axes:</p>
 * <ul>
 *   <li><b>λ / β parameter sweep.</b> {@code lambda} scales the direct edge term in the saving formula;
 *       {@code beta} blends the two asymmetric orientations (i&rarr;j vs. j&rarr;i), weighting the better one by β;
 *       both orientations then share one value, and the one with the better plain saving is tried first. The
 *       {@code lambdas × betas} grid ({@code lambdas} alone when {@code betas} is empty) is evaluated on a
 *       {@code parallelStream}, each combination on its own deep copy of the seed plans, and {@link #getBestPlans}
 *       keeps the best resulting plan set under {@link #plansComparator()} ("most customers served, then cheapest";
 *       ties go to the combination listed first).</li>
 *   <li><b>Zone locality.</b> Under {@link OptimizationType#LOCATION_ZONE} customers are grouped by zone, each zone
 *       is swept independently to pick its own best λ/β, and only then are the per-zone savings combined into a
 *       single merge pass — savings never span zones. Any other {@link OptimizationType} treats all customers as one
 *       pool.</li>
 * </ul>
 *
 * <p>Abstract: subclasses supply the domain hooks ({@link #getInitialPlans}, {@link #getNewPlan},
 * {@link #planComparator()}) and may override the protected steps. Being a {@link BaseAlgorithm}, the merge loop
 * honours {@code cancel()} via {@link #checkCancel()}. Construction always completes: an {@code interrupt()} does not
 * stop the merge pass, because stopping it would leave unmerged plans the fleet cannot cover and their customers would
 * be dropped. Both signals are forwarded to the nested local search.</p>
 *
 * <p><strong>Thread-safety.</strong> The instance holds only immutable configuration, the two {@link BaseAlgorithm}
 * flags and a thread-local carrying the fixed previous plans of the {@link #plan} call running on each thread (handed
 * explicitly to the sweep workers), so {@link #plan} may run concurrently on independent inputs provided the
 * collaborators ({@link PathCostCalculation}, {@link TrackableAssignment}, {@link LocalSearchAlgorithm}) and the hooks
 * tolerate it; the sweep itself already calls them from parallel-stream worker threads.</p>
 *
 * @param <P> the plan/route type
 * @param <N> the node/stop type
 */
@Slf4j
public abstract class AbstractClarkeWrightAlgorithm<P
        extends Planable<P, N, ?>, N extends Nodeable<N>> extends BaseAlgorithm {

    /**
     * The cost model: pair costs for the savings, plan costs for {@link #plansComparator()}; its matrix must already
     * be initialized with every node.
     */
    protected final PathCostCalculation<P, N, ?> costCalculation;
    /**
     * Decides whether a merged plan set can still be served by the fleet and assigns the resources.
     */
    protected final TrackableAssignment<P, ?> trackableAssignment;
    /**
     * Re-optimizes every resource-holding plan after an accepted merge and every returned plan at the end of a pass.
     */
    protected final LocalSearchAlgorithm<P> localSearchAlgorithm;
    /**
     * The zone policy: {@link OptimizationType#LOCATION_ZONE} sweeps and pairs customers per zone, any other value
     * treats all customers as one pool.
     */
    protected final OptimizationType optimizationType;
    /**
     * The λ values of the sweep, in configuration order; unmodifiable, never empty, every value finite.
     */
    protected final List<Double> lambdas;
    /**
     * The β values of the sweep, in configuration order; unmodifiable, possibly empty (then only λ varies), every
     * value finite.
     */
    protected final List<Double> betas;
    /**
     * The fixed previous plans (see {@link #plan}) of the {@code plan} call running on the current thread: they take no
     * part in merging and their units are withheld from every merge attempt and from the final assignment pass.
     * Thread-local rather than an instance field so that concurrent {@code plan} calls on a shared instance cannot see
     * each other's; the λ/β sweep reads it once on the calling thread and hands it to each worker explicitly.
     */
    private final ThreadLocal<List<P>> fixedPlans = new ThreadLocal<>();

    /**
     * Zone-restricted construction ({@link OptimizationType#LOCATION_ZONE}) with the classic saving ({@code λ = 1}, no
     * β).
     *
     * @param costCalculation      the cost model; its matrix must be initialized before {@link #plan} runs
     * @param trackableAssignment  the resource assignment deciding which merges the fleet can serve
     * @param localSearchAlgorithm the local search applied to merged plans
     * @throws NullPointerException if any argument is {@code null}
     */
    public AbstractClarkeWrightAlgorithm(PathCostCalculation<P, N, ?> costCalculation,
            TrackableAssignment<P, ?> trackableAssignment,
            LocalSearchAlgorithm<P> localSearchAlgorithm) {
        this(costCalculation, trackableAssignment, localSearchAlgorithm, OptimizationType.LOCATION_ZONE);
    }

    /**
     * Construction with the classic saving ({@code λ = 1}, no β) under the given zone policy.
     *
     * @param costCalculation      the cost model; its matrix must be initialized before {@link #plan} runs
     * @param trackableAssignment  the resource assignment deciding which merges the fleet can serve
     * @param localSearchAlgorithm the local search applied to merged plans
     * @param optimizationType     the zone policy
     * @throws NullPointerException if any argument is {@code null}
     */
    public AbstractClarkeWrightAlgorithm(PathCostCalculation<P, N, ?> costCalculation,
            TrackableAssignment<P, ?> trackableAssignment,
            LocalSearchAlgorithm<P> localSearchAlgorithm,
            OptimizationType optimizationType) {
        this(costCalculation, trackableAssignment, localSearchAlgorithm, optimizationType, List.of(1D));
    }

    /**
     * Construction sweeping the given λ values without a β term.
     *
     * @param costCalculation      the cost model; its matrix must be initialized before {@link #plan} runs
     * @param trackableAssignment  the resource assignment deciding which merges the fleet can serve
     * @param localSearchAlgorithm the local search applied to merged plans
     * @param optimizationType     the zone policy
     * @param lambdas              the λ values to sweep; copied
     * @throws NullPointerException     if any argument or λ is {@code null}
     * @throws IllegalArgumentException if {@code lambdas} is empty or any λ is not a finite number
     */
    public AbstractClarkeWrightAlgorithm(PathCostCalculation<P, N, ?> costCalculation,
            TrackableAssignment<P, ?> trackableAssignment,
            LocalSearchAlgorithm<P> localSearchAlgorithm,
            OptimizationType optimizationType, List<Double> lambdas) {
        this(costCalculation, trackableAssignment, localSearchAlgorithm, optimizationType, lambdas,
                Collections.emptyList());
    }

    /**
     * Full constructor: every combination of {@code lambdas × betas} is evaluated and the best plan set kept
     * (with an empty {@code betas} only λ varies). Neither parameter is range-checked: λ may be any finite value (see
     * {@link #getSavingValue(Nodeable, Nodeable, Nodeable, Nodeable, double)}), β &le; 0 selects the plain saving and
     * β &gt; 1 extrapolates beyond the better orientation (see
     * {@link #getSavingValue(Nodeable, Nodeable, Nodeable, Nodeable, Nodeable, Nodeable, double, double)}).
     *
     * @param costCalculation      the cost model; its matrix must be initialized before {@link #plan} runs
     * @param trackableAssignment  the resource assignment deciding which merges the fleet can serve
     * @param localSearchAlgorithm the local search applied to merged plans
     * @param optimizationType     the zone policy
     * @param lambdas              the λ values to sweep, in tie-breaking order; copied
     * @param betas                the β values to sweep, possibly empty; copied
     * @throws NullPointerException     if any argument, λ or β is {@code null}
     * @throws IllegalArgumentException if {@code lambdas} is empty, or any λ or β is not a finite number
     */
    public AbstractClarkeWrightAlgorithm(PathCostCalculation<P, N, ?> costCalculation,
            TrackableAssignment<P, ?> trackableAssignment,
            LocalSearchAlgorithm<P> localSearchAlgorithm,
            OptimizationType optimizationType, List<Double> lambdas, List<Double> betas) {
        this.costCalculation = Objects.requireNonNull(costCalculation, "costCalculation");
        this.trackableAssignment = Objects.requireNonNull(trackableAssignment, "trackableAssignment");
        this.localSearchAlgorithm = Objects.requireNonNull(localSearchAlgorithm, "localSearchAlgorithm");
        this.optimizationType = Objects.requireNonNull(optimizationType, "optimizationType");
        this.lambdas = List.copyOf(Objects.requireNonNull(lambdas, "lambdas"));
        this.betas = List.copyOf(Objects.requireNonNull(betas, "betas"));
        if (this.lambdas.isEmpty()) {
            throw new IllegalArgumentException("At least one lambda is required");
        }
        for (Double value : this.lambdas) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Lambda must be a finite number, got " + value);
            }
        }
        for (Double value : this.betas) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Beta must be a finite number, got " + value);
            }
        }
    }

    /**
     * Best value first. The β blend gives both orientations of a pair the same value, so the better plain saving
     * breaks the tie — the orientation that actually saves more is tried first — and only then the origin code.
     */
    private static List<Saving> sorted(List<RankedSaving> savings) {
        savings.sort(Comparator.comparingDouble((RankedSaving r) -> r.saving().value()).reversed()
                .thenComparing(Comparator.comparingDouble(RankedSaving::plain).reversed())
                .thenComparing(r -> r.saving().origin().getCode()));
        return savings.stream().map(RankedSaving::saving).collect(Collectors.toList());
    }

    private static Set<String> codesOf(List<? extends Nodeable<?>> nodes) {
        return nodes.stream().map(Nodeable::getCode).collect(Collectors.toSet());
    }

    private static boolean containsInstance(List<?> list, Object element) {
        for (Object item : list) {
            if (item == element) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds a plan set covering {@code customers} out of {@code origin}, layered on top of any {@code previousPlans}.
     *
     * <p>Customers whose code appears among the <em>customers</em> of {@code previousPlans} are dropped so nothing is
     * planned twice (the depot stops of previous plans are not considered); the remaining customers seed the initial
     * plans through {@link #getInitialPlans}. Under {@link OptimizationType#LOCATION_ZONE} each zone (a {@code null}
     * zone counts as one zone of its own) first chooses its own best λ/β against its own seed plans and the previous
     * plans of that zone, then a single cross-zone merge pass ({@link #mergePlans(List, List)}) runs over all plans
     * with those per-zone parameters. Otherwise the whole customer pool is swept ({@link #getPlans}) and the best
     * resulting set returned ({@link #getBestPlans}).</p>
     *
     * <p><strong>Previous plans that cannot be re-assigned.</strong> Every merge attempt re-assigns the
     * resource-holding plans together with the merged one, so before the sweep the previous plans with customers and
     * a resource are split, greedily in input order and on copies: such a plan <em>takes part</em> when the
     * participating ones before it plus itself can still be assigned ({@link TrackableAssignment#assign(List)}, with
     * the units of the plans already found fixed withheld); otherwise it is <em>fixed</em> (for example because its
     * unit is no longer in the assignment's current pool, or the assignment hook refuses it). The split is repeated
     * until it finds no new fixed plan. Fixed plans take no part in the merge pass, and their units are withheld from
     * every merge attempt ({@link TrackableAssignment#assignGreedy(List, List)}) and from the final assignment pass, so
     * no unit is booked twice and the new customers are still merged. Previous plans without a resource always take
     * part (the merge walk never re-assigns them). When no plan is fixed the result is the same as without this
     * step.</p>
     *
     * <p>The returned list is new and includes the previous plans, as copies: the caller's instances are never
     * modified. Their customers are never moved out and never dropped. The participating copies take part in the merge
     * pass, so they may be re-assigned to a different resource and are re-optimized by local search (their stop order
     * may change); a fixed previous plan, or one the final pass cannot assign, is returned unchanged with the resource
     * it came with (possibly none). Previous plans without customers take no part in construction and never take a
     * resource ahead of a real plan: they are appended last, each keeping its own resource if no other plan holds it,
     * otherwise taking a unit no plan uses, otherwise left without one. New plans that no resource can serve are left
     * out, so the result may serve fewer customers than {@code customers}; callers recover them by diffing.</p>
     *
     * @param origin        the depot the seed plans start from; never paired in a saving
     * @param customers     the customers to plan; not modified
     * @param previousPlans plans already fixed by the caller, possibly none; not modified
     * @return a new, modifiable list: the plans the merge pass kept (constructed plans and previous-plan copies with a
     *     resource), then copies of the previous plans it did not keep (the fixed ones among them), then the previous
     *     plans without customers
     * @throws IllegalArgumentException if two customers share a code
     * @throws NullPointerException     if {@code customers} or {@code previousPlans} is {@code null}
     * @throws com.github.liliumsoftware.optimization.exception.CancelException if the algorithm (or the nested local
     *     search) is cancelled or the running thread is interrupted
     * @throws ArithmeticException      if the total cost of a candidate plan set does not fit a {@code long} (see
     *     {@link CostCalculation#totalCost})
     */
    @SafeVarargs
    // Collections.addAll would trip javac's varargs lint
    @SuppressWarnings({"ManualArrayToCollectionCopy", "UseBulkOperation"})
    public final List<P> plan(N origin, List<N> customers, P... previousPlans) {
        requireDistinctCodes(customers);
        List<P> originals = new ArrayList<>(previousPlans.length);
        for (P plan : previousPlans) {
            originals.add(plan);
        }
        List<P> previous = originals.stream().map(Planable::copy).collect(Collectors.toCollection(ArrayList::new));
        final List<P> emptyPrevious = previous.stream().filter(p -> p.getCustomers().isEmpty()).toList();
        previous.removeIf(p -> p.getCustomers().isEmpty());
        Set<String> plannedCodes = previous.stream().flatMap(p -> p.getCustomers().stream()).map(Nodeable::getCode)
                .collect(Collectors.toSet());
        customers = new ArrayList<>(customers);
        customers.removeIf(c -> plannedCodes.contains(c.getCode()));
        List<P> fixed = fixedPreviousPlans(previous);
        previous.removeIf(p -> containsInstance(fixed, p));
        List<P> seeds = getInitialPlans(origin, customers);
        List<N> newCustomers = customers;
        List<P> finalPlans = withFixedPlans(fixed, () -> construct(origin, newCustomers, seeds, previous));
        finalPlans = withPreviousPlans(finalPlans, originals);
        finalPlans.addAll(withoutCustomers(finalPlans, emptyPrevious));
        if (log.isDebugEnabled()) {
            log.debug(getMessage(finalPlans));
        }
        return finalPlans;
    }

    /**
     * The construction proper of {@link #plan}: the zone-wise or pool-wide sweep and merge over the seed plans and the
     * participating previous plans. Runs with the fixed previous plans published through {@link #fixedPlans}.
     */
    private List<P> construct(N origin, List<N> customers, List<P> initialPlans, List<P> previous) {
        List<P> finalPlans;
        if (optimizationType == OptimizationType.LOCATION_ZONE) {
            Map<String, LambdaBeta> zoneLambdaBetas = groupByZone(customers)
                    .entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                            nodes -> getBestPlans(getPlans(origin, nodes.getValue(),
                                    Stream.concat(initialPlans.stream().filter(p -> !p.getCustomers().isEmpty()
                                                    && codesOf(nodes.getValue()).contains(p.getCustomers().getFirst()
                                                    .getCode())),
                                            previous.stream().filter(p -> Objects.equals(p.getZone(),
                                                    nodes.getKey()))).toList())).lambdaBeta()));
            initialPlans.addAll(0, previous);
            finalPlans = mergePlans(initialPlans, getSavings(origin, customers, getRouteStarts(origin, initialPlans),
                    getRouteEnds(origin, initialPlans), zoneLambdaBetas));
        } else {
            initialPlans.addAll(0, previous);
            finalPlans = getBestPlans(getPlans(origin, customers, initialPlans)).plans();
        }
        return finalPlans;
    }

    /**
     * The previous plans (with customers) that cannot take part in the merge pass, decided greedily in input order on
     * copies: a resource-holding previous plan takes part when the participating ones before it plus itself can be
     * assigned ({@link #canTakePart}) with the units of the plans already found fixed withheld; otherwise it is fixed.
     * Because a plan found fixed later withholds a unit an earlier participant may have been probed with, the scan
     * repeats until it finds no new fixed plan, so the participating set is always assignable next to the fixed ones.
     * Previous plans without a resource never block a merge (the merge walk only re-assigns resource-holding plans)
     * and always take part. Returns a subset of {@code previous} (same objects), in the order they were found.
     */
    private List<P> fixedPreviousPlans(List<P> previous) {
        List<P> fixed = new ArrayList<>();
        boolean grown = true;
        while (grown) {
            grown = false;
            List<P> participating = new ArrayList<>();
            for (P plan : previous) {
                if (plan.getTrackable() == null || containsInstance(fixed, plan)) {
                    continue;
                }
                List<P> probe = new ArrayList<>(participating.size() + 1);
                for (P p : participating) {
                    probe.add(p.copy());
                }
                probe.add(plan.copy());
                if (canTakePart(probe, fixed)) {
                    participating.add(plan);
                } else {
                    log.trace("Previous plan {} cannot be re-assigned; it keeps its resource", plan.getCode());
                    fixed.add(plan);
                    grown = true;
                }
            }
        }
        return fixed;
    }

    /**
     * Whether {@code plans} (copies, which this call may mutate) can be assigned the way the merge walk re-assigns its
     * resource-holding plans while the units of {@code fixed} are withheld: here
     * {@link TrackableAssignment#assignGreedy(List, List)}, which with no fixed plan is exactly
     * {@link TrackableAssignment#assign(List)}. The partial variant draws from its pool instead.
     */
    boolean canTakePart(List<P> plans, List<P> fixed) {
        return trackableAssignment.assignGreedy(fixed, plans);
    }

    /**
     * The fixed previous plans of the {@link #plan} call running on the current thread (see {@link #plan}), or an
     * empty list outside such a call. The λ/β workers receive them explicitly through {@link #withFixedPlans}.
     */
    final List<P> fixedPlans() {
        List<P> fixed = fixedPlans.get();
        return fixed == null ? List.of() : fixed;
    }

    /**
     * Runs {@code action} with {@code fixed} published as the current thread's fixed previous plans, restoring the
     * previous value afterwards (a parallel-stream worker may be the calling thread itself).
     */
    final <R> R withFixedPlans(List<P> fixed, Supplier<R> action) {
        List<P> outer = fixedPlans.get();
        fixedPlans.set(fixed);
        try {
            return action.get();
        } finally {
            if (outer == null) {
                fixedPlans.remove();
            } else {
                fixedPlans.set(outer);
            }
        }
    }

    /**
     * {@code kept} plus the fixed previous plans, whose units are therefore not on offer; {@code kept} itself when
     * there are none.
     */
    private List<P> withFixed(List<P> kept, List<P> fixed) {
        if (fixed.isEmpty()) {
            return kept;
        }
        List<P> holders = new ArrayList<>(kept);
        holders.addAll(fixed);
        return holders;
    }

    /**
     * {@code plans} plus a fresh copy of every previous plan with customers that the merge pass did not keep (a fixed
     * one, which never took part, or one the final pass could not assign), so a previous plan's customers are never
     * lost. A previous plan is never merged (no
     * saving starts or ends at its customers), so it is recognised by its code and its set of customer codes.
     */
    private List<P> withPreviousPlans(List<P> plans, List<P> previousPlans) {
        List<P> result = new ArrayList<>(plans);
        for (P previous : previousPlans) {
            if (previous.getCustomers().isEmpty()) {
                continue;   // handled by withoutCustomers, after every real plan has its resource
            }
            Set<String> codes = customerCodes(previous);
            boolean kept = plans.stream()
                    .anyMatch(p -> Objects.equals(p.getCode(), previous.getCode()) && customerCodes(p).equals(codes));
            if (!kept) {
                log.trace("Keeping previous plan {} with its own resource", previous.getCode());
                result.add(previous.copy());
            }
        }
        return result;
    }

    /**
     * The previous plans without customers, each keeping its own resource when no plan of {@code plans} holds it,
     * otherwise assigned a unit no plan uses (left without one if there is none; the assignment hook clears the plan's
     * resource on failure). Plan codes are unique, so no two of these plans are equal.
     */
    private List<P> withoutCustomers(List<P> plans, List<P> emptyPrevious) {
        List<P> result = new ArrayList<>();
        for (P plan : emptyPrevious) {
            List<P> holders = new ArrayList<>(plans);
            holders.addAll(result);
            boolean ownUnitFree = plan.getTrackable() != null
                    && holders.stream().noneMatch(p -> plan.getTrackable().equals(p.getTrackable()));
            if (!ownUnitFree) {
                trackableAssignment.assign(holders, plan);
            }
            result.add(plan);
        }
        return result;
    }

    private Set<String> customerCodes(P plan) {
        return plan.getCustomers().stream().map(Nodeable::getCode).collect(Collectors.toSet());
    }

    /**
     * Customers grouped by zone; unlike {@code Collectors.groupingBy} a {@code null} zone is a group of its own.
     */
    private Map<String, List<N>> groupByZone(List<N> customers) {
        Map<String, List<N>> zones = new HashMap<>();
        for (N customer : customers) {
            zones.computeIfAbsent(customer.getZone(), zone -> new ArrayList<>()).add(customer);
        }
        return zones;
    }

    /**
     * Where the route through each customer ends, read from the plans the savings will merge — the same way the merge
     * core decides it: for the last customer of every plan, the plan's {@link Planable#getDestination() destination}
     * stop; else the plan's origin when the route is closed ({@link #isClosedRoute}); else {@code null}, an open route
     * that simply stops at that customer. A customer that ends none of {@code plans} is treated as returning to
     * {@code origin}, the classic assumption. Customers are matched by code.
     *
     * @param origin the depot assumed for customers that end none of {@code plans}
     * @param plans  the plans the savings will merge; read once, not retained
     * @return a lookup from customer to route end ({@code null} for an open route); safe to share between threads
     */
    protected Function<N, N> getRouteEnds(N origin, List<P> plans) {
        Map<String, N> ends = new HashMap<>();
        for (P plan : plans) {
            List<N> planCustomers = plan.getCustomers();
            if (planCustomers.isEmpty()) {
                continue;
            }
            N end = plan.getDestination();
            if (end == null && isClosedRoute(plan)) {
                end = plan.getOrigin();
            }
            ends.put(planCustomers.getLast().getCode(), end);
        }
        return node -> ends.getOrDefault(node.getCode(), origin);
    }

    /**
     * Whether a plan without a destination stop is a closed tour that returns to its origin. Decided by the plan's
     * resource ({@link Trackable#isReturns()}) once one is assigned; before that — the seed plans the savings are
     * computed from have none yet — by the fleet: closed when any unit of the fleet returns, which is the classic
     * Clarke-Wright assumption, open only when no unit does. Override to decide differently for a mixed fleet.
     *
     * @param plan a plan without a destination stop
     * @return {@code true} if the route returns to its origin
     */
    protected boolean isClosedRoute(P plan) {
        if (plan.getTrackable() != null) {
            return plan.getTrackable().isReturns();
        }
        return trackableAssignment.getTrackables().stream().anyMatch(Trackable::isReturns);
    }

    /**
     * Where the route through each customer starts, read from the plans the savings will merge: for the first customer
     * of every plan, the plan's {@link Planable#getOrigin() origin} stop, or {@code null} when the plan has none (a
     * route such as {@code customer…, DESTINATION} that starts at its first customer and therefore has no start leg a
     * merge could save). A customer that starts none of {@code plans} is treated as starting at {@code origin}.
     * Customers are matched by code.
     *
     * @param origin the depot assumed for customers that start none of {@code plans}
     * @param plans  the plans the savings will merge; read once, not retained
     * @return a lookup from customer to route start ({@code null} when the route has no origin stop); safe to share
     *     between threads
     */
    protected Function<N, N> getRouteStarts(N origin, List<P> plans) {
        Map<String, N> starts = new HashMap<>();
        for (P plan : plans) {
            List<N> planCustomers = plan.getCustomers();
            if (!planCustomers.isEmpty()) {
                starts.put(planCustomers.getFirst().getCode(), plan.getOrigin());
            }
        }
        return node -> starts.getOrDefault(node.getCode(), origin);
    }

    /**
     * Runs the full λ/β grid — one {@link #mergePlans(List, List)} pass per parameter combination, on a
     * {@code parallelStream} — and returns each outcome tagged with the {@link LambdaBeta} that produced it, in
     * configuration order (see {@link #grid()}; when {@code betas} is empty only λ varies, with β = 0). Every pass gets
     * its own deep copy of {@code plans} and computes its own savings ({@link #getSavings(Nodeable, List, Function,
     * Function, double, double)}), from the route starts and ends of {@code plans} ({@link #getRouteStarts},
     * {@link #getRouteEnds}), so the passes share no mutable state of this algorithm; the collaborators and hooks are
     * called from worker threads. The fixed previous plans of the running {@link #plan} call are read on the calling
     * thread and published to each worker for the duration of its pass, so {@link #mergePlans(List, List)}, including
     * an override that calls {@code super}, withholds their units on every worker. An override of this method that
     * calls {@code mergePlans} on threads of its own does not publish them there, so those passes would not withhold
     * the units of fixed previous plans; such an override should merge on the calling thread or call {@code super}.
     *
     * @param origin    the depot
     * @param customers the customers the savings are formed over
     * @param plans     the plans to merge (seed plans, possibly with previous plans); not modified
     * @return one outcome per grid point, in configuration order
     * @throws com.github.liliumsoftware.optimization.exception.CancelException if the algorithm is cancelled
     */
    protected List<PlanList<P>> getPlans(N origin, List<N> customers, List<P> plans) {
        Function<N, N> routeStarts = getRouteStarts(origin, plans);
        Function<N, N> routeEnds = getRouteEnds(origin, plans);
        // the workers do not see the caller's thread-local: hand them the fixed previous plans explicitly
        List<P> fixed = fixedPlans();
        if (betas.isEmpty()) {
            return lambdas.parallelStream().map(l -> new PlanList<>(withFixedPlans(fixed, () -> mergePlans(
                    plans.stream().map(Planable::copy).collect(Collectors.toList()),
                    getSavings(origin, customers, routeStarts, routeEnds, l, 0))), new LambdaBeta(l))).toList();
        } else {
            return grid().parallelStream().map(lb -> new PlanList<>(withFixedPlans(fixed, () -> mergePlans(
                    plans.stream().map(Planable::copy).collect(Collectors.toList()),
                    getSavings(origin, customers, routeStarts, routeEnds, lb.lambda(), lb.beta()))), lb)).toList();
        }
    }

    /**
     * The {@code lambdas × betas} grid in configuration order (λ-major, each λ with every β in turn; duplicates once).
     * The sweep evaluates it in parallel but returns the outcomes in this order, so a tie under
     * {@link #plansComparator()} goes to the combination listed first rather than to whichever hash order a map
     * would give. Each combination's savings are computed by the worker that merges them, so the grid never holds
     * all savings lists at once.
     *
     * @return the distinct (λ, β) combinations, unmodifiable; empty when {@code betas} is empty
     */
    final List<LambdaBeta> grid() {
        Set<LambdaBeta> grid = new LinkedHashSet<>();
        for (Double lambda : lambdas) {
            for (Double beta : betas) {
                grid.add(new LambdaBeta(lambda, beta));
            }
        }
        return List.copyOf(grid);
    }

    /**
     * Picks the winning sweep outcome under {@link #plansComparator()} (most customers served, ties broken by lowest
     * total cost); among equally good outcomes the first in {@code plans} wins.
     *
     * @param plans the sweep outcomes, in configuration order
     * @return the best outcome
     * @throws IllegalStateException if the sweep produced no candidates at all
     * @throws ArithmeticException   if the default comparator's total cost of an outcome does not fit a {@code long}
     */
    protected PlanList<P> getBestPlans(List<PlanList<P>> plans) {
        return plans.stream().min((o1, o2) -> plansComparator().compare(o1.plans(), o2.plans()))
                .orElseThrow(() -> new IllegalStateException("At least one list of plans must be available"));
    }

    /**
     * Whether the merge walk may try {@code saving} at all; asked once per saving, before its routes are looked up and
     * before any assignment is attempted. A saving this method rejects is skipped. The default accepts every saving,
     * whatever its value, so zero and negative savings are merged whenever the merged plans can be assigned (see the
     * class documentation). Override with {@code return saving.value() > 0;} to merge only positive savings (the
     * classic rule); the routes such a rejection leaves apart then need units of their own in the final assignment
     * pass, and those that get none are left out. Called from the parallel λ/β workers, so it must be thread-safe.
     *
     * @param saving the saving about to be tried
     * @return {@code true} to try the merge, {@code false} to skip the saving
     */
    protected boolean isMergeable(Saving saving) {
        return true;
    }

    /**
     * The greedy merge core. Walks {@code savings} once, in list order (sorted best first by {@link #getSavings}); for
     * each saving it looks for the plan whose customer sequence <em>ends</em> at the saving's origin (matched by code)
     * and the plan that <em>begins</em> at its destination, concatenates them into a single route via
     * {@link #getNewPlan} (origin of the first plan, else of the second; destination of the second, else of the
     * first), and commits the merge only if {@link TrackableAssignment#assignGreedy(List, List)} still succeeds on the
     * assignable subset (every plan holding a resource plus the merged one) with the units of the fixed previous plans
     * of the running {@link #plan} call withheld (with none, exactly {@link TrackableAssignment#assign(List)}). A
     * saving {@link #isMergeable(Saving)} rejects is skipped; by default its value is not checked, so a zero or
     * negative saving is merged like any other (see the class documentation). The attempt works on copies made by
     * {@link PlanMerger}; on success the contents of {@code plans} are replaced (plans without a resource first, then
     * the assigned subset) and every plan of that subset is re-optimized by local search. Plans without customers never
     * take part in a merge. Construction always completes: an {@link #interrupt() interrupt} does not stop the walk
     * (stopping it would leave unmerged plans the fleet cannot cover, and their customers would be dropped). A final
     * pass tries to assign a resource to every leftover plan (ordered by {@link #planComparator()}) via
     * {@link TrackableAssignment#assign(List, Planable)} against the plans kept so far plus the fixed previous plans,
     * and keeps only those that succeed, so unassignable plans are
     * dropped here ({@link #plan} returns dropped previous plans regardless); every kept plan is then re-optimized by
     * local search.
     *
     * @param plans   the plans to merge; its contents are replaced on every accepted merge
     * @param savings the savings in the order to try them; cleared once walked
     * @return a new list of the plans holding a resource
     * @throws com.github.liliumsoftware.optimization.exception.CancelException if the algorithm is cancelled or the
     *     running thread is interrupted
     */
    protected List<P> mergePlans(List<P> plans, List<Saving> savings) {
        List<P> fixed = fixedPlans();
        return mergePlans(plans, savings, merged -> trackableAssignment.assignGreedy(fixed, merged),
                (kept, plan) -> trackableAssignment.assign(withFixed(kept, fixed), plan));
    }

    /**
     * The merge walk shared with {@link AbstractPartialClarkeWrightAlgorithm}, which differs only in the pool the
     * resources are drawn from.
     *
     * <p>The plans whose customer sequence begins / ends at each customer are indexed by customer code and the indexes
     * rebuilt only after an accepted merge (the only time a plan's ends can change), instead of scanning every plan for
     * every saving. Customer codes are unique, so a customer begins or ends at most one plan and the indexes find
     * exactly the plans a scan would.
     *
     * @param plans     the plans to merge; its contents are replaced on every accepted merge
     * @param savings   the savings in the order to try them; cleared once walked
     * @param assignAll assigns the plans holding a resource plus the merged one; {@code false} rejects the merge
     * @param assignOne assigns one leftover plan against the plans kept so far; {@code false} drops it
     * @return a new list of the plans holding a resource
     */
    final List<P> mergePlans(List<P> plans, List<Saving> savings, Predicate<List<P>> assignAll,
            BiPredicate<List<P>, P> assignOne) {
        Map<String, P> byFirstCustomer = new HashMap<>();
        Map<String, P> byLastCustomer = new HashMap<>();
        indexRouteEnds(plans, byFirstCustomer, byLastCustomer);
        // Walk the list once instead of removing its head each time: on an ArrayList every head removal shifts all
        // remaining savings, which made this loop quadratic in the number of savings.
        for (Saving saving : savings) {
            checkCancel();
            if (!isMergeable(saving)) {
                log.trace("Saving not mergeable: {}", saving);
                continue;
            }
            P firstPlan = byLastCustomer.get(saving.origin().getCode());
            P secondPlan = byFirstCustomer.get(saving.destination().getCode());
            if (firstPlan == null || secondPlan == null || firstPlan == secondPlan) {
                // a plan that both begins with the destination and ends with the origin would be merged with itself
                log.trace("There is no route with start / end matching saving : {}. saving ignored", saving);
            } else {
                log.trace("Trying to merge based on saving: {}", saving);
                List<N> newNodes = new ArrayList<>();
                // The merged route starts where the first route starts and ends where the second one ends; either
                // depot stop may be missing (see Planable), and with routes of different shapes the other route's stop
                // is kept rather than dropped. Appending a missing stop unconditionally would splice in null.
                N mergedOrigin = firstPlan.getOrigin() != null ? firstPlan.getOrigin() : secondPlan.getOrigin();
                if (mergedOrigin != null) {
                    newNodes.add(mergedOrigin);
                }
                newNodes.addAll(firstPlan.getCustomers());
                newNodes.addAll(secondPlan.getCustomers());
                N mergedDestination = secondPlan.getDestination() != null ? secondPlan.getDestination()
                        : firstPlan.getDestination();
                if (mergedDestination != null) {
                    newNodes.add(mergedDestination);
                }
                P newPlan = getNewPlan(firstPlan, newNodes);
                List<P> tempPlans = new PlanMerger<>(plans, firstPlan, secondPlan, newPlan).merge();
                // by identity: newPlan usually keeps the first plan's code, and plans may compare by code
                List<P> mergedPlans = tempPlans.stream()
                        .filter(p -> p.getTrackable() != null || p == newPlan).collect(Collectors.toList());
                if (assignAll.test(mergedPlans)) {
                    plans.clear();
                    mergedPlans.replaceAll(localSearchAlgorithm::apply);
                    plans.addAll(tempPlans.stream().filter(p -> p.getTrackable() == null && p != newPlan)
                            .toList());
                    plans.addAll(mergedPlans);
                    indexRouteEnds(plans, byFirstCustomer, byLastCustomer);
                    log.trace("Merged plan: {}", newPlan);
                } else {
                    log.trace("Merging plans failed");
                }
            }
        }
        savings.clear();
        List<P> finalPlans = plans.stream().filter(p -> p.getTrackable() != null).collect(Collectors.toList());
        for (P plan : plans.stream().filter(p -> p.getTrackable() == null).sorted(planComparator()).toList()) {
            checkCancel();
            if (assignOne.test(finalPlans, plan)) {
                finalPlans.add(plan);
            } else {
                log.trace("Trackable assignment failed");
            }
        }
        finalPlans.replaceAll(localSearchAlgorithm::apply);
        log.trace("Merged plans");
        return finalPlans;
    }

    /**
     * Rebuilds the code → plan indexes of the first and last customer of every plan with customers.
     */
    private void indexRouteEnds(List<P> plans, Map<String, P> byFirstCustomer, Map<String, P> byLastCustomer) {
        byFirstCustomer.clear();
        byLastCustomer.clear();
        for (P plan : plans) {
            List<N> customers = plan.getCustomers();
            if (!customers.isEmpty()) {
                byFirstCustomer.put(customers.getFirst().getCode(), plan);
                byLastCustomer.put(customers.getLast().getCode(), plan);
            }
        }
    }

    /**
     * Computes every candidate saving for one fixed λ/β, sorted descending; ties go to the better plain (unblended)
     * saving, then to the origin code, then to the destination listed first in {@code customers}. This order matches
     * {@link Saving#reversedValueComparator()} except for β &gt; 0, where it adds the plain-saving tie-break. Every
     * ordered pair of distinct customers yields one saving, whatever its sign; self-pairs and any pair touching the
     * depot {@code origin} are excluded. Under {@link OptimizationType#LOCATION_ZONE} pairs are only formed within a
     * zone, so no saving can ever bridge two zones. {@code beta <= 0} selects the plain (asymmetric) saving formula,
     * otherwise the β-blended one.
     *
     * @param origin      the depot; excluded from every pair
     * @param customers   the customers to pair
     * @param routeStarts where the route through each customer starts ({@code null} without an origin stop); see
     *                    {@link #getRouteStarts}
     * @param routeEnds   where the route through each customer ends ({@code null} for an open route); see
     *                    {@link #getRouteEnds}
     * @param lambda      the λ of the saving formula
     * @param beta        the β of the blended formula, or a value &le; 0 for the plain one
     * @return a new, modifiable list of savings, best first
     */
    protected List<Saving> getSavings(N origin, List<N> customers, Function<N, N> routeStarts, Function<N, N> routeEnds,
            double lambda, double beta) {
        List<RankedSaving> savings = new ArrayList<>();
        if (optimizationType == OptimizationType.LOCATION_ZONE) {
            for (List<N> zone : groupByZone(customers).values()) {
                addSavings(savings, origin, zone, routeStarts, routeEnds, lambda, beta);
            }
        } else {
            addSavings(savings, origin, customers, routeStarts, routeEnds, lambda, beta);
        }
        return sorted(savings);
    }

    /**
     * Zone-aware variant used by the {@link OptimizationType#LOCATION_ZONE} path: each zone's savings are computed with
     * that zone's own {@link LambdaBeta} (chosen earlier by an independent sweep), then all zones are merged into one
     * descending-sorted list (same order as above) for a single combined merge pass. As above, pairs never cross a
     * zone boundary.
     *
     * @param origin          the depot; excluded from every pair
     * @param customers       the customers to pair, grouped by zone ({@code null} zone included)
     * @param routeStarts     where the route through each customer starts ({@code null} without an origin stop)
     * @param routeEnds       where the route through each customer ends ({@code null} for an open route)
     * @param zoneLambdaBetas the parameters chosen for each zone, keyed by zone name ({@code null} key for customers
     *                        without a zone)
     * @return a new, modifiable list of savings, best first
     * @throws NullPointerException if a zone of {@code customers} has no entry in {@code zoneLambdaBetas}
     */
    protected List<Saving> getSavings(N origin, List<N> customers, Function<N, N> routeStarts, Function<N, N> routeEnds,
            Map<String, LambdaBeta> zoneLambdaBetas) {
        List<RankedSaving> savings = new ArrayList<>();
        for (Map.Entry<String, List<N>> e : groupByZone(customers).entrySet()) {
            LambdaBeta lambdaBeta = zoneLambdaBetas.get(e.getKey());
            addSavings(savings, origin, e.getValue(), routeStarts, routeEnds, lambdaBeta.lambda(), lambdaBeta.beta());
        }
        return sorted(savings);
    }

    private void addSavings(List<RankedSaving> savings, N origin, List<N> nodes, Function<N, N> routeStarts,
            Function<N, N> routeEnds, double lambda, double beta) {
        for (N nodeI : nodes) {
            for (N nodeJ : nodes) {
                if (!nodeI.equals(nodeJ) && !nodeI.equals(origin) && !nodeJ.equals(origin)) {
                    N startJ = routeStarts.apply(nodeJ);
                    N endI = routeEnds.apply(nodeI);
                    double plain = getSavingValue(startJ, endI, nodeI, nodeJ, lambda);
                    double value = beta <= 0 ? plain : getSavingValue(routeStarts.apply(nodeI), startJ, endI,
                            routeEnds.apply(nodeJ), nodeI, nodeJ, lambda, beta);
                    savings.add(new RankedSaving(new Saving(nodeI, nodeJ, value), plain));
                }
            }
        }
    }

    /**
     * The parameterized Clarke-Wright saving of chaining {@code nodeI} directly into {@code nodeJ}, computed from the
     * actual ends of the two routes: {@code cost(i,end) + cost(start,j) − λ·cost(i,j)}, where {@code end} is where
     * {@code nodeI}'s route ends and {@code start} where {@code nodeJ}'s route starts. For a closed tour {@code end} is
     * the origin (the classic formula), for a separate destination depot it is that depot, and for an open route
     * ({@code routeEnd == null}) there is no return leg to save; likewise a route without an origin stop
     * ({@code routeStart == null}) has no start leg to save. λ scales the direct edge: a larger λ lowers the saving of
     * pairs that are far apart relative to the depot legs, so such pairs are tried <em>later</em>; a smaller λ moves
     * them earlier. λ only reorders the merge walk: every saving is still merged when its routes can be assigned,
     * whatever its sign, so λ &gt; 1 does not by itself keep distant customers apart (see the class documentation).
     * This is the one place fractional arithmetic is allowed — the resulting plan costs remain integral.
     *
     * @param routeStart where {@code nodeJ}'s route starts, or {@code null} when it has no origin stop
     * @param routeEnd   where {@code nodeI}'s route ends, or {@code null} for an open route
     * @param nodeI      the customer that would end the first route
     * @param nodeJ      the customer that would begin the second route
     * @param lambda     the weight of the direct edge {@code nodeI → nodeJ}
     * @return the saving; may be zero or negative
     */
    protected double getSavingValue(N routeStart, N routeEnd, N nodeI, N nodeJ, double lambda) {
        double savedEnd = routeEnd == null ? 0 : costCalculation.calculateCost(nodeI, routeEnd);
        double savedStart = routeStart == null ? 0 : costCalculation.calculateCost(routeStart, nodeJ);
        return savedEnd + savedStart - (lambda * costCalculation.calculateCost(nodeI, nodeJ));
    }

    /**
     * The β variant used when {@code beta > 0}: a convex blend of the two orientations,
     * {@code beta·max(s_ij, s_ji) + (1 − beta)·min(s_ij, s_ji)}, where {@code s} is the λ-saving of each direction
     * computed from its own route start and end. {@code beta = 1} keeps only the better orientation,
     * {@code beta = 0.5} averages them. Both orientations get the same value; {@link #getSavings} tries the one with
     * the better plain saving first. (Earlier versions added {@code beta} instead of multiplying by it, which merely
     * shifted every saving by a constant.) β is not range-checked: β &gt; 1 extrapolates beyond the better
     * orientation (the result exceeds {@code max(s_ij, s_ji)} when the orientations differ); values &le; 0 never reach
     * this method from {@link #getSavings}, which uses the plain formula for them.
     *
     * @param routeStartI where {@code nodeI}'s route starts, or {@code null} without an origin stop
     * @param routeStartJ where {@code nodeJ}'s route starts, or {@code null} without an origin stop
     * @param routeEndI   where {@code nodeI}'s route ends, or {@code null} for an open route
     * @param routeEndJ   where {@code nodeJ}'s route ends, or {@code null} for an open route
     * @param nodeI       one customer of the pair
     * @param nodeJ       the other customer of the pair
     * @param lambda      the weight of the direct edge in each orientation's saving
     * @param beta        the weight of the better orientation
     * @return the blended saving, shared by both orientations
     */
    protected double getSavingValue(N routeStartI, N routeStartJ, N routeEndI, N routeEndJ, N nodeI, N nodeJ,
            double lambda, double beta) {
        double sij = getSavingValue(routeStartJ, routeEndI, nodeI, nodeJ, lambda);
        double sji = getSavingValue(routeStartI, routeEndJ, nodeJ, nodeI, lambda);
        return (beta * Math.max(sij, sji)) + ((1d - beta) * Math.min(sij, sji));
    }

    /**
     * The debug message logged with the final plans of {@link #plan}; only called when debug logging is enabled.
     *
     * @param plans the final plans
     * @return the message
     */
    protected String getMessage(List<P> plans) {
        return "ClarkeWright " + plans.size() + " plans";
    }

    /**
     * Orders candidate plan sets: more customers served first, then lower total cost; the sweep keeps the first.
     * Customers are counted with {@link Planable#getCustomerCount()}, costs summed with
     * {@link CostCalculation#totalCost}, whose {@link ArithmeticException} on overflow the comparator propagates.
     *
     * @return the comparator; a smaller plan set is a better one
     */
    protected Comparator<List<P>> plansComparator() {
        return (original, candidate) -> {
            long originalNodeCount = original.stream().mapToLong(Planable::getCustomerCount).sum();
            long candidateNodeCount = candidate.stream().mapToLong(Planable::getCustomerCount).sum();
            if (originalNodeCount != candidateNodeCount) {
                return Long.compare(candidateNodeCount, originalNodeCount);
            }
            return Long.compare(CostCalculation.totalCost(original), CostCalculation.totalCost(candidate));
        };
    }

    /**
     * Propagates cancellation to the nested local search before cancelling itself. Never reset; affects every run in
     * progress on this instance.
     */
    @Override
    public void cancel() {
        localSearchAlgorithm.cancel();
        super.cancel();
    }

    /**
     * Propagates the graceful stop to the nested local search before interrupting itself. The merge pass itself does
     * not stop (construction always completes); only the local search may return earlier.
     */
    @Override
    public void interrupt() {
        localSearchAlgorithm.interrupt();
        super.interrupt();
    }

    /**
     * Orders the leftover plans (those still without a resource after the merge walk) for the final assignment pass:
     * plans ordered first get the first pick of the remaining units.
     *
     * @return the comparator; must not be {@code null}
     */
    protected abstract Comparator<P> planComparator();

    /**
     * Builds the seed plans: one plan per customer, typically {@code node → customer} plus a destination stop when the
     * domain has one (a seed may also omit the origin, see the class documentation). The seeds' first and last stops
     * determine the route starts and ends the savings are computed from. Seeds should carry no resource: a seed that
     * already holds one is treated as assigned by the merge walk.
     *
     * @param node      the depot the seed routes start from
     * @param customers the customers to seed, possibly empty
     * @return a new, modifiable list with one plan per customer
     */
    protected abstract List<P> getInitialPlans(N node, List<N> customers);

    /**
     * Builds the merged plan: a new plan with {@code nodes} as its route, taking its other attributes (code, zone, …)
     * from {@code plan}. The resource is assigned afterwards by the merge walk.
     *
     * @param plan  the first of the two merged plans (an element of the list being merged); must not be modified,
     *              since the merge may still be rejected
     * @param nodes the merged route: origin (if any), the customers of both plans, destination (if any); a fresh list
     *              the new plan may keep
     * @return the merged plan, a new object
     */
    protected abstract P getNewPlan(P plan, List<N> nodes);

    /**
     * A saving together with its plain (unblended) value, used only to order savings.
     */
    private record RankedSaving(Saving saving, double plain) {
    }

}
