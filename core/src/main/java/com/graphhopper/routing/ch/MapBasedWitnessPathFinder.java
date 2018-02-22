/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.routing.ch;

import com.carrotsearch.hppc.IntObjectMap;
import com.carrotsearch.hppc.cursors.IntObjectCursor;
import com.graphhopper.coll.GHBitSetImpl;
import com.graphhopper.coll.GHIntObjectHashMap;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.CHGraph;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.EdgeIteratorState;

import java.util.BitSet;
import java.util.PriorityQueue;

public class MapBasedWitnessPathFinder extends WitnessPathFinder {
    private IntObjectMap<WitnessSearchEntry> chEntries;
    private BitSet settledEntries;
    private PriorityQueue<WitnessSearchEntry> priorityQueue;

    public MapBasedWitnessPathFinder(CHGraph graph, Weighting weighting, TraversalMode traversalMode, int maxLevel) {
        super(graph, weighting, traversalMode, maxLevel);
        doReset();
    }

    @Override
    protected void initEntries(IntObjectMap<WitnessSearchEntry> initialEntries) {
        for (IntObjectCursor<WitnessSearchEntry> e : initialEntries) {
            if (e.value.onOrigPath) {
                numOnOrigPath++;
                avoidNode = e.value.adjNode;
            }
            chEntries.put(e.key, e.value);
            priorityQueue.add(e.value);
        }
    }

    @Override
    public CHEntry getFoundEntry(int origEdge, int adjNode) {
        int edgeKey = getEdgeKey(origEdge, adjNode);
        return chEntries.get(edgeKey);
    }

    @Override
    public CHEntry getFoundEntryNoParents(int edge, int adjNode) {
        return getFoundEntry(edge, adjNode);
    }

    @Override
    public void findTarget(int targetEdge, int targetNode) {
        boolean targetDiscoveredByOrigPath = false;
        int targetKey = getEdgeKey(targetEdge, targetNode);
        if (settledEntries.get(targetKey)) {
            return;
        }

        while (!priorityQueue.isEmpty()) {
            WitnessSearchEntry currEdge = priorityQueue.peek();
            if (currEdge.incEdge == targetEdge && currEdge.adjNode == targetNode) {
                break;
            }

            currEdge = priorityQueue.poll();

            if (currEdge.onOrigPath) {
                numOnOrigPath--;
            }

            if (numOrigEdgesSettled > maxOrigEdgesSettled && !currEdge.onOrigPath) {
                continue;
            }

            EdgeIterator iter = outEdgeExplorer.setBaseNode(currEdge.adjNode);
            while (iter.next()) {
                // todo: increases number of shortcuts and not sure if needed
//                if (!currEdge.onOrigPath && iter.getAdjNode() == avoidNode) {
//                    continue;
//                }
                if ((!traversalMode.hasUTurnSupport() && iter.getFirstOrigEdge() == currEdge.incEdge) ||
                        isContracted(iter.getAdjNode())) {
                    continue;
                }
                double weight = weighting.calcWeight(iter, false, currEdge.incEdge) + currEdge.weight;
                if (Double.isInfinite(weight)) {
                    continue;
                }

                boolean possibleShortcut = currEdge.onOrigPath && iter.getBaseNode() == iter.getAdjNode();
                int traversalId = getEdgeKey(iter.getLastOrigEdge(), iter.getAdjNode());
                WitnessSearchEntry entry = chEntries.get(traversalId);
                if (entry == null) {
                    entry = createEntry(iter, currEdge, weight, possibleShortcut);
                    if (targetDiscoveredByOrigPath(targetEdge, targetNode, currEdge, iter)) {
                        targetDiscoveredByOrigPath = true;
                    }
                    chEntries.put(traversalId, entry);
                    priorityQueue.add(entry);
                } else if (entry.weight > weight) {
                    priorityQueue.remove(entry);
                    updateEntry(entry, iter, weight, currEdge, possibleShortcut);
                    if (targetDiscoveredByOrigPath(targetEdge, targetNode, currEdge, iter)) {
                        targetDiscoveredByOrigPath = true;
                    }
                    priorityQueue.add(entry);
                }
            }
            settledEntries.set(getEdgeKey(currEdge.incEdge, currEdge.adjNode));
            numOrigEdgesSettled++;
            if (numOnOrigPath < 1 && !targetDiscoveredByOrigPath) {
                break;
            }
        }
    }

    @Override
    protected void doReset() {
        initCollections();
    }

    private WitnessSearchEntry createEntry(EdgeIteratorState iter, CHEntry parent, double weight, boolean possibleShortcut) {
        WitnessSearchEntry entry = new WitnessSearchEntry(iter.getEdge(), iter.getLastOrigEdge(), iter.getAdjNode(), weight);
        entry.parent = parent;
        if (possibleShortcut) {
            entry.onOrigPath = true;
            numOnOrigPath++;
        }
        return entry;
    }

    private void updateEntry(WitnessSearchEntry entry, EdgeIteratorState iter, double weight, CHEntry parent, boolean possibleShortcut) {
        entry.edge = iter.getEdge();
        entry.incEdge = iter.getLastOrigEdge();
        entry.weight = weight;
        entry.parent = parent;
        if (possibleShortcut) {
            if (!entry.onOrigPath) {
                numOnOrigPath++;
            }
            entry.onOrigPath = true;
        }
    }

    private boolean targetDiscoveredByOrigPath(int targetEdge, int targetNode, WitnessSearchEntry currEdge, EdgeIteratorState iter) {
        return currEdge.onOrigPath && iter.getLastOrigEdge() == targetEdge && iter.getAdjNode() == targetNode;
    }

    private void initCollections() {
        // todo: tune initial collection sizes
        int size = Math.min(Math.max(200, graph.getNodes() / 10), 2000);
        priorityQueue = new PriorityQueue<>(size);
        chEntries = new GHIntObjectHashMap<>(size);
        settledEntries = new GHBitSetImpl(size);
    }

}
