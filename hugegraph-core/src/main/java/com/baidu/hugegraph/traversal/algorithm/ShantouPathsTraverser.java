/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.baidu.hugegraph.traversal.algorithm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;

import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.slf4j.Logger;

import com.baidu.hugegraph.HugeException;
import com.baidu.hugegraph.HugeGraph;
import com.baidu.hugegraph.backend.id.Id;
import com.baidu.hugegraph.structure.HugeEdge;
import com.baidu.hugegraph.structure.HugeVertex;
import com.baidu.hugegraph.type.define.Directions;
import com.baidu.hugegraph.util.CollectionUtil;
import com.baidu.hugegraph.util.E;
import com.baidu.hugegraph.util.Log;
import com.google.common.collect.ImmutableList;

public class ShantouPathsTraverser extends HugeTraverser {

    private static final Logger LOG = Log.logger(ShantouPathsTraverser.class);

    private static final String HAS_VUNCORE_ATTR = "has_vnoncore_attr";
    private static final String HAS_UNCORE_ATTR = "has_noncore_attr";

    public ShantouPathsTraverser(HugeGraph graph) {
        super(graph);
    }

    public List<Path> paths(List<String> mentionValues) {
        // Query mentions vertices
        List<Vertex> mentions = this.mentions(mentionValues);
        if (mentions.isEmpty()) {
            return ImmutableList.of();
        }

        // Step 1: Query entities and filter
        NodeMap<Vertex> entities = this.entities(mentions);

        Traverser traverser = new Traverser(graph(), entities);

        // Step 2: Traverse from vcore to vbsku
        List<Path> paths = traverser.vcore2Vbsku();
        if (paths != null) {
            return paths;
        }

        // TODO: to implement step 3 here

        // Step 4: Traverse from vbsku to category
        traverser.vbsku2category();


        // TODO: to implement step 5 from here
        // From vbsku to bsku to core/noncore
//        traverser.backward();

        // Merge paths by category
//        traverser.merge();

        // Calculate weight
//        traverser.calculateWeight();

        return traverser.results();
    }

    /**
     * Query mentions to filter out not valid mentions
     * @param mentions, mention strings
     * @return exist mention vertices list
     */
    private List<Vertex> mentions(List<String> mentions) {
        return this.graph().traversal().V()
                   .hasLabel("mention")
                   .has("value", P.within(mentions))
                   .toList();
    }

    /**
     * Mentions to entities, entities may be category, vbsku, bsku, vcore_attr,
     * vnoncore_attr, core_attr and noncore_attr.
     * @param mentions
     * @return entities and its paths
     */
    private NodeMap<Vertex> entities(List<Vertex> mentions) {
        NodeMap<Vertex> entities = newNodeMap();
        for (Vertex mention : mentions) {
            Id id = ((HugeVertex) mention).id();
            List<Vertex> outs = this.graph().traversal().V(id).out().toList();
            for (Vertex vertex : outs) {
                Id entityId = ((HugeVertex) vertex).id();
                entities.add(vertex, new Node(entityId, new Node(id, null)));
            }
        }
        return filterEntities(entities);
    }

    private static NodeMap<Vertex> filterEntities(NodeMap<Vertex> entitiesMap) {
        Map<String, Vertex> keyMap = new HashMap<>();
        for (Vertex vertex : entitiesMap.keySet()) {
            String key = vertex.value("key");
            if (keyMap.containsKey(key)) {
                String newValue = vertex.value("value");
                String existValue = keyMap.get(key).value("value");
                if (newValue.length() <= existValue.length()) {
                    continue;
                }
            }
            keyMap.put(key, vertex);
        }
        NodeMap<Vertex> entities = newNodeMap();
        for (Vertex vertex : keyMap.values()) {
            entities.put(vertex, entitiesMap.get(vertex));
        }
        return entities;
    }

    private static NodeMap<Vertex> extractEntities(NodeMap<Vertex> all,
                                                   String label) {
        NodeMap<Vertex> entities = newNodeMap();
        for (Map.Entry<Vertex, List<Node>> entry : all.entrySet()) {
            Vertex vertex = entry.getKey();
            if (vertex.label().equals(label)) {
                entities.put(vertex, entry.getValue());
            }
        }
        return entities;
    }

