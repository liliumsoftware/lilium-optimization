package ir.baho.algorithm.clarkewright;

import ir.baho.algorithm.BaseAlgorithm;
import ir.baho.algorithm.OptimizationType;
import ir.baho.algorithm.assignment.TrackableAssignment;
import ir.baho.algorithm.calculation.CostCalculation;
import ir.baho.algorithm.calculation.PathCostCalculation;
import ir.baho.algorithm.domain.Nodeable;
import ir.baho.algorithm.domain.Planable;
import ir.baho.algorithm.domain.Trackable;
import ir.baho.algorithm.localsearch.LocalSearchAlgorithm;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
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
 * descending order:
 * a saving merges two routes only when the first route <em>ends</em> at its {@code origin} customer and the second
 * <em>begins</em> at its {@code destination} customer, and only when {@link TrackableAssignment} can still assign a
 * resource to the merged set. Accepted merges mutate the plan list in place and every plan holding a resource is
 * re-optimized through {@link LocalSearchAlgorithm}.</p>
 *
 * <p>The classic algorithm is generalized along two axes:</p>
 * <ul>
 *   <li><b>λ / β parameter sweep.</b> {@code lambda} scales the direct edge term in the saving formula;
 *       {@code beta} weights the smaller asymmetric orientation (i&rarr;j vs. j&rarr;i) against the larger one;
 *       both orientations then share one value, and the one with the better plain saving is tried first. The full
 *       {@code lambdas × betas} grid is evaluated on a {@code parallelStream} and {@link #getBestPlans} keeps the
 *       strongest resulting plan set
 *       (see {@link #plansComparator()} for the "most customers served, then cheapest" criterion).</li>
 *   <li><b>Zone locality.</b> Under {@link OptimizationType#LOCATION_ZONE} customers are grouped by zone, each zone
 *       is swept independently to pick its own best λ/β, and only then are the per-zone savings combined into a
 *       single merge pass — savings never span zones. Any other {@link OptimizationType} treats all customers as one
 *       pool.</li>
 * </ul>
 *
 * <p>Abstract: subclasses supply the domain hooks ({@link #getInitialPlans}, {@link #getNewPlan},
 * {@link #planComparator()}). Being a {@link BaseAlgorithm}, the merge loop honours {@code cancel()} via
 * {@link #checkCancel()}. Construction always completes: an {@code interrupt()} does not stop the merge pass, because
 * stopping it would leave unmerged plans the fleet cannot cover and their customers would be dropped. Both signals
 * are forwarded to the nested local search.</p>
 *
 * @param <P> the plan/route type
 * @param <N> the node/stop type
 */
@Slf4j
public abstract class AbstractClarkeWrightAlgorithm<P
        extends Planable<P, N, ?>, N extends Nodeable<N>> extends BaseAlgorithm {

    protected final PathCostCalculation<P, N, ?> costCalculation;
    protected final TrackableAssignment<P, ?> trackableAssignment;
    protected final LocalSearchAlgorithm<P> localSearchAlgorithm;
    protected final OptimizationType optimizationType;
    protected final List<Double> lambdas;
    protected final List<Double> betas;

    /**
     * Zone-restricted construction ({@link OptimizationType#LOCATION_ZONE}) with the classic saving ({@code λ = 1}, no
     * β).
     */
    public AbstractClarkeWrightAlgorithm(PathCostCalculation<P, N, ?> costCalculation,
            TrackableAssignment<P, ?> trackableAssignment,
            LocalSearchAlgorithm<P> localSearchAlgorithm) {
        this(costCalculation, trackableAssignment, localSearchAlgorithm, OptimizationType.LOCATION_ZONE);
    }

    /**
     * Construction with the classic saving ({@code λ = 1}, no β) under the given zone policy.
     */
    public AbstractClarkeWrightAlgorithm(PathCostCalculation<P, N, ?> costCalculation,
            TrackableAssignment<P, ?> trackableAssignment,
            LocalSearchAlgorithm<P> localSearchAlgorithm,
            OptimizationType optimizationType) {
        this(costCalculation, trackableAssignment, localSearchAlgorithm, optimizationType, List.of(1D));
    }

    /**
     * Construction sweeping the given λ values without a β term.
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
     * (with an empty {@code betas} only λ varies).
     *
     * @throws NullPointerException     if any argument is {@code null}
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

    /**
     * Builds a plan set covering {@code customers} out of {@code origin}, layered on top of any {@code previousPlans}.
     *
     * <p>Customers already present in {@code previousPlans} (matched by code) are dropped so nothing is planned twice;
     * the remaining customers seed the initial one-stop plans. Under {@link OptimizationType#LOCATION_ZONE} each zone
     * (a {@code null} zone counts as one zone of its own) first chooses its own best λ/β against its own customers and
     * matching previous plans, then a single cross-zone merge pass runs with those per-zone parameters. Otherwise the
     * whole customer pool is swept and the best resulting set returned.</p>
     *
     * <p>The returned list includes the previous plans, as copies: the caller's instances are never modified. Their
     * customers are never moved out and never dropped. The copies take part in the merge pass, so they may be
     * re-assigned to a different resource and are re-optimized by local search (their stop order may change); a
     * previous plan that cannot be re-assigned is returned unchanged with the resource it came with (possibly
     * none). Previous plans without customers take no part in construction and never take a resource ahead of a
     * real plan: they are appended last, each keeping its own resource if no other plan holds it, otherwise taking a
     * unit no plan uses, otherwise left without one. New plans that no resource can serve are left out, as
     * before.</p>
     *
     * @throws IllegalArgumentException if two customers share a code
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
        Set<String> plannedCodes = previous.stream().flatMap(p -> p.getNodes().stream()).map(Nodeable::getCode)
                .collect(Collectors.toSet());
        customers = new ArrayList<>(customers);
        customers.removeIf(c -> plannedCodes.contains(c.getCode()));
        List<P> initialPlans = getInitialPlans(origin, customers);
        List<P> finalPlans;
        if (optimizationType == OptimizationType.LOCATION_ZONE) {
            Map<String, LambdaBeta> zoneLambdaBetas = groupByZone(customers)
                    .entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                            nodes -> getBestPlans(getPlans(origin, nodes.getValue(),
                                    Stream.concat(initialPlans.stream().filter(p -> !p.getCustomers().isEmpty()
                                                    && codesOf(nodes.getValue()).contains(p.getCustomers().get(0)
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
        finalPlans = withPreviousPlans(finalPlans, originals);
        finalPlans.addAll(withoutCustomers(finalPlans, emptyPrevious));
        log.debug(getMessage(finalPlans));
        return finalPlans;
    }

    /**
     * {@code plans} plus a fresh copy of every previous plan with customers that the merge pass dropped because it
     * could not be re-assigned, so a previous plan's customers are never lost. A previous plan is never merged (no
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
     * {@code origin}, the classic assumption.
     *
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
            ends.put(planCustomers.get(planCustomers.size() - 1).getCode(), end);
        }
        return node -> ends.getOrDefault(node.getCode(), origin);
    }

    /**
     * Whether a plan without a destination stop is a closed tour that returns to its origin. Decided by the plan's
     * resource ({@link Trackable#isReturns()}) once one is assigned; before that — the seed plans the savings are
     * computed from have none yet — by the fleet: closed when any unit of the fleet returns, which is the classic
     * Clarke-Wright assumption, open only when no unit does. Override to decide differently for a mixed fleet.
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
     *
     * @return a lookup from customer to route start ({@code null} when the route has no origin stop); safe to share
     *     between threads
     */
    protected Function<N, N> getRouteStarts(N origin, List<P> plans) {
        Map<String, N> starts = new HashMap<>();
        for (P plan : plans) {
            List<N> planCustomers = plan.getCustomers();
            if (!planCustomers.isEmpty()) {
                starts.put(planCustomers.get(0).getCode(), plan.getOrigin());
            }
        }
        return node -> starts.getOrDefault(node.getCode(), origin);
    }

    /**
     * Runs the full λ/β grid — one {@link #mergePlans} pass per parameter combination — and returns each outcome
     * tagged with the {@link LambdaBeta} that produced it. When {@code betas} is empty only λ varies. Every pass gets
     * its own deep copy of {@code plans} so the parallel sweep does not share mutable state. The savings use the route
     * starts and ends of {@code plans} ({@link #getRouteStarts}, {@link #getRouteEnds}).
     */
    protected List<PlanList<P>> getPlans(N origin, List<N> customers, List<P> plans) {
        Function<N, N> routeStarts = getRouteStarts(origin, plans);
        Function<N, N> routeEnds = getRouteEnds(origin, plans);
        if (betas.isEmpty()) {
            return lambdas.parallelStream().map(l -> new PlanList<>(mergePlans(plans.stream().map(Planable::copy)
                            .collect(Collectors.toList()),
                    getSavings(origin, customers, routeStarts, routeEnds, l, 0)), new LambdaBeta(l))).toList();
        } else {
            Map<LambdaBeta, List<Saving>> savings = new HashMap<>();
            lambdas.forEach(l -> betas.forEach(b -> savings.put(new LambdaBeta(l, b), getSavings(origin, customers,
                    routeStarts, routeEnds, l, b))));
            return savings.entrySet().parallelStream().map(s -> new PlanList<>(mergePlans(plans.stream()
                            .map(Planable::copy).collect(Collectors.toList()),
                    s.getValue()), s.getKey())).collect(Collectors.toList());
        }
    }

    /**
     * Picks the winning sweep outcome under {@link #plansComparator()} (most customers served, ties broken by lowest
     * total cost).
     *
     * @throws IllegalStateException if the sweep produced no candidates at all
     */
    protected PlanList<P> getBestPlans(List<PlanList<P>> plans) {
        return plans.stream().min((o1, o2) -> plansComparator().compare(o1.plans(), o2.plans()))
                .orElseThrow(() -> new IllegalStateException("At least one list of plans must be available"));
    }

    /**
     * The greedy merge core. Walks {@code savings} in descending order (the list is mutated — each saving is removed
     * once handled); for each one it looks for the plan whose customer sequence <em>ends</em> at the saving's origin
     * and the plan that <em>begins</em> at its destination, concatenates them into a single route via
     * {@link #getNewPlan}, and commits the merge only if {@link TrackableAssignment#assign(List)} still succeeds on the
     * assignable subset (every plan holding a resource plus the merged one). On success the plan list is rebuilt in
     * place and every plan of that subset is re-optimized by local search. Plans without customers never take part in
     * a merge. Construction always completes: an {@link #interrupt() interrupt} does not stop the walk (stopping it
     * would leave unmerged plans the fleet cannot cover, and their customers would be dropped). A final pass tries to
     * assign a trackable to any
     * leftover plan (ordered by {@link #planComparator()}) and keeps only those that succeed, so unassignable plans are
     * dropped here ({@link #plan} returns dropped previous plans regardless).
     */
    protected List<P> mergePlans(List<P> plans, List<Saving> savings) {
        List<N> customers;
        // Walk the list once instead of removing its head each time: on an ArrayList every head removal shifts all
        // remaining savings, which made this loop quadratic in the number of savings.
        for (Saving saving : savings) {
            P firstPlan = null;
            P secondPlan = null;
            for (P plan : plans) {
                checkCancel();
                if (firstPlan != null && secondPlan != null) {
                    break;
                }
                customers = plan.getCustomers();
                if (customers.isEmpty()) {
                    continue;
                }
                if (customers.get(0).getCode().equals(saving.destination().getCode())) {
                    secondPlan = plan;
                    continue;
                }
                if (customers.get(customers.size() - 1).getCode().equals(saving.origin().getCode())) {
                    firstPlan = plan;
                }
            }
            if (firstPlan == null || secondPlan == null) {
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
                List<P> mergedPlans = tempPlans.stream()
                        .filter(p -> p.getTrackable() != null || p.equals(newPlan)).collect(Collectors.toList());
                boolean result = trackableAssignment.assign(mergedPlans);
                if (result) {
                    plans.clear();
                    mergedPlans.replaceAll(localSearchAlgorithm::apply);
                    plans.addAll(tempPlans.stream().filter(p -> p.getTrackable() == null && !p.equals(newPlan))
                            .toList());
                    plans.addAll(mergedPlans);
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
            if (trackableAssignment.assign(finalPlans, plan)) {
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
     * Computes every candidate saving for one fixed λ/β, sorted descending; ties go to the better plain (unblended)
     * saving, then to the origin code. Self-pairs and any pair touching the depot {@code origin} are excluded. Under
     * {@link OptimizationType#LOCATION_ZONE} pairs are only formed within a zone, so no saving can ever bridge two
     * zones. {@code beta <= 0} selects the plain (asymmetric) saving formula, otherwise the β-blended one.
     *
     * @param routeStarts where the route through each customer starts ({@code null} without an origin stop); see
     *                    {@link #getRouteStarts}
     * @param routeEnds   where the route through each customer ends ({@code null} for an open route); see
     *                    {@link #getRouteEnds}
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
     * descending-sorted list for a single combined merge pass. As above, pairs never cross a zone boundary.
     *
     * @param routeStarts where the route through each customer starts ({@code null} without an origin stop)
     * @param routeEnds   where the route through each customer ends ({@code null} for an open route)
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
     * ({@code routeStart == null}) has no start leg to save. λ scales the direct edge: λ &gt; 1 penalises merging
     * customers that are far apart (favouring more, shorter routes), λ &lt; 1 makes such merges more attractive. This
     * is the one place fractional arithmetic is allowed — the resulting plan costs remain integral.
     *
     * @param routeStart where {@code nodeJ}'s route starts, or {@code null} when it has no origin stop
     * @param routeEnd   where {@code nodeI}'s route ends, or {@code null} for an open route
     */
    protected double getSavingValue(N routeStart, N routeEnd, N nodeI, N nodeJ, double lambda) {
        double savedEnd = routeEnd == null ? 0 : costCalculation.calculateCost(nodeI, routeEnd);
        double savedStart = routeStart == null ? 0 : costCalculation.calculateCost(routeStart, nodeJ);
        return savedEnd + savedStart - (lambda * costCalculation.calculateCost(nodeI, nodeJ));
    }

    /**
     * The β variant used when {@code beta > 0}: {@code beta + max(s_ij, s_ji) + (1 − beta)·min(s_ij, s_ji)}, where
     * {@code s} is the λ-saving of each direction computed from its own route start and end. Note that {@code beta}
     * enters as an <em>additive</em> term, so the larger orientation always carries full weight and {@code beta} only
     * scales how much the smaller one contributes. Both orientations get the same value; {@link #getSavings} tries
     * the one with the better plain saving first.
     *
     * @param routeStartI where {@code nodeI}'s route starts, or {@code null} without an origin stop
     * @param routeStartJ where {@code nodeJ}'s route starts, or {@code null} without an origin stop
     * @param routeEndI   where {@code nodeI}'s route ends, or {@code null} for an open route
     * @param routeEndJ   where {@code nodeJ}'s route ends, or {@code null} for an open route
     */
    protected double getSavingValue(N routeStartI, N routeStartJ, N routeEndI, N routeEndJ, N nodeI, N nodeJ,
            double lambda, double beta) {
        double sij = getSavingValue(routeStartJ, routeEndI, nodeI, nodeJ, lambda);
        double sji = getSavingValue(routeStartI, routeEndJ, nodeJ, nodeI, lambda);
        return (beta + Math.max(sij, sji)) + ((1d - beta) * Math.min(sij, sji));
    }

    /**
     * The debug message logged with the final plans.
     */
    protected String getMessage(List<P> plans) {
        return "ClarkeWright " + plans.size() + " plans";
    }

    /**
     * Orders candidate plan sets: more customers served first, then lower total cost; the sweep keeps the first.
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
     * Propagates cancellation to the nested local search before cancelling itself.
     */
    @Override
    public void cancel() {
        localSearchAlgorithm.cancel();
        super.cancel();
    }

    /**
     * Propagates the graceful stop to the nested local search before interrupting itself.
     */
    @Override
    public void interrupt() {
        localSearchAlgorithm.interrupt();
        super.interrupt();
    }

    /**
     * Orders the leftover plans for the final assignment pass.
     */
    protected abstract Comparator<P> planComparator();

    /**
     * One seed plan per customer, each starting at {@code node}.
     */
    protected abstract List<P> getInitialPlans(N node, List<N> customers);

    /**
     * A plan with {@code nodes} as its route, taking its other attributes from {@code plan}.
     */
    protected abstract P getNewPlan(P plan, List<N> nodes);

    /**
     * A saving together with its plain (unblended) value, used only to order savings.
     */
    private record RankedSaving(Saving saving, double plain) {
    }

}
