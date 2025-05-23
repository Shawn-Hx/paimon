/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.rest;

import org.apache.paimon.rest.requests.AlterDatabaseRequest;
import org.apache.paimon.rest.requests.AlterFunctionRequest;
import org.apache.paimon.rest.requests.AlterTableRequest;
import org.apache.paimon.rest.requests.AlterViewRequest;
import org.apache.paimon.rest.requests.CreateDatabaseRequest;
import org.apache.paimon.rest.requests.CreateFunctionRequest;
import org.apache.paimon.rest.requests.CreateTableRequest;
import org.apache.paimon.rest.requests.CreateViewRequest;
import org.apache.paimon.rest.requests.RenameTableRequest;
import org.apache.paimon.rest.requests.RollbackTableRequest;
import org.apache.paimon.rest.responses.AlterDatabaseResponse;
import org.apache.paimon.rest.responses.ConfigResponse;
import org.apache.paimon.rest.responses.ErrorResponse;
import org.apache.paimon.rest.responses.GetDatabaseResponse;
import org.apache.paimon.rest.responses.GetFunctionResponse;
import org.apache.paimon.rest.responses.GetTableResponse;
import org.apache.paimon.rest.responses.GetTableTokenResponse;
import org.apache.paimon.rest.responses.GetViewResponse;
import org.apache.paimon.rest.responses.ListDatabasesResponse;
import org.apache.paimon.rest.responses.ListPartitionsResponse;
import org.apache.paimon.rest.responses.ListTablesResponse;
import org.apache.paimon.rest.responses.ListViewsResponse;
import org.apache.paimon.table.Instant;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataTypes;
import org.apache.paimon.types.IntType;

import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.core.JsonProcessingException;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.apache.paimon.rest.RESTObjectMapper.OBJECT_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Test for {@link RESTObjectMapper}. */
public class RESTObjectMapperTest {

    @Test
    public void configResponseParseTest() throws Exception {
        String confKey = "a";
        Map<String, String> conf = new HashMap<>();
        conf.put(confKey, "b");
        ConfigResponse response = new ConfigResponse(conf, conf);
        String responseStr = OBJECT_MAPPER.writeValueAsString(response);
        ConfigResponse parseData = OBJECT_MAPPER.readValue(responseStr, ConfigResponse.class);
        assertEquals(conf.get(confKey), parseData.getDefaults().get(confKey));
    }

    @Test
    public void errorResponseParseTest() throws Exception {
        String message = "message";
        Integer code = 400;
        ErrorResponse response = new ErrorResponse(null, null, message, code);
        String responseStr = OBJECT_MAPPER.writeValueAsString(response);
        ErrorResponse parseData = OBJECT_MAPPER.readValue(responseStr, ErrorResponse.class);
        assertEquals(message, parseData.getMessage());
        assertEquals(code, parseData.getCode());
    }

    @Test
    public void createDatabaseRequestParseTest() throws Exception {
        String name = MockRESTMessage.databaseName();
        CreateDatabaseRequest request = MockRESTMessage.createDatabaseRequest(name);
        String requestStr = OBJECT_MAPPER.writeValueAsString(request);
        CreateDatabaseRequest parseData =
                OBJECT_MAPPER.readValue(requestStr, CreateDatabaseRequest.class);
        assertEquals(request.getName(), parseData.getName());
        assertEquals(request.getOptions().size(), parseData.getOptions().size());
    }

    @Test
    public void getDatabaseResponseParseTest() throws Exception {
        String name = MockRESTMessage.databaseName();
        GetDatabaseResponse response = MockRESTMessage.getDatabaseResponse(name);
        String responseStr = OBJECT_MAPPER.writeValueAsString(response);
        GetDatabaseResponse parseData =
                OBJECT_MAPPER.readValue(responseStr, GetDatabaseResponse.class);
        assertEquals(name, parseData.getName());
        assertEquals(response.getOptions().size(), parseData.getOptions().size());
    }

    @Test
    public void listDatabaseResponseParseTest() throws Exception {
        String name = MockRESTMessage.databaseName();
        ListDatabasesResponse response = MockRESTMessage.listDatabasesResponse(name);
        String responseStr = OBJECT_MAPPER.writeValueAsString(response);
        ListDatabasesResponse parseData =
                OBJECT_MAPPER.readValue(responseStr, ListDatabasesResponse.class);
        assertEquals(response.getDatabases().size(), parseData.getDatabases().size());
        assertEquals(name, parseData.getDatabases().get(0));
    }