    private static <V> NodeMap<V> newNodeMap() {
        return new NodeMap<>();
    }

    private class Traverser {

        private NodeMap<Vertex> vcores;
        private NodeMap<Vertex> vnoncores;
        private NodeMap<Vertex> cores;
        private NodeMap<Vertex> noncores;
        private NodeMap<Vertex> categories;
        private HugeGraph graph;
        private Map<Id, List<Path>> paths;
        private List<Path> results;

        private Traverser(HugeGraph graph, NodeMap<Vertex> entities) {
            this.vcores = extractEntities(entities, "vcore_attr");
            this.vnoncores = extractEntities(entities, "vnoncore_attr");
            this.cores = extractEntities(entities, "core_attr");
            this.noncores = extractEntities(entities, "noncore_attr");
            this.categories = extractEntities(entities, "category");
            this.graph = graph;
            this.results = new ArrayList<>();
        }

        private List<Path> results() {
            return this.results;
        }

        private List<Path> vcore2Vbsku() {
            if (this.vcores.isEmpty()) {
                if(this.categories.isEmpty()) {
                    return ImmutableList.of();
                }
                this.paths = entities2Paths(this.categories);
                this.forward(ForwardType.IS_SUBCATEGORY);
                this.forward(ForwardType.IS_SUBCATEGORY);
                this.forward(ForwardType.IS_TRADE);
                this.forward(ForwardType.IS_ROOT);
                return this.mergeCategoryAndCalc();
            }
            forward(ForwardType.HAS_VCORE_ATTR);
            return null;
        }

        private void vbsku2category() {
            this.forward(ForwardType.IS_CATEGORY);
            this.forward(ForwardType.IS_SUBCATEGORY);
            this.forward(ForwardType.IS_SUBCATEGORY);
            this.forward(ForwardType.IS_TRADE);
            this.forward(ForwardType.IS_ROOT);
            this.vbskuIntersect();
        }

        private void vbskuIntersect() {
            E.checkState(this.paths.size() == 1,
                         "All paths should cross in one root, " +
                         "but got roots '%s'", this.paths.keySet());

            MultivaluedMap<Id, Path> forwardPaths = newMultivalueMap();
            Set<Path> categoryPaths = new HashSet<>();
            // classify by crosspoint
            for (List<Path> paths : this.paths.values()) {
                for (Path path : paths) {
                    Id crosspoint = path.crosspoint();
                    if (crosspoint == null) {
                        categoryPaths.add(path);
                    } else {
                        forwardPaths.add(crosspoint, path);
                    }
                }
            }

            List<Path> results = new ArrayList<>();
            for (List<Path> paths : forwardPaths.values()) {
                // Get union of vbsku by vcore under same category
                Map<Id, Path> union = new HashMap<>();
                for (Path path : paths) {
                    Id vcore = path.vcoreAttr();
                    Path exist = union.get(vcore);
                    if (exist != null) {
                        // merge
                        exist.vbskus(path.vbskus());
                    } else {
                        union.put(vcore, path);
                    }
                }

                Path result = null;
                // Get intersect of vbsku of all vcores under same category
                Collection<Id> vbskus = null;
                for (Path path : union.values()) {
                    if (vbskus == null) {
                        result = path.cloneCategory();
                        vbskus = new HashSet<>(path.vbskus());
                    } else {
                        CollectionUtil.intersectWithModify(vbskus,
                                                           path.vbskus());
                    }
                    if (vbskus.isEmpty()) {
                        break;
                    }
                }
                E.checkNotNull(result, "result");
                result.vbskus((Set<Id>) vbskus);

                for (Path path : union.values()) {
                    result.vcoreAttrs(path.vcoreAttrs());
                    result.mentions(path.mentions());
                }

                results.add(result);
            }
            results.addAll(categoryPaths);

            this.results = this.mergeCategory(results);
        }

