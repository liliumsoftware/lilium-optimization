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

package com.github.liliumsoftware.optimization.support;

import com.github.liliumsoftware.optimization.OptimizationType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Readable, hand-checkable routing problems shared by the algorithm tests.
 *
 * <p>Every fixture is small enough that a human can verify the expected routes and costs on paper: nodes sit
 * on a grid, distances are Manhattan, and coordinates are chosen so that pairwise distances are distinct
 * (avoiding ties whose resolution is an implementation detail rather than a contract).
 */
public final class Problems {

    private Problems() {
    }

    /**
     * Depot plus a single customer 10 units away; the only possible route.
     */
    public static Problem singleCustomer() {
        return new Problem("singleCustomer", TestNode.origin("O", 0, 0), null,
                List.of(TestNode.customer("A", 0, 10, 1)),
                List.of(new TestTrackable("T1", 10)));
    }

    // ---------------------------------------------------------------- degenerate sizes

    /**
     * Two customers on the same axis: O(0,0), A(0,10), B(0,30). Serving A then B costs 30; B then A costs 50.
     */
    public static Problem twoCustomers() {
        return new Problem("twoCustomers", TestNode.origin("O", 0, 0), null,
                List.of(TestNode.customer("A", 0, 10, 1), TestNode.customer("B", 0, 30, 1)),
                List.of(new TestTrackable("T1", 10)));
    }

    /**
     * No customers at all.
     */
    public static Problem noCustomers() {
        return new Problem("noCustomers", TestNode.origin("O", 0, 0), null,
                List.of(), List.of(new TestTrackable("T1", 10)));
    }

    /**
     * Three collinear customers with a closed return to the depot: O(0,0), A(0,10), B(0,20), C(0,30),
     * D(0,0) as the return depot. The unique cheapest closed tour is O-A-B-C-D at cost 30 + 30 = 60; any
     * other permutation is strictly worse (e.g. O-B-A-C-D = 20+10+20+30 = 80).
     */
    public static Problem collinearClosed() {
        return new Problem("collinearClosed", TestNode.origin("O", 0, 0), TestNode.destination("D", 0, 0),
                List.of(TestNode.customer("A", 0, 10, 1), TestNode.customer("B", 0, 20, 1),
                        TestNode.customer("C", 0, 30, 1)),
                List.of(new TestTrackable("T1", 10)));
    }

    // ---------------------------------------------------------------- hand-computable optimum

    /**
     * The same three collinear customers as an open path: O(0,0), A(0,10), B(0,20), C(0,30). The optimal open
     * route is O-A-B-C at cost 30; the worst is O-C-A-B = 30+20+10 = 60.
     */
    public static Problem collinearOpen() {
        return new Problem("collinearOpen", TestNode.origin("O", 0, 0), null,
                List.of(TestNode.customer("A", 0, 10, 1), TestNode.customer("B", 0, 20, 1),
                        TestNode.customer("C", 0, 30, 1)),
                List.of(new TestTrackable("T1", 10)));
    }

    /**
     * Two tight clusters 1000 units apart, each with two customers, and two single-customer-capacity-free
     * vehicles. The cheapest two-route split is obviously "one route per cluster"; merging across clusters
     * costs an extra ~2000.
     */
    public static Problem twoClusters() {
        return new Problem("twoClusters", TestNode.origin("O", 0, 0), null,
                List.of(TestNode.customer("A1", 10, 0, 1), TestNode.customer("A2", 20, 0, 1),
                        TestNode.customer("B1", 1000, 0, 1), TestNode.customer("B2", 1010, 0, 1)),
                List.of(new TestTrackable("T1", 10), new TestTrackable("T2", 10)));
    }

    /**
     * Four customers but every vehicle holds at most two, so no single-route solution can be feasible.
     */
    public static Problem capacityConstrained() {
        return new Problem("capacityConstrained", TestNode.origin("O", 0, 0), null,
                List.of(TestNode.customer("A", 0, 10, 1), TestNode.customer("B", 0, 20, 1),
                        TestNode.customer("C", 0, 30, 1), TestNode.customer("D", 0, 40, 1)),
                List.of(new TestTrackable("T1", 2), new TestTrackable("T2", 2)));
    }

    // ---------------------------------------------------------------- constraints

    /**
     * Demand that no single vehicle can carry and a fleet too small to split it: structurally infeasible.
     */
    public static Problem infeasible() {
        return new Problem("infeasible", TestNode.origin("O", 0, 0), null,
                List.of(TestNode.customer("A", 0, 10, 5), TestNode.customer("B", 0, 20, 5),
                        TestNode.customer("C", 0, 30, 5)),
                List.of(new TestTrackable("T1", 1)));
    }

    /**
     * Several customers at the very same coordinates — all inter-customer edges cost 0.
     */
    public static Problem identicalLocations() {
        return new Problem("identicalLocations", TestNode.origin("O", 0, 0), null,
                List.of(TestNode.customer("A", 7, 7, 1), TestNode.customer("B", 7, 7, 1),
                        TestNode.customer("C", 7, 7, 1)),
                List.of(new TestTrackable("T1", 10)));
    }

    // ---------------------------------------------------------------- pathological geometry