    @Test
    public void alterDatabaseRequestParseTest() throws Exception {
        AlterDatabaseRequest request = MockRESTMessage.alterDatabaseRequest();
        String requestStr = OBJECT_MAPPER.writeValueAsString(request);
        AlterDatabaseRequest parseData =
                OBJECT_MAPPER.readValue(requestStr, AlterDatabaseRequest.class);
        assertEquals(request.getRemovals().size(), parseData.getRemovals().size());
        assertEquals(request.getUpdates().size(), parseData.getUpdates().size());
    }

    @Test
    public void alterDatabaseResponseParseTest() throws Exception {
        AlterDatabaseResponse response = MockRESTMessage.alterDatabaseResponse();
        String responseStr = OBJECT_MAPPER.writeValueAsString(response);
        AlterDatabaseResponse parseData =
                OBJECT_MAPPER.readValue(responseStr, AlterDatabaseResponse.class);
        assertEquals(response.getRemoved().size(), parseData.getRemoved().size());
        assertEquals(response.getUpdated().size(), parseData.getUpdated().size());
        assertEquals(response.getMissing().size(), parseData.getMissing().size());
    }

    @Test
    public void createTableRequestParseTest() throws Exception {
        CreateTableRequest request = MockRESTMessage.createTableRequest("t1");
        String requestStr = OBJECT_MAPPER.writeValueAsString(request);
        CreateTableRequest parseData =
                OBJECT_MAPPER.readValue(requestStr, CreateTableRequest.class);
        assertEquals(request.getIdentifier(), parseData.getIdentifier());
        assertEquals(request.getSchema(), parseData.getSchema());
    }

    // This test is to guarantee the compatibility of field name in RESTCatalog.
    @Test
    public void dataFieldParseTest() throws Exception {
        int id = 1;
        String name = "col1";
        IntType type = DataTypes.INT();
        String descStr = "desc";
        String dataFieldStr =
                String.format(
                        "{\"id\": %d,\"name\":\"%s\",\"type\":\"%s\", \"description\":\"%s\"}",
                        id, name, type, descStr);
        DataField parseData = OBJECT_MAPPER.readValue(dataFieldStr, DataField.class);
        assertEquals(id, parseData.id());
        assertEquals(name, parseData.name());
        assertEquals(type, parseData.type());
        assertEquals(descStr, parseData.description());
    }

    @Test
    public void renameTableRequestParseTest() throws Exception {
        RenameTableRequest request = MockRESTMessage.renameRequest("t1", "t2");
        String requestStr = OBJECT_MAPPER.writeValueAsString(request);
        RenameTableRequest parseData =
                OBJECT_MAPPER.readValue(requestStr, RenameTableRequest.class);
        assertEquals(request.getSource(), parseData.getSource());
        assertEquals(request.getDestination(), parseData.getDestination());
    }

    @Test
    public void getTableResponseParseTest() throws Exception {
        GetTableResponse response = MockRESTMessage.getTableResponse();
        String responseStr = OBJECT_MAPPER.writeValueAsString(response);
        GetTableResponse parseData = OBJECT_MAPPER.readValue(responseStr, GetTableResponse.class);
        assertEquals(response.getSchemaId(), parseData.getSchemaId());
        assertEquals(response.getSchema(), parseData.getSchema());
    }

    @Test
    public void listTablesResponseParseTest() throws Exception {
        ListTablesResponse response = MockRESTMessage.listTablesResponse();
        String responseStr = OBJECT_MAPPER.writeValueAsString(response);
        ListTablesResponse parseData =
                OBJECT_MAPPER.readValue(responseStr, ListTablesResponse.class);
        assertEquals(response.getTables(), parseData.getTables());
    }

    @Test
    public void alterTableRequestParseTest() throws Exception {
        AlterTableRequest request = MockRESTMessage.alterTableRequest();
        String requestStr = OBJECT_MAPPER.writeValueAsString(request);
        AlterTableRequest parseData = OBJECT_MAPPER.readValue(requestStr, AlterTableRequest.class);
        assertEquals(parseData.getChanges().size(), parseData.getChanges().size());
    }

    @Test
    public void listPartitionsResponseParseTest() throws Exception {
        ListPartitionsResponse response = MockRESTMessage.listPartitionsResponse();
        String responseStr = OBJECT_MAPPER.writeValueAsString(response);
        ListPartitionsResponse parseData =
                OBJECT_MAPPER.readValue(responseStr, ListPartitionsResponse.class);
        assertEquals(
                response.getPartitions().get(0).fileCount(),
                parseData.getPartitions().get(0).fileCount());
    }

    @Test
    public void createViewRequestParseTest() throws Exception {
        CreateViewRequest request = MockRESTMessage.createViewRequest("t1");
        String requestStr = OBJECT_MAPPER.writeValueAsString(request);
        CreateViewRequest parseData = OBJECT_MAPPER.readValue(requestStr, CreateViewRequest.class);
        assertEquals(request.getIdentifier(), parseData.getIdentifier());
        assertEquals(request.getSchema(), parseData.getSchema());
    }

