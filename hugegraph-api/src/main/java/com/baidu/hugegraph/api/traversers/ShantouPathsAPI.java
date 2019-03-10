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

package com.baidu.hugegraph.api.traversers;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;

import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.slf4j.Logger;

import com.baidu.hugegraph.HugeGraph;
import com.baidu.hugegraph.api.API;
import com.baidu.hugegraph.api.filter.StatusFilter.Status;
import com.baidu.hugegraph.backend.id.Id;
import com.baidu.hugegraph.core.GraphManager;
import com.baidu.hugegraph.server.RestServer;
import com.baidu.hugegraph.structure.HugeVertex;
import com.baidu.hugegraph.traversal.algorithm.ShantouPathsTraverser;
import com.baidu.hugegraph.util.E;
import com.baidu.hugegraph.util.Log;
import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableMap;

@Path("graphs/{graph}/traversers/shantoupaths")
@Singleton
public class ShantouPathsAPI extends API {

    private static final Logger LOG = Log.logger(RestServer.class);

    @POST
    @Timed
    @Status(Status.CREATED)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    public String post(@Context GraphManager manager,
                       @PathParam("graph") String graph,
                       ShantouPathsRequest request) {
        E.checkArgumentNotNull(request,
                               "The request body can't be null");
        E.checkArgument(request.mentions != null &&
                        !request.mentions.isEmpty(),
                        "The mentions of request can't be null or empty");
        LOG.debug("Graph [{}] get paths from mentions '{}'",
                  graph, request.mentions);

        HugeGraph g = graph(manager, graph);
        ShantouPathsTraverser traverser = new ShantouPathsTraverser(g);
        List<ShantouPathsTraverser.Path> paths = traverser.paths(
                                                        request.mentions);
        if (!request.withVertex) {
            return manager.serializer(g)
                          .writeShantoupaths(paths, ImmutableMap.of());
        }
        Set<Id> ids = new HashSet<>();
        for (ShantouPathsTraverser.Path p : paths) {
            ids.addAll(p.vertices());
        }
        Iterator<Vertex> iter = Collections.emptyIterator();
        if (!ids.isEmpty()) {
            iter = g.vertices(ids.toArray());
        }
        Map<Id, Vertex> vertices = new HashMap<>();
        while (iter.hasNext()) {
            HugeVertex vertex = (HugeVertex) iter.next();
            vertices.put(vertex.id(), vertex);
        }
        return manager.serializer(g).writeShantoupaths(paths, vertices);
    }

    private static class ShantouPathsRequest {

        @JsonProperty("mentions")
        private List<String> mentions;
        @JsonProperty("with_vertex")
        private boolean withVertex;

        @Override
        public String toString() {
            return String.format("ShantouPathsRequest{mentions=%s," +
                                 "withVertex=%s}",
                                 this.mentions, this.withVertex);
        }
    }
}