        private List<Path> mergeCategoryAndCalc() {
            E.checkState(this.paths.size() == 1,
                         "All paths should cross in one root, " +
                                 "but got roots '%s'", this.paths.keySet());
            List<Path> paths = new ArrayList<>();
            for (List<Path> pathList : this.paths.values()) {
                paths.addAll(pathList);
            }

            List<Path> results = this.mergeCategory(paths);

            for (Path path : results) {
                path.weight(path.mentions().size() * 0.3);
            }
            results.sort((p1, p2) -> Double.compare(p2.weight(), p1.weight()));
            // TODO：Add core, noncore, vnoncore to paths
            return results;
        }

        private List<Path> mergeCategory(List<Path> paths) {
            paths.sort(Comparator.comparingInt(p -> p.categories().size()));
            int size = paths.size();
            List<Path> results = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                if (i == size - 1) {
                    results.add(paths.get(i));
                }
                boolean merged = false;
                for (int j = i + 1; j < size; j++) {
                    Path pathI = paths.get(i);
                    Path pathJ = paths.get(j);
                    if (pathJ.categories().containsAll(pathI.categories())) {
                        merged = true;
                        pathJ.mentions().addAll(pathI.mentions());
                    }
                }
                if (!merged) {
                    results.add(paths.get(i));
                }
            }
            return results;
        }

        private void forward(ForwardType type) {
            LOG.debug("Forward with type {}", type);
            Id label = this.graph.edgeLabel(type.label()).id();
            MultivaluedMap<Id, Path> results = newMultivalueMap();
            for (Map.Entry<Id, List<Path>> entry : this.paths.entrySet()) {
                Id source = entry.getKey();
                Iterator<Edge> edges = edgesOfVertex(source, type.direction(),
                                                     label, NO_LIMIT);

                // If no edges of 'type', put current entry for next 'type'
                if (!edges.hasNext()) {
                    results.addAll(entry.getKey(), entry.getValue());
                    continue;
                }

                // Path forward
                while (edges.hasNext()) {
                    HugeEdge edge = (HugeEdge) edges.next();
                    Id target = edge.id().otherVertexId();
                    for (Path p : entry.getValue()) {
                        results.add(target, p.forward(source, type, target));
                    }
                }
            }
            this.paths = results;
        }


        private void backward() {
            LOG.debug("Backward from vbsku/bsku to noncore attrs");
            Set<Id> noncores = this.noncores.keySet().stream()
                                  .map(vertex -> ((HugeVertex) vertex).id())
                                  .collect(Collectors.toSet());
            Id vnoncoreLabel = this.graph.edgeLabel(HAS_VUNCORE_ATTR).id();
            Id noncoreLabel = this.graph.edgeLabel(HAS_UNCORE_ATTR).id();
            Iterator<Edge> edges;
            for (Map.Entry<Id, List<Path>> entry : this.paths.entrySet()) {
                for (Path path : entry.getValue()) {
                    for (Id vbsku : path.vbskus()) {
                        edges = edgesOfVertex(vbsku, Directions.OUT,
                                              vnoncoreLabel, NO_LIMIT);
                        while (edges.hasNext()) {
                            HugeEdge edge = (HugeEdge) edges.next();
                            Id target = edge.id().otherVertexId();
                            if (noncores.contains(target)) {
                                path.vnoncoreAttrs(target);
                            }
                        }
                    }
                    for (Id bsku : path.bskus()) {
                        edges = edgesOfVertex(bsku, Directions.OUT,
                                              noncoreLabel, NO_LIMIT);
                        while (edges.hasNext()) {
                            HugeEdge edge = (HugeEdge) edges.next();
                            Id target = edge.id().otherVertexId();
                            if (noncores.contains(target)) {
                                path.noncoreAttrs(target);
                            }
                        }
                    }
                }
            }
        }

