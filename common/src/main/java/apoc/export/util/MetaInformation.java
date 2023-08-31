/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package apoc.export.util;

import apoc.meta.Types;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ClassUtils;
import org.neo4j.cypher.export.SubGraph;
import org.neo4j.graphdb.Entity;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ResultTransformer;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static apoc.export.util.BulkImportUtil.allowedMapping;
import static apoc.gephi.GephiFormatUtils.getCaption;
import static apoc.meta.tablesforlabels.PropertyTracker.typeMappings;
import static apoc.util.collection.Iterables.stream;
import static java.util.Arrays.asList;
import static org.apache.commons.lang3.ClassUtils.primitiveToWrapper;

/**
 * @author mh
 * @since 19.01.14
 */
public class MetaInformation {
    
    private static final Map<String, String> REVERSED_TYPE_MAP = MapUtils.invertMap(typeMappings);
    
    public static Map<String, Class> collectPropTypesForNodes(SubGraph graph, GraphDatabaseService db, ExportConfig config) {
        if (!config.isSampling()) {
            Map<String,Class> propTypes = new LinkedHashMap<>();
            for (Node node : graph.getNodes()) {
                updateKeyTypes(propTypes, node);
            }
            return propTypes;
        }
        final Map<String, Object> conf = config.getSamplingConfig();
        conf.putIfAbsent("includeLabels", stream(graph.getAllLabelsInUse()).map(Label::name).collect(Collectors.toList()));
        
        return db.executeTransactionally("CALL apoc.meta.nodeTypeProperties($conf)", 
                Map.of("conf", conf), getMapResultTransformer()); 
    }

    public static Map<String, Class> collectPropTypesForRelationships(SubGraph graph, GraphDatabaseService db, ExportConfig config) {
        if (!config.isSampling()) {
            Map<String,Class> propTypes = new LinkedHashMap<>();
            for (Relationship relationship : graph.getRelationships()) {
                updateKeyTypes(propTypes, relationship);
            }
            return propTypes;
        }
        final Map<String, Object> conf = config.getSamplingConfig();
        conf.putIfAbsent("includeRels", stream(graph.getAllRelationshipTypesInUse()).map(RelationshipType::name).collect(Collectors.toList()));

        return db.executeTransactionally("CALL apoc.meta.relTypeProperties($conf)", 
                Map.of("conf", conf), getMapResultTransformer());
    }

    private static ResultTransformer<Map<String, Class>> getMapResultTransformer() {
        return result -> result.stream()
                .filter(map -> map.get("propertyName") != null)
                .collect(Collectors.toMap(map -> (String) map.get("propertyName"),
                        map -> {
                            final String propertyTypes = ((List<String>) map.get("propertyTypes")).get(0);
                            // take the className from the result, inversely to the meta.relTypeProperties/nodeTypeProperties procedures
                            String className = REVERSED_TYPE_MAP.get(propertyTypes);
                            try {
                                return ClassUtils.getClass(className);
                            } catch (ClassNotFoundException e) {
                                throw new RuntimeException(e);
                            }
                        }, (e1, e2) -> e2));
    }

    public static void updateKeyTypes(Map<String, Class> keyTypes, Entity pc) {
        for (String prop : pc.getPropertyKeys()) {
            Object value = pc.getProperty(prop);
            Class storedClass = keyTypes.get(prop);
            if (storedClass==null) {
                keyTypes.put(prop,value.getClass());
                continue;
            }
            if (storedClass == void.class || storedClass.equals(value.getClass())) continue;
            keyTypes.put(prop, void.class);
        }
    }

    public final static Set<String> GRAPHML_ALLOWED = new HashSet<>(asList("boolean", "int", "long", "float", "double", "string"));
    
    public static String typeFor(Class value, Set<String> allowed) {
        if (value == void.class) return null; // Is this necessary?
        final boolean isArray = value.isArray();
        value = isArray ? value.getComponentType() : value;
        // csv case
        // consistent with https://neo4j.com/docs/operations-manual/current/tools/neo4j-admin/neo4j-admin-import/#import-tool-header-format-properties
        if (allowed == null) {
            return allowedMapping.getOrDefault( primitiveToWrapper(value), "string" );
        }
        // graphML case
        String name = value.getSimpleName().toLowerCase();
        boolean isAllowed = allowed.contains(name);
        Types type = Types.of(value);
        switch (type) {
            case NULL:
                return null;
            case INTEGER: case FLOAT:
                return "integer".equals(name) || !isAllowed ? "int" : name;
            default:
                return isAllowed ? name : "string"; // We manage all other data types as strings
        }
    }

    public static String getLabelsString(Node node) {
        if (!node.getLabels().iterator().hasNext()) return "";
        String delimiter = ":";
        return delimiter + FormatUtils.joinLabels(node, delimiter);
    }

    public static String getLabelsStringGephi(ExportConfig config, Node node) {
        return getCaption(node, config.getCaption());
    }
}
