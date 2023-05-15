# baho-algorithm

Vehicle-routing and plan-optimization heuristics for Java. The library is a framework of abstract algorithm templates: you implement the `Nodeable`, `Planable` and `Trackable` interfaces against your own entities and a `PathCostCalculation` against your cost source, and the library supplies the heuristics.

## What is included

- Clarke-Wright savings construction (`AbstractClarkeWrightAlgorithm`) with a λ/β parameter sweep and zone locality.
- 2-opt local search for closed tours (`AbstractTwoOptAlgorithm`).
- 2-opt variant for open routes (`AbstractOpenTwoOptAlgorithm`).
- resource-to-route assignment with relocate / exchange / reassign repair passes (`TrackableAssignment`).
- assignment for an unlimited fleet (`InfiniteTrackableAssignment`).
- cheapest and regret-k insertion of unrouted customers (`AbstractInsertionAlgorithm`).
- partitioning of routes into independent zone groups for parallel optimization (`ZoneSplitAlgorithm`).
- inter-route exchange and relocate moves (`AbstractExchangeAlgorithm`, `AbstractRelocateAlgorithm`).
- route merging when it lowers total cost (`AbstractMergeAlgorithm`).
- a pipeline skeleton and plan-code generation (`BaseAlgorithmRunner`, `DateTimePlanCodeGenerator`).
- an OSRM client and GeoJSON model for travel-time matrices (`RoutingProvider`).
- destroy-and-repair large-neighbourhood search (`AbstractDestroyRepairAlgorithm`).
- k-medoids re-seeding of routes (`AbstractKMedoidsAlgorithm`).
- balancing of customer counts between routes (`AbstractNodeBalanceAlgorithm`).
- splitting of stops too large for any resource (`AbstractSplitAlgorithm`, `AbstractBigNodeSplitAlgorithm`).
- balancing of capacity usage between routes (`AbstractCapacityBalanceAlgorithm`).
- un-merging of routes along zone boundaries (`AbstractSingleUnMergeAlgorithm`, `AbstractDoubleUnMergeAlgorithm`).
- partial-fleet re-planning (`AbstractPartialClarkeWrightAlgorithm`).
- pre-planning validation of stops (`ValidationChecker`).

## Requirements

- Java 17 or newer.
- Apache Maven 3.9.0 is downloaded automatically by the bundled Maven Wrapper; no local installation is needed.

## Maven dependency

```xml
<dependency>
    <groupId>ir.baho</groupId>
    <artifactId>baho-algorithm</artifactId>
    <version>2.1.0</version>
</dependency>
```

Runtime dependencies to add to your project: `org.slf4j:slf4j-api` and, for the `routing` package, `jackson-databind`. Lombok is used at compile time only.

## Build

```bash
./mvnw clean install
```

## Contracts the algorithms rely on

- Call `PathCostCalculation#initializeCostMatrix` with every node (depots and customers) before any algorithm runs.
- `copy()` must return a deep copy; moves are built on copies and accepted by comparing costs.
- `getCost()` must equal the sum of the consecutive edge costs of `getNodes()`.
- Inter-route algorithms mutate the plan list they are given; pass a mutable list you own.

## License

Apache License, Version 2.0 — see `LICENSE`.