        private Map<Id, List<Path>> entities2Paths(NodeMap<Vertex> cores) {
            Map<Id, List<Path>> paths = new HashMap<>();
            for (Map.Entry<Vertex, List<Node>> entry : cores.entrySet()) {
                HugeVertex vertex = (HugeVertex) entry.getKey();
                List<Path> pathList = new ArrayList<>();
                for (Node node : entry.getValue()) {
                    Path path = new Path(node);
                    pathList.add(path);
                }
                paths.put(vertex.id(), pathList);
            }
            return paths;
        }

        private void merge() {
            E.checkState(this.paths.size() == 1,
                         "All paths should cross in one root, " +
                         "but got roots '%s'", this.paths.keySet());
            LOG.debug("Merge paths by crosspoint category for root {}",
                      this.paths.keySet().iterator().next());
            Map<Id, Path> forwardPaths = new HashMap<>();
            Set<Path> categoryPaths = new HashSet<>();
            for (List<Path> paths : this.paths.values()) {
                for (Path path : paths) {
                    Id crosspoint = path.crosspoint();
                    if (crosspoint == null) {
                        categoryPaths.add(path);
                        continue;
                    }
                    if (forwardPaths.containsKey(crosspoint)) {
                        Path exist = forwardPaths.get(crosspoint);
                        exist.merge(path);
                        forwardPaths.put(crosspoint, exist);
                    } else {
                        forwardPaths.put(crosspoint, path);
                    }
                }
            }
            // Add categories directly accessed by mentions
            for (Path path : forwardPaths.values()) {
                for (Path cp : categoryPaths) {
                    if (path.categories().containsAll(cp.categories())) {
                        path.mentions().addAll(cp.mentions());
                    }
                }
            }
            this.results = new ArrayList<>(forwardPaths.values());
        }

