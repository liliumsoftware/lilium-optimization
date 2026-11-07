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
/**
 * K-medoids re-seeding of an existing plan set.
 *
 * <p>{@link com.github.liliumsoftware.optimization.cluster.AbstractKMedoidsAlgorithm} rebuilds one seed plan around
 * the medoid of each input plan (see
 * {@link com.github.liliumsoftware.optimization.calculation.PathCostCalculation#getMedoid}), has the
 * {@link com.github.liliumsoftware.optimization.assignment.TrackableAssignment} serve the seeds, and inserts every
 * other customer into the nearest admissible seed through an
 * {@link com.github.liliumsoftware.optimization.insertion.AbstractInsertionAlgorithm}, repeating until the medoid set
 * stops changing. The number of clusters is the number of input plans. Customers a round cannot place are offered
 * again in later rounds, and the best round (most customers served, then lowest total cost) is returned; customers it
 * leaves out are recovered by diffing the result against the input. The caller's plans are never modified.
 */
package com.github.liliumsoftware.optimization.cluster;