    /**
     * Customers sitting exactly on the depot: every edge costs 0, so no move can ever improve anything.
     */
    public static Problem customersAtDepot() {
        return new Problem("customersAtDepot", TestNode.origin("O", 0, 0), null,
                List.of(TestNode.customer("A", 0, 0, 1), TestNode.customer("B", 0, 0, 1)),
                List.of(new TestTrackable("T1", 10)));
    }

    /**
     * Coordinates of 10^9, so single edges stay within {@code int} range (compact cells) while any route through
     * the customers sums well beyond {@link Integer#MAX_VALUE}: accidental {@code int} arithmetic in cost
     * summation overflows here.
     */
    public static Problem largeCoordinates() {
        return new Problem("largeCoordinates", TestNode.origin("O", 0, 0), null,
                List.of(TestNode.customer("A", 1_000_000_000, 0, 1),
                        TestNode.customer("B", 0, 1_000_000_000, 1),
                        TestNode.customer("C", 1_000_000_000, 1_000_000_000, 1)),
                List.of(new TestTrackable("T1", 10)));
    }

    /**
     * Negative coordinates in all four quadrants — Manhattan distance must stay non-negative.
     */
    public static Problem negativeCoordinates() {
        return new Problem("negativeCoordinates", TestNode.origin("O", 0, 0), null,
                List.of(TestNode.customer("A", -10, -20, 1), TestNode.customer("B", -30, 5, 1),
                        TestNode.customer("C", 15, -7, 1)),
                List.of(new TestTrackable("T1", 10)));
    }

    /**
     * Three customers in three zones (Z1, Z2, Z3) with Z1 and Z2 declared mutual neighbours and Z3 isolated.
     */
    public static Problem zoned() {
        return new Problem("zoned", TestNode.origin("O", 0, 0), null,
                List.of(TestNode.customer("A", "Z1", 0, 10, 1), TestNode.customer("B", "Z2", 0, 20, 1),
                        TestNode.customer("C", "Z3", 500, 500, 1)),
                List.of(new TestTrackable("T1", 10), new TestTrackable("T2", 10), new TestTrackable("T3", 10)));
    }

    // ---------------------------------------------------------------- zones

    /**
     * Neighbour matrix matching {@link #zoned()}: Z1 &harr; Z2 (and each zone is its own neighbour), Z3 alone.
     */
    public static Map<String, List<String>> zonedNeighbors() {
        return Map.of("Z1", List.of("Z1", "Z2"), "Z2", List.of("Z2", "Z1"), "Z3", List.of("Z3"));
    }

    /**
     * A reproducible random instance. The seed fully determines the coordinates and demands, so any failure
     * is replayable by re-running with the same seed.
     */
    public static Problem random(long seed, int customerCount, int vehicleCount, int capacity, int span) {
        Random random = new Random(seed);
        List<TestNode> customers = new ArrayList<>();
        for (int i = 0; i < customerCount; i++) {
            customers.add(TestNode.customer("C" + i, random.nextInt(span) - span / 2,
                    random.nextInt(span) - span / 2, 1 + random.nextInt(3)));
        }
        List<TestTrackable> fleet = new ArrayList<>();
        for (int i = 0; i < vehicleCount; i++) {
            fleet.add(new TestTrackable("T" + i, capacity));
        }
        return new Problem("random(seed=" + seed + ")", TestNode.origin("O", 0, 0), null,
                Collections.unmodifiableList(customers), fleet);
    }

    // ---------------------------------------------------------------- generated instances

    /**
     * One planning instance: a depot, an optional return depot, the customers to serve, and the fleet.
     */
    public record Problem(String name, TestNode origin, TestNode destination,
                          List<TestNode> customers, List<TestTrackable> trackables) {

        /**
         * Every node the cost matrix must know about, in {@code ORIGIN, CUSTOMER…, DESTINATION} order.
         */
        public List<TestNode> allNodes() {
            List<TestNode> nodes = new ArrayList<>();
            nodes.add(origin);
            nodes.addAll(customers);
            if (destination != null) {
                nodes.add(destination);
            }
            return nodes;
        }

        /**
         * A cost calculation already initialized over {@link #allNodes()} — the state every algorithm requires.
         */
        public MatrixCostCalculation calculation() {
            MatrixCostCalculation calculation = new MatrixCostCalculation();
            calculation.initializeCostMatrix(allNodes());
            return calculation;
        }

        public MatrixCostCalculation calculation(OptimizationType type, Map<String, List<String>> neighbors) {
            MatrixCostCalculation calculation = new MatrixCostCalculation(type, neighbors);
            calculation.initializeCostMatrix(allNodes());
            return calculation;
        }

        public TestAssignment assignment() {
            return new TestAssignment(trackables);
        }

        /**
         * A plan laid out as {@code ORIGIN, customers…, DESTINATION} (the destination only when this problem
         * has one), with its cost left unset — callers recost through the cost calculation.
         */
        public TestPlan plan(String code, TestNode... planCustomers) {
            return plan(code, Arrays.asList(planCustomers));
        }

        public TestPlan plan(String code, List<TestNode> planCustomers) {
            List<TestNode> nodes = new ArrayList<>();
            nodes.add(origin);
            nodes.addAll(planCustomers);
            if (destination != null) {
                nodes.add(destination);
            }
            return new TestPlan(code, nodes);
        }

        public TestNode customer(String code) {
            return customers.stream().filter(c -> c.getCode().equals(code)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No customer " + code + " in " + name));
        }
    }

}