    @Test
    public void getViewResponseParseTest() throws Exception {
        GetViewResponse response = MockRESTMessage.getViewResponse();
        String responseStr = OBJECT_MAPPER.writeValueAsString(response);
        GetViewResponse parseData = OBJECT_MAPPER.readValue(responseStr, GetViewResponse.class);
        assertEquals(response.getId(), parseData.getId());
        assertEquals(response.getName(), parseData.getName());
        assertEquals(response.getSchema(), parseData.getSchema());
    }

    @Test
    public void listViewsResponseParseTest() throws Exception {
        ListViewsResponse response = MockRESTMessage.listViewsResponse();
        String responseStr = OBJECT_MAPPER.writeValueAsString(response);
        ListViewsResponse parseData = OBJECT_MAPPER.readValue(responseStr, ListViewsResponse.class);
        assertEquals(response.getViews(), parseData.getViews());
    }

    @Test
    public void getTableTokenResponseParseTest() throws Exception {
        GetTableTokenResponse response = MockRESTMessage.getTableCredentialsResponse();
        String responseStr = OBJECT_MAPPER.writeValueAsString(response);
        GetTableTokenResponse parseData =
                OBJECT_MAPPER.readValue(responseStr, GetTableTokenResponse.class);
        assertEquals(response.getToken(), parseData.getToken());
        assertEquals(response.getExpiresAtMillis(), parseData.getExpiresAtMillis());
    }

    @Test
    public void rollbackTableRequestParseTest() throws Exception {
        Long snapshotId = 123L;
        String tagName = "tagName";
        RollbackTableRequest rollbackTableRequestBySnapshot =
                MockRESTMessage.rollbackTableRequestBySnapshot(snapshotId);
        String rollbackTableRequestBySnapshotStr =
                OBJECT_MAPPER.writeValueAsString(rollbackTableRequestBySnapshot);
        Instant.SnapshotInstant rollbackTableRequestParseData =
                (Instant.SnapshotInstant)
                        OBJECT_MAPPER
                                .readValue(
                                        rollbackTableRequestBySnapshotStr,
                                        RollbackTableRequest.class)
                                .getInstant();
        assertTrue(rollbackTableRequestParseData.getSnapshotId() == snapshotId);
        RollbackTableRequest rollbackTableRequestByTag =
                MockRESTMessage.rollbackTableRequestByTag(tagName);
        String rollbackTableRequestByTagStr =
                OBJECT_MAPPER.writeValueAsString(rollbackTableRequestByTag);
        Instant.TagInstant rollbackTableRequestByTagParseData =
                (Instant.TagInstant)
                        OBJECT_MAPPER
                                .readValue(rollbackTableRequestByTagStr, RollbackTableRequest.class)
                                .getInstant();
        assertEquals(rollbackTableRequestByTagParseData.getTagName(), tagName);
    }

    @Test
    public void alterViewRequestParseTest() throws Exception {
        AlterViewRequest request = MockRESTMessage.alterViewRequest();
        String requestStr = OBJECT_MAPPER.writeValueAsString(request);
        AlterViewRequest parseData = OBJECT_MAPPER.readValue(requestStr, AlterViewRequest.class);
        assertEquals(parseData.viewChanges().size(), request.viewChanges().size());
        for (int i = 0; i < request.viewChanges().size(); i++) {
            assertEquals(parseData.viewChanges().get(i), request.viewChanges().get(i));
        }
    }

    @Test
    public void getFunctionResponseParseTest() throws Exception {
        GetFunctionResponse response = MockRESTMessage.getFunctionResponse();
        String responseStr = OBJECT_MAPPER.writeValueAsString(response);
        GetFunctionResponse parseData =
                OBJECT_MAPPER.readValue(responseStr, GetFunctionResponse.class);
        assertEquals(response.uuid(), parseData.uuid());
    }

    @Test
    public void createFunctionRequestParseTest() throws JsonProcessingException {
        CreateFunctionRequest request = MockRESTMessage.createFunctionRequest();
        String requestStr = OBJECT_MAPPER.writeValueAsString(request);
        CreateFunctionRequest parseData =
                OBJECT_MAPPER.readValue(requestStr, CreateFunctionRequest.class);
        assertEquals(parseData.name(), request.name());
    }

    @Test
    public void alterFunctionRequestParseTest() throws JsonProcessingException {
        AlterFunctionRequest request = MockRESTMessage.alterFunctionRequest();
        String requestStr = OBJECT_MAPPER.writeValueAsString(request);
        AlterFunctionRequest parseData =
                OBJECT_MAPPER.readValue(requestStr, AlterFunctionRequest.class);
        assertEquals(parseData.changes().size(), request.changes().size());
    }
}
