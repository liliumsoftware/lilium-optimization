# lilium-optimization

Vehicle-routing and plan-optimization heuristics for Java, maintained
by [Lilium Software](https://github.com/liliumsoftware).

The library is a **framework of abstract algorithm templates**. You implement a handful of small interfaces against your
own entities (stops, routes, vehicles) and a cost source, and the library supplies construction, improvement, assignment
and partitioning heuristics that operate on them. The few concrete classes are self-contained utilities: a GeoJSON
model and an OSRM HTTP client for obtaining travel-time matrices, the zone splitter, a shelf bin-packing heuristic and
a date-time plan-code generator.

> This repository is the continuation of the former `baho-algorithm` library (`ir.baho.algorithm`). The package root is
> now `com.github.liliumsoftware.optimization`; see [Migration from baho-algorithm](#migration-from-baho-algorithm).

## Goals

- Correct, predictable heuristics with explicit contracts (what is mutated, what is copied, when a move is accepted).
- Domain independence: the library never depends on a persistence layer, a web framework or a specific routing engine.
- Deterministic behaviour so results are reproducible for a given input and configuration.
- Cooperative cancellation and graceful interruption of long-running searches.

## Features

- **Construction**: Clarke-Wright savings with a λ/β parameter sweep and zone locality, partial-fleet re-planning,
  k-medoids seeding, cheapest and regret insertion (the regret is summed over every plan; there is no `k`).
- **Improvement**: 2-opt for closed and open routes, inter-route exchange and relocate (segment moves with neighbour
  limits), destroy-and-repair (large neighbourhood search), route merging and zone-boundary un-merging, load balancing
  by customer count or capacity.
- **Resources**: assignment of vehicles/drivers to routes with relocate / exchange / reassign repair passes, multi-unit
  resources, unlimited fleets, second-dispatch (re-use after return), substitute lookup.
- **Pre-processing**: pre-planning validation, splitting of oversized stops against the smallest or biggest resource,
  partitioning into independent zone groups that are optimized in parallel against one shared fleet.
- **Cost model**: all-pairs cost matrix with nearest-neighbour ranking, zone predicates and medoids; costs are integral
  (e.g. milliseconds), stored as 32-bit cells when they fit and 64-bit cells otherwise.
- **Extras**: a shelf heuristic for packing 3-D boxes into a container, GeoJSON model classes, and an OSRM client
  (`/route`, `/table`).

## Algorithm catalog

| Package        | Class                                                                                  | Purpose                                                                                                          |
|----------------|----------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| `clarkewright` | `AbstractClarkeWrightAlgorithm`                                                        | Savings-based route construction; λ/β grid evaluated in parallel; per-zone parameters under `LOCATION_ZONE`.     |
| `clarkewright` | `AbstractPartialClarkeWrightAlgorithm`                                                 | Re-plans freed customers against a restricted part of the fleet to consolidate work.                             |
| `cluster`      | `AbstractKMedoidsAlgorithm`                                                            | Re-seeds plans around their medoids and re-inserts every other customer; repeats until the medoid set is stable. |
| `insertion`    | `AbstractInsertionAlgorithm`                                                           | Cheapest or regret insertion (regret summed over all plans) of unrouted customers into existing plans.           |
| `localsearch`  | `AbstractTwoOptAlgorithm`                                                              | First-improvement 2-opt on a closed tour.                                                                        |
| `localsearch`  | `AbstractOpenTwoOptAlgorithm`                                                          | 2-opt variant for open routes (destination fixed last; reversal plus two relocation moves).                      |
| `optimization` | `AbstractExchangeAlgorithm`                                                            | Swaps customer segments between two routes.                                                                      |
| `optimization` | `AbstractRelocateAlgorithm`                                                            | Moves a customer segment from one route to another, in either direction.                                         |
| `optimization` | `AbstractDestroyRepairAlgorithm`                                                       | Removes the worst-placed chain of customers and re-inserts them with the insertion algorithm.                    |
| `merge`        | `AbstractMergeAlgorithm`                                                               | Fuses two routes when that lowers total cost and resources can still be assigned.                                |
| `merge`        | `AbstractSingleUnMergeAlgorithm`, `AbstractDoubleUnMergeAlgorithm`                     | Separate routes along one or two zone boundaries.                                                                |
| `balance`      | `AbstractNodeBalanceAlgorithm`, `AbstractCapacityBalanceAlgorithm`                     | Even out customer counts or capacity usage between routes.                                                       |
| `assignment`   | `TrackableAssignment` (+ `Infinite…`, `SecondDispatch…`, `TrackableAlternativeFinder`) | Resource-to-route assignment and repair.                                                                         |
| `split`        | `AbstractSplitAlgorithm`, `AbstractBigNodeSplitAlgorithm`                              | Split stops that no single resource can serve.                                                                   |
| `split`        | `ZoneSplitAlgorithm`, `ZoneGroups`                                                     | Group routes/nodes into non-interacting zone groups; run an optimizer over the groups in parallel.               |
| `calculation`  | `PathCostCalculation`                                                                  | Cost matrix, closest-node ranking, zone predicates, medoids.                                                     |
| `validation`   | `ValidationChecker`                                                                    | Partition raw nodes into valid and invalid before planning.                                                      |
| `feasibility`  | `FeasibilityChecker`                                                                   | Per-route feasibility used inside every move (hot path).                                                         |
| `packing`      | `BinPacking`                                                                           | Shelf heuristic for 3-D boxes; reports placements and how many identical boxes fit.                              |
| `routing`      | `RoutingProvider`, GeoJSON model                                                       | OSRM client returning millisecond matrices, durations, distances and route geometry.                             |
| root           | `BaseAlgorithm`, `BaseAlgorithmRunner`                                                 | Cancel/interrupt signals; pipeline skeleton (initialize → plan → finalize).                                      |

## Requirements

|                                   | Minimum | Verified with                          |
|-----------------------------------|---------|----------------------------------------|
| Java (runtime and compile target) | **21**  | OpenJDK 25.0.4                         |
| Maven (via the wrapper)           | 3.9.9   | 3.10.0 (bundled wrapper configuration) |

Runtime dependencies: `org.slf4j:slf4j-api` (always), `tools.jackson.core:jackson-databind` 3.x (only if you use the
`routing` package; declared `optional`). Lombok is compile-time only.

## Maven dependency

The artifact is **not published to Maven Central** at this time. Build and install it locally, then depend on it:

```xml
<dependency>
    <groupId>com.github.liliumsoftware</groupId>
    <artifactId>lilium-optimization</artifactId>
    <version>3.0.1</version>
</dependency>
```

If you use `RoutingProvider` or the GeoJSON classes, also add `tools.jackson.core:jackson-databind` (3.x) to your
project.

## Installation

```bash
git clone https://github.com/liliumsoftware/lilium-optimization.git
cd lilium-optimization
./mvnw clean install          # Linux/macOS
mvnw.cmd clean install        # Windows
```

## Usage

Every algorithm is parameterized by three self-typed interfaces you implement:

| Interface           | Represents                                   | Must provide                                                                                       |
|---------------------|----------------------------------------------|----------------------------------------------------------------------------------------------------|
| `Nodeable<N>`       | a stop (`ORIGIN`, `CUSTOMER`, `DESTINATION`) | unique `getCode()`, `getType()`, optional `getZone()`, deep `copy()`, `equals` by value or by code |
| `Planable<P, N, T>` | route `[ORIGIN, ]CUSTOMER…[, DESTINATION]`   | unique `getCode()`, mutable `getNodes()`, `getTrackable()`, `getCost()`, deep `copy()`             |
| `Trackable<T>`      | a resource (vehicle, driver)                 | `getCode()`, deep `copy()`, `equals` by code and index; optionally `getCount()`, `isReturns()`     |

Then subclass `PathCostCalculation` (supply the all-pairs matrix), `TrackableAssignment` (decide whether a resource fits
a route) and the algorithm templates you need (supply the `getNewPlan`-style factory hooks). A complete, compiling set
of such implementations lives in the test sources under `src/test/java/com/github/liliumsoftware/optimization/support`
(`TestNode`, `TestPlan`, `TestTrackable`, `MatrixCostCalculation`, `TestAssignment`, `TestClarkeWright`, …).

Using those implementations, an end-to-end run looks like this (taken from `PlanningPipelineTest`):

```java
Problems.Problem problem = Problems.capacityConstrained();          // depot, customers, fleet
MatrixCostCalculation calculation = problem.calculation();          // initializeCostMatrix(...) already called
TestAssignment assignment = problem.assignment();

// 1. Construct routes with Clarke-Wright, improving each merged route with 2-opt.
List<TestPlan> plans = new ArrayList<>(new TestClarkeWright(calculation, assignment,
        new TestTwoOpt(calculation, new AlwaysFeasibleChecker()),
        problem.destination(), OptimizationType.NO_RESTRICTION)
        .plan(problem.origin(), problem.customers()));

// 2. Improve between routes.
TestInsertion insertion = new TestInsertion(calculation, new AlwaysFeasibleChecker(), false);
plans = new ArrayList<>(new TestExchange(calculation, assignment, new RecostingLocalSearch(calculation),
        insertion, ExchangeRelocateType.SIMPLE, 4, 2, true).apply(plans));
plans = new ArrayList<>(new TestRelocate(calculation, assignment, new RecostingLocalSearch(calculation),
        insertion, ExchangeRelocateType.TWO_OPT, 4, 2).apply(plans));
plans = new ArrayList<>(new TestDestroyRepair(calculation, assignment, new RecostingLocalSearch(calculation),
        insertion, 2, 0).apply(plans));
plans = new ArrayList<>(new TestMerge(calculation, assignment, new RecostingLocalSearch(calculation)).apply(plans));
```

Contracts every caller must respect:

- Call `PathCostCalculation#initializeCostMatrix` with **every** node (depots and customers) before any algorithm runs.
- The cost model is integer-valued by design: `calculateCost` and `getCost()` return `long`. Provider cells may be any
  `Number` (`int[][]` from OSRM, `Long[][]`, nested arrays); the matrix is kept as one flat `int[]` when every cell fits
  in 32 bits (enough for about 24.8 days in milliseconds or 2 147 483 km in metres per edge, 4 bytes per cell) and as a
  `long[]` otherwise (8 bytes per cell). Sums over edges are always `long`. Choose an integral unit such as milliseconds
  or metres; a fractional cell like `5.9` would be stored as `5`.
- `copy()` must be a deep copy; moves are built on copies and accepted by comparing costs, so a shallow copy corrupts
  the search.
- `getCost()` must equal the sum of the consecutive edge costs of the *current* `getNodes()`: several moves mutate the
  node list of a copy and compare `getCost()` directly, so a cost cached before the mutation corrupts the search.
- `FeasibilityChecker#getResult` must be free of side effects: it runs on the hot path and the library skips it for
  candidates that cannot win anyway (insertion only checks slots that beat the best one so far).
- `LocalSearchAlgorithm#apply` must not modify the plan it is given (return a new plan or the argument itself):
  destroy-repair passes it the plan objects of your list.
- Inter-route algorithms mutate the plan list you pass in; pass a mutable `ArrayList` you own. They build every move on
  copies and commit by replacing list elements, so the plan objects you passed in are not modified.
- Equality contract. `Planable#equals` may be value-based, code-based or identity-based (entities with
  framework-managed identity): every search loop terminates on accepted-move progress, never on plan equality, and
  plans are located by position or identity. Plan codes must be unique within a run (plans with the same code are
  treated as the same plan). A `Nodeable` copy must be `equals` to its source (by value or by code), because customers
  are still matched by `equals` when computing unplanned customers and medoids, and customer codes must be unique:
  batches with two customers sharing a code are rejected with `IllegalArgumentException`. `Trackable#equals` must tell
  units apart by code and index; a resource that was never expanded reports index `0`, the index of the first pool unit.
- Zones. Under `OptimizationType.LOCATION_ZONE` the neighbours matrix is taken literally: a zone is **not** its own
  neighbour unless its list names it (`Map.of("Z1", List.of("Z1", "Z2"))`). A `null` zone is unrestricted on either
  side of `isSameZone` / `isNeighbor`; the zone splitter groups two zones if either lists the other.
- Resource order. The list-based `TrackableAssignment` overloads offer each plan the pool sorted by `comparator(plan)`;
  `InfiniteTrackableAssignment` offers every plan the whole fleet, also sorted by `comparator(plan)`, and never
  withdraws a unit.
- Call `cancel()` to abort (throws `CancelException` inside the loops) or `interrupt()` to stop gracefully. Improvement
  algorithms (local search, exchange/relocate, destroy-repair, merge, balance, k-medoids) return their best result so
  far; construction (Clarke-Wright, insertion) always completes, so an interrupt never leaves customers unplanned. Both
  may be invoked from another thread.
- Overridden acceptance hooks (`compare…` in assignment, exchange/relocate and destroy-repair) must accept *strict*
  improvements only. Assignment repair and destroy-repair stop at a cap (100 000 moves / passes, logged at WARN);
  exchange/relocate restart after every accepted move and have no cap, so a hook that accepts ties can loop forever.

## Package structure

```
com.github.liliumsoftware.optimization
├── BaseAlgorithm, BaseAlgorithmRunner, OptimizationType
├── assignment      resource assignment and repair
├── balance         load balancing
├── calculation     cost matrix (CostCalculation, PathCostCalculation)
├── clarkewright    savings construction
├── cluster         k-medoids
├── domain          Nodeable, Planable, Trackable, Bin, Container, …
├── exception       CancelException, PlanningException, ObjectError
├── feasibility     FeasibilityChecker, FeasibilityResult
├── generator       plan code generation
├── insertion       cheapest / regret insertion
├── localsearch     2-opt
├── merge           merge / un-merge
├── optimization    exchange, relocate, destroy-repair
├── packing         bin packing
├── routing         OSRM client and GeoJSON model
├── split           node splitting, zone grouping
└── validation      pre-planning validation
```

## Algorithm complexity

n = customers, m = routes, k = `chain`, c = cost of one feasibility check. Figures describe one pass; most algorithms
restart after each accepted move and run to a local fixed point, so total time depends on how many improvements exist.

| Algorithm                                          | Per pass                                                                                                                 |
|----------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------|
| `PathCostCalculation#initializeCostMatrix`         | O(n² log n) time; O(n²) space: 4 or 8 bytes per matrix cell plus one ranking reference per cell                          |
| Clarke-Wright savings                              | O(n² log n) to build and sort savings; each saving finds its two routes in O(1); each merge attempt copies the plan list |
| Cheapest insertion of one customer                 | O(m · n · c) with a plan copy per probed slot                                                                            |
| 2-opt (closed/open)                                | O(n²) reversals per pass, each recosted in O(n)                                                                          |
| Exchange / relocate                                | O(m² · n² · k) candidate moves per pass, each copying all plans; `neighbors` prunes targets                              |
| Destroy-and-repair                                 | O(n · k) destroyed chains per pass, each repaired by insertion                                                           |
| Merge                                              | O(m²) candidate fusions per pass                                                                                         |
| Assignment repair (relocate / exchange / reassign) | O(m²) pairs per pass; repeats until no change                                                                            |
| `BinPacking#pack`                                  | O(b² · levels) for b boxes                                                                                               |
| `BinPacking#getCount`                              | O(log k) packings for an answer of k                                                                                     |

## Build

```bash
./mvnw clean verify              # compile, test, enforcer, JAR + sources + Javadoc JAR
./mvnw clean verify -Pcoverage   # additionally writes a JaCoCo report to target/site/jacoco
./mvnw javadoc:javadoc           # API docs under target/reports/apidocs
./mvnw checkstyle:check          # style check alone (it also runs in every build, in the validate phase)
./mvnw test -Pmutation           # + PIT mutation testing, report under target/pit-reports (takes several minutes)
```

The build enforces Maven ≥ 3.9.9 and JDK ≥ 21, dependency convergence, and complete Javadoc: every `doclint` check
runs, missing `@param`/`@return`/`@throws` included, and any Javadoc warning fails the build. Javadoc reads the sources
without running Lombok, so public API members are written out by hand (Lombok only generates loggers and
`InsertionCandidate`'s `equals`/`hashCode`/`toString`). Every violation of
`checkstyle.xml` fails the build, for main and test sources alike: the rules are Google's Checkstyle configuration
adapted to this project (4-space indentation, 120-column lines, imports grouped as third-party, then `java`/`javax`,
then static, no unused imports, Javadoc required on main sources; in tests only the fixtures' methods may go
undocumented). The compiler runs with every lint category except annotation processing (`-Xlint:all,-processing`).
`.editorconfig` carries the matching IntelliJ settings, so reformatting in IntelliJ keeps the build green. Archives are
reproducible (`project.build.outputTimestamp`).

## Testing

```bash
./mvnw test
./mvnw test -Dtest=ClarkeWrightAlgorithmTest
```

The suite (JUnit Jupiter 6, AssertJ) checks algorithm behaviour with an independent Manhattan-distance oracle,
hand-verifiable fixtures, seeded property sweeps, an end-to-end pipeline test and a loopback fake OSRM server. All tests
are deterministic. Mutation testing with PIT is available as the opt-in `mutation` profile (see Build).

## Maven Wrapper

The repository ships the Maven Wrapper 3.3.4 (`only-script` type: no binary JAR is committed). `./mvnw` downloads Apache
Maven 3.10.0 on first use into `~/.m2/wrapper` and verifies its SHA-256 (`distributionSha256Sum` in
`.mvn/wrapper/maven-wrapper.properties`). No global Maven installation is required.

To upgrade the wrapped Maven version, change `distributionUrl` and `distributionSha256Sum` together.

## Contributing

1. Fork and create a branch from `develop` named `feature/<short-description>` (or `hotfix/<short-description>` from
   `main`).
2. Keep one logical change per branch; include tests and Javadoc with the change.
3. Run `./mvnw clean verify` before opening a pull request.
4. Use single-line, Conventional-Commit style messages: `feat: …`, `fix: …`, `test: …`, `docs: …`, `build: …`,
   `refactor: …`, `chore: …`.
5. Public behaviour changes must update the README and Javadoc in the same change.

## Git Flow

| Branch          | Purpose                                                                                                |
|-----------------|--------------------------------------------------------------------------------------------------------|
| `main`          | released versions only; every commit on `main` is a release or hotfix merge and carries a `vX.Y.Z` tag |
| `develop`       | integration branch for the next release                                                                |
| `feature/*`     | one coherent feature, branched from and merged back into `develop`                                     |
| `release/X.Y.Z` | stabilization of a release, branched from `develop`, merged into `main` and back into `develop`        |
| `hotfix/*`      | fixes to a released version, branched from `main`, merged into `main` and `develop`                    |

## Release procedure

Releases use the [Maven Release Plugin](https://maven.apache.org/maven-release/maven-release-plugin/) on a Git Flow
release branch. The plugin is configured to tag as `vX.Y.Z`, to **not push** and to **not deploy**; those steps are
deliberate manual actions.

```bash
git checkout -b release/1.0.0 develop
./mvnw release:prepare          # checks the tree is clean, runs `clean verify`, sets 1.0.0 in the POM,
                                #  commits "chore: prepare release v1.0.0", tags v1.0.0, sets 1.0.1-SNAPSHOT
                                #  and commits "chore: prepare for next development iteration"
./mvnw release:perform          # checks out tag v1.0.0 from the local repository and runs `verify` on it
git checkout main && git merge --no-ff v1.0.0      # the tagged release, not the branch (it already holds the snapshot)
git checkout develop && git merge --no-ff release/1.0.0
git branch -d release/1.0.0
```

Pushing branches/tags and publishing artifacts to a repository require explicit configuration (`distributionManagement`,
credentials) that this project does not ship.

## Versioning

Semantic Versioning (`MAJOR.MINOR.PATCH`): a new backward-compatible capability bumps MINOR, a backward-compatible fix
bumps PATCH, a breaking public API change bumps MAJOR. Tags are `vX.Y.Z`. The latest release is `3.0.1`; development
continues on `develop` as `3.0.2-SNAPSHOT`.

## Migration from baho-algorithm

|                                                                                    | baho-algorithm                                                                                                      | lilium-optimization                                                                                                                                                                                                           |
|------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Maven coordinates                                                                  | `ir.baho:baho-algorithm`                                                                                            | `com.github.liliumsoftware:lilium-optimization`                                                                                                                                                                               |
| Package root                                                                       | `ir.baho.algorithm`                                                                                                 | `com.github.liliumsoftware.optimization`                                                                                                                                                                                      |
| `slf4j-api` scope                                                                  | `provided`                                                                                                          | `compile` (transitive)                                                                                                                                                                                                        |
| `jackson-databind` scope                                                           | `provided`                                                                                                          | `compile`, `optional`                                                                                                                                                                                                         |
| Compile target                                                                     | Java 25 (5.0.0-SNAPSHOT)                                                                                            | Java 21                                                                                                                                                                                                                       |
| `PathCostCalculation#getValue` / `getClosestNodeCostRank`                          | return `int`                                                                                                        | return `long` (cells may exceed the `int` range)                                                                                                                                                                              |
| `PathCostCalculation` matrix storage                                               | `Map<String, Map<String, C>>` of boxed cells, exposed as protected fields                                           | flat primitive `int[]`/`long[]` behind `calculateCost`/`getClosestNodes`; no direct access                                                                                                                                    |
| `BaseAlgorithmRunner#finalize(List)`                                               | `finalize(List)`                                                                                                    | `finalizePlans(List)`; `planModifiers` is an unmodifiable `List`                                                                                                                                                              |
| `ValidationChecker` getters, `AbstractBigNodeSplitAlgorithm#getSingleNodePlans()`  | mutable internal lists                                                                                              | unmodifiable lists / snapshot copies                                                                                                                                                                                          |
| Clarke-Wright β saving                                                             | `beta + max + (1−beta)·min`                                                                                         | `beta·max + (1−beta)·min` (convex blend; results for β > 0 change)                                                                                                                                                            |
| `RoutingProvider`                                                                  | new `HttpClient` per call                                                                                           | one shared client, `AutoCloseable`, optional caller-supplied client, request timeout (30 s default, configurable or off) covering the whole exchange, response body included                                                  |
| `AbstractTwoOptAlgorithm` on open routes                                           | last customer never moved                                                                                           | last customer takes part in reversals; a trailing destination still stays last                                                                                                                                                |
| Balance algorithms                                                                 | kept the candidate transfer with the highest total cost                                                             | keep the cheapest candidate transfer                                                                                                                                                                                          |
| Clarke-Wright savings                                                              | every route assumed to return to the origin                                                                         | computed from each route's real start and end (destination depot, closed tour by a returning fleet, or open route)                                                                                                            |
| Duplicate customer codes                                                           | planned twice                                                                                                       | rejected with `IllegalArgumentException` (Clarke-Wright, insertion, k-medoids)                                                                                                                                                |
| `RoutingProvider` input                                                            | any coordinate sent as is                                                                                           | coordinates validated (`IllegalArgumentException`), `null` cells and short tables raise `RoutingException`; the base URL must be an absolute `http`/`https` URL with a host (`IllegalArgumentException` from the constructor) |
| Zone groups                                                                        | parallel, resources could be booked twice                                                                           | parallel per group on its own units, then idle units in input order; deterministic                                                                                                                                            |
| `RoutingProvider` durations                                                        | seconds × 1000 truncated (32.3 s became 32 299 ms)                                                                  | rounded to the nearest millisecond; non-finite or out-of-range durations and distances raise `RoutingException`                                                                                                               |
| `Trackable#getIndex()` / `Bin#getIndex()` default                                  | `1`                                                                                                                 | `0`, the index of the first expanded unit                                                                                                                                                                                     |
| `PathCostCalculation#getMedoid`                                                    | summed `cost(c, c)` into each candidate's total                                                                     | sums the costs to the *other* customers only (differs only for a non-zero diagonal)                                                                                                                                           |
| `PathCostCalculation#getClosestNodeRank`, neighbour not in the matrix              | returned `-1`                                                                                                       | `IllegalStateException`, like every other query for an unknown code                                                                                                                                                           |
| 2-opt (`AbstractTwoOptAlgorithm`, `AbstractOpenTwoOptAlgorithm`) and `interrupt()` | ignored                                                                                                             | returns the best plan found so far                                                                                                                                                                                            |
| Destroy-repair candidates of equal rank                                            | hash order of the nodes (could vary between runs)                                                                   | plan order (deterministic)                                                                                                                                                                                                    |
| Clarke-Wright λ/β sweep, equally good outcomes                                     | winner by hash order of the λ/β pairs                                                                               | winner is the first combination of the configured grid                                                                                                                                                                        |
| `AbstractBigNodeSplitAlgorithm#split` called repeatedly                            | each call could carve against units an earlier call had consumed; a failing call kept the plans it had already made | consumed units stay consumed for the instance; a failing call records no plan                                                                                                                                                 |
| `DateTimePlanCodeGenerator#generate()`                                             | codes minted within one clock tick differed only in a 5-digit random suffix                                         | the timestamp of each code is moved past the previous one, so one instance never repeats a code                                                                                                                               |
| GeoJSON coordinate (`LngLatAltDeserializer`) errors                                | raw `NumberFormatException` / `IllegalArgumentException`; `{}` read as (0, 0)                                       | reported through Jackson (`DatabindException`); the object form needs `longitude` and `latitude`                                                                                                                              |
| `SecondDispatchTrackableAssignment#secondDispatchAndOthers`, plan without a unit   | `null` entered the pool and failed later                                                                            | `NullPointerException` naming the plan                                                                                                                                                                                        |
| `AbstractCapacityBalanceAlgorithm` shortfall overflow                              | wrapped silently                                                                                                    | `ArithmeticException`                                                                                                                                                                                                         |

Apart from the rows above, class and method names are unchanged; a consumer migrates by replacing the package prefix in
imports.

## Thread-safety

Every class in the library is safe to share between threads. The guarantees, by class family:

| Classes                                                                                                                      | Guarantee                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
|------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| All `Abstract*Algorithm` templates, `LocalSearchAlgorithm`, `ZoneSplitAlgorithm`, `BinPacking`, `TrackableAlternativeFinder` | No mutable instance state beyond the two signal flags below; one instance may run concurrently on independent inputs. The plan lists you pass in are mutated, so never share one input list between concurrent runs. The zone-group entry points (`apply(plans, maxSplitCount)`) run their groups in parallel (see `ZoneGroups`).                                                                                                                                                                                                                                                                                          |
| `DateTimePlanCodeGenerator`                                                                                                  | Thread-safe. It keeps the timestamp of the last code it generated in an atomic reference, so the codes one instance generates are distinct and increasing even when the clock has not advanced between two calls; share one instance per process.                                                                                                                                                                                                                                                                                                                                                                          |
| `BaseAlgorithm#cancel()` / `interrupt()`                                                                                     | Volatile flags, safe to raise from any thread. They are scoped to the instance: cancelling stops every run in progress on that instance, and the flags are never reset. Use one set of algorithm instances per planning run when runs must be cancellable independently (this is what `BaseAlgorithmRunner` subclasses normally do).                                                                                                                                                                                                                                                                                       |
| `AbstractPartialClarkeWrightAlgorithm`                                                                                       | Its restricted resource pool is per call and is handed explicitly to the parallel λ/β sweep workers; concurrent `apply` calls on one instance cannot see each other's pool.                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `PathCostCalculation`                                                                                                        | The matrix and the ranking are built privately and published together as one immutable snapshot through a single volatile reference. `initializeCostMatrix` may run at any time while other threads query; a single query (one edge cost, one ranking) sees the complete old state or the complete new one, never a mix. A query that reads several edges (`calculateCost(plan)`, `getMedoid`, `calculateMedoidToOthersCost`) reads the snapshot per edge, so re-initializing while it runs may mix both matrices or fail on a code the new matrix lacks: do not re-initialize while algorithms are using the calculation. |
| `TrackableAssignment`                                                                                                        | Each `assign…` call copies the pool into a private working list; the pool reference is volatile and the second-dispatch mutators are synchronized. `getTrackables()` is unmodifiable. Zone groups run against one fleet through `ZoneGroups`: first in parallel, each group limited to the units its own plans hold, then one after the other in input order with access to the idle units, so no unit is ever booked twice and the result does not depend on thread timing.                                                                                                                                               |
| `ValidationChecker`, `AbstractBigNodeSplitAlgorithm`                                                                         | Stateful by design (they publish results through getters). Entry points are synchronized, results are built completely before publication, and the getters return unmodifiable lists / snapshots; `ValidationChecker` publishes its valid and invalid lists together through one volatile reference, and `AbstractBigNodeSplitAlgorithm` records the plans of a call only once the call has completed and never hands out a unit consumed by an earlier call. Use one instance per run when you need several results side by side.                                                                                         |
| `RoutingProvider`                                                                                                            | One thread-safe `HttpClient` per provider, per-thread coordinate formatter; usable from any thread. `AutoCloseable`; a caller-supplied client is never closed by the provider. Every call is bounded by a request timeout covering the whole exchange, response body included: 30 s by default, any duration you pass, or none with `NO_REQUEST_TIMEOUT`. Response bodies are read into memory without a size limit, so point it only at a routing server you trust.                                                                                                                                                       |
| GeoJSON model (`Point`, `LngLatAlt`, `Geometry`, `Crs`, …), `InsertionCandidate`, records                                    | Value objects. Records are immutable; `InsertionCandidate` (Lombok `@Data`, final fields) is shallowly immutable — the plans it references are mutable. The GeoJSON beans have setters for Jackson binding: treat them as immutable after construction/deserialization, or confine mutation to one thread.                                                                                                                                                                                                                                                                                                                 |

Your own `Nodeable`/`Planable`/`Trackable` implementations must honour the same rule: the library mutates only the
copies it creates through `copy()`, so `copy()` must return an independent object.

## Known limitations

- Heuristics, not exact methods: results are local optima and depend on input order and parameters.
- `RoutingProvider` targets the OSRM HTTP API response shape; other routing engines need their own client. It reads
  responses without a size limit; use it with a routing server you trust.
- Clarke-Wright merges every saving whose routes can still be assigned a resource, including zero and negative savings:
  fewer routes are preferred over cost (λ and β only change the order in which merges are tried). With open routes,
  routes without an origin stop, λ > 1 or non-metric matrices a merge may therefore raise the total cost.
- Clarke-Wright with previous plans: a previous plan that holds a resource but can no longer be re-assigned makes every
  merge attempt fail, so new customers stay on single-stop plans and those the free units cannot take are left out.
- K-medoids returns the last round's plans; a customer a round could not place is not offered again in later rounds,
  so the result may serve fewer customers than its input (recover them with `getUnplannedNodes`).

## Documentation

- Javadoc: `./mvnw javadoc:javadoc` → `target/reports/apidocs/index.html`

## License

Apache License, Version 2.0 — see `LICENSE`.