        private void calculateWeight() {
            LOG.debug("Calculate weight for paths {}", this.results);
            NodeMap<Id> paths = newNodeMap();
            for (Map.Entry<Vertex, List<Node>> entry : noncores.entrySet()) {
                paths.put(((HugeVertex) entry.getKey()).id(), entry.getValue());
            }
            for (Path path : this.results) {
                Set<Id> mentions = new HashSet<>();
                for (Id id : path.noncores()) {
                    List<Node> nodes = paths.get(id);
                    mentions.addAll(nodes.stream().map(n -> n.path().get(0))
                                         .collect(Collectors.toSet()));
                }
                int coreMentionNum = path.mentions().size();
                int noncoreMentionNum = mentions.size();
                path.weight(coreMentionNum * 0.3 + noncoreMentionNum * 0.01);
            }
            this.results.sort(((p1, p2) -> {
                return Double.compare(p2.weight(), p1.weight());
            }));
        }
    }

    public static class Path implements Cloneable {

        private Node node;
        private Id crosspoint;
        private Id root;
        private Id trade;
        private List<Id> categories;
        private Set<Id> vbskus;
        private Set<Id> bskus;
        private Set<Id> vcoreAttrs;
        private Set<Id> vnoncoreAttrs;
        private Set<Id> coreAttrs;
        private Set<Id> noncoreAttrs;
        private Set<Id> mentions;
        private double weight;

        public Path(Node node) {
            this.node = node;
            this.categories = new ArrayList<>();
            this.vbskus = new HashSet<>();
            this.bskus = new HashSet<>();
            this.vcoreAttrs = new HashSet<>();
            this.vnoncoreAttrs = new HashSet<>();
            this.coreAttrs = new HashSet<>();
            this.noncoreAttrs = new HashSet<>();
            this.mentions = new HashSet<>(Arrays.asList(node.path().get(0)));
        }

        public Node node() {
            return this.node;
        }

        public void node(Node node) {
            this.node = node;
        }

        public Id crosspoint() {
            return this.crosspoint;
        }

        public void crosspoint(Id crosspoint) {
            this.crosspoint = crosspoint;
            this.categories(crosspoint);
        }

        public Id root() {
            return this.root;
        }

        public void root(Id root) {
            this.root = root;
        }

        public Id trade() {
            return this.trade;
        }

        public void trade(Id trade) {
            this.trade = trade;
        }

        public List<Id> categories() {
            return this.categories;
        }

        public void categories(Id category) {
            if (!this.categories.contains(category)) {
                this.categories.add(0, category);
            }
        }

        public Set<Id> vbskus() {
            return this.vbskus;
        }

        public void vbskus(Id vbsku) {
            this.vbskus.add(vbsku);
        }

        public void vbskus(Set<Id> vbskus) {
            this.vbskus.addAll(vbskus);
        }

        public Set<Id> bskus() {
            return this.bskus;
        }

        public void bskus(Id bsku) {
            this.bskus.add(bsku);
        }

        public Set<Id> vcoreAttrs() {
            return this.vcoreAttrs;
        }

        public Id vcoreAttr() {
            E.checkState(this.vcoreAttrs.size() == 1,
                         "vcore_attrs of one path before merging must only " +
                         "have one, but got '%s'", this.vcoreAttrs.size());
            return this.vcoreAttrs.iterator().next();
        }

        public void vcoreAttrs(Id vcoreAttr) {
            this.vcoreAttrs.add(vcoreAttr);
        }

        public void vcoreAttrs(Set<Id> vcoreAttrs) {
            this.vcoreAttrs.addAll(vcoreAttrs);
        }

        public Set<Id> vnoncoreAttrs() {
            return this.vnoncoreAttrs;
        }

        public void vnoncoreAttrs(Id vnoncoreAttr) {
            this.vnoncoreAttrs.add(vnoncoreAttr);
        }

        public void vnoncoreAttrs(Set<Id> vnoncoreAttrs) {
            this.vnoncoreAttrs.addAll(vnoncoreAttrs);
        }

        public Set<Id> coreAttrs() {
            return this.coreAttrs;
        }

        public void coreAttrs(Id coreAttr) {
            this.coreAttrs.add(coreAttr);
        }

        public void coreAttrs(Set<Id> coreAttrs) {
            this.coreAttrs.addAll(coreAttrs);
        }

        public Set<Id> noncoreAttrs() {
            return this.noncoreAttrs;
        }

        public void noncoreAttrs(Id noncoreAttr) {
            this.noncoreAttrs.add(noncoreAttr);
        }

        public void noncoreAttrs(Set<Id> noncoreAttrs) {
            this.noncoreAttrs.addAll(noncoreAttrs);
        }

        public Set<Id> mentions() {
            return this.mentions;
        }

        public void mentions(Id mention) {
            this.mentions.add(mention);
        }

        public void mentions(Set<Id> mention) {
            this.mentions.addAll(mentions);
        }

        public double weight() {
            return this.weight;
        }

        public void weight(double weight) {
            this.weight = weight;
        }

        public Set<Id> vertices() {
            Set<Id> vertices = new HashSet<>();
            vertices.add(this.root);
            vertices.add(this.trade);
            vertices.addAll(this.categories);
            vertices.addAll(this.vbskus);
            vertices.addAll(this.bskus);
            vertices.addAll(this.vcoreAttrs);
            vertices.addAll(this.vnoncoreAttrs);
            vertices.addAll(this.coreAttrs);
            vertices.addAll(this.noncoreAttrs);
            vertices.addAll(this.mentions);
            return vertices;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> path = new LinkedHashMap<>();
            path.put("root", this.root);
            path.put("trade", this.trade);
            path.put("categories", this.categories);
            path.put("vbskus", this.vbskus);
            path.put("bskus", this.bskus);
            path.put("vcoreAttrs", this.vcoreAttrs);
            path.put("vnoncoreAttrs", this.vnoncoreAttrs);
            path.put("coreAttrs", this.coreAttrs);
            path.put("noncoreAttrs", this.noncoreAttrs);
            path.put("mentions", this.mentions);
            path.put("weight", this.weight);
            return path;
        }

        private void merge(Path path) {
            E.checkState(this.crosspoint.equals(path.crosspoint()),
                         "Only path with same crosspoint can merge," +
                         "but got path '%s' with crosspoint '%s' " +
                         "and path '%s' with crosspoint '%s'",
                         this, this.crosspoint, path, path.crosspoint());
            E.checkState(this.categories.size() == path.categories.size() &&
                         this.categories.containsAll(path.categories),
                         "Expected same categories to merge, but got '%s' " +
                         "and '%s'", this.categories, path.categories);
            // Get intersection
            this.vbskus = new HashSet<>(CollectionUtil.intersect(this.vbskus,
                                                                 path.vbskus));
            this.bskus = new HashSet<>(CollectionUtil.intersect(this.bskus,
                                                                path.bskus));
            // Get union
            this.vcoreAttrs.addAll(path.vcoreAttrs);
            this.vnoncoreAttrs.addAll(path.vnoncoreAttrs);
            this.coreAttrs.addAll(path.coreAttrs);
            this.noncoreAttrs.addAll(path.noncoreAttrs);
            this.mentions.addAll(path.mentions);
        }

        private void vcoreMerge(Path path) {
            this.vbskus(path.vbskus());
            this.mentions(path.mentions());
        }

        private Path forward(Id source, ForwardType type, Id target) {
            Path path = this.clone();
            path.node(new Node(target, this.node()));
            switch (type) {
                case HAS_CORE_ATTR:
                    path.coreAttrs(source);
                    path.bskus(target);
                    break;
                case HAS_VCORE_ATTR:
                    path.vcoreAttrs(source);
                    path.vbskus(target);
                    break;
                case IS_INSTANCE:
                    path.bskus(source);
                    path.vbskus(target);
                    break;
                case IS_CATEGORY:
                    path.vbskus(source);
                    // Found potential crosspoint category
                    path.crosspoint(target);
                    break;
                case IS_SUBCATEGORY:
                    path.categories(source);
                    path.categories(target);
                    break;
                case IS_TRADE:
                    path.categories(source);
                    path.trade(target);
                    break;
                case IS_ROOT:
                    path.trade(source);
                    path.root(target);
                    break;
            }
            return path;
        }

        private Set<Id> noncores() {
            return CollectionUtil.union(this.vnoncoreAttrs, this.noncoreAttrs);
        }

        @Override
        protected Path clone() {
            Path path;
            try {
                path = (Path) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new HugeException("Failed to clone Path", e);
            }
            path.categories = new ArrayList<>(this.categories);
            path.vbskus = new HashSet<>(this.vbskus);
            path.bskus = new HashSet<>(this.bskus);
            path.vcoreAttrs = new HashSet<>(this.vcoreAttrs);
            path.vnoncoreAttrs = new HashSet<>(this.vnoncoreAttrs);
            path.coreAttrs = new HashSet<>(this.coreAttrs);
            path.noncoreAttrs = new HashSet<>(this.noncoreAttrs);
            path.mentions = new HashSet<>(this.mentions);
            return path;
        }

        private Path cloneCategory() {
            Path path;
            try {
                path = (Path) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new HugeException("Failed to clone Path", e);
            }
            path.categories = new ArrayList<>(this.categories);
            path.vbskus = new HashSet<>();
            path.bskus = new HashSet<>();
            path.vcoreAttrs = new HashSet<>();
            path.vnoncoreAttrs = new HashSet<>();
            path.coreAttrs = new HashSet<>();
            path.noncoreAttrs = new HashSet<>();
            path.mentions = new HashSet<>();
            return path;
        }
    }

    private enum ForwardType {

        HAS_CORE_ATTR("has_core_attr", Directions.IN),

        HAS_VCORE_ATTR("has_vcore_attr", Directions.IN),

        IS_INSTANCE("is_instance", Directions.IN),

        IS_CATEGORY("is_category", Directions.OUT),

        IS_SUBCATEGORY("is_subcategory", Directions.OUT),

        IS_TRADE("is_trade", Directions.OUT),

        IS_ROOT("is_root", Directions.OUT);

        private String label;
        private Directions direction;

        ForwardType(String label, Directions direction) {
            this.label = label;
            this.direction = direction;
        }

        public String label() {
            return this.label;
        }

        public Directions direction() {
            return this.direction;
        }
    }

    private static class NodeMap<V> extends MultivaluedHashMap<V, Node> {}
}
