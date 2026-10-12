/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.polaris.service.it.env;

import static jakarta.ws.rs.core.Response.Status.NO_CONTENT;
import static jakarta.ws.rs.core.Response.Status.OK;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.polaris.core.rest.NamespaceUtils;
import org.apache.polaris.service.types.CreateIcechunkTableRequest;
import org.apache.polaris.service.types.ListIcechunkTablesResponse;
import org.apache.polaris.service.types.LoadIcechunkTableCredentialsResponse;
import org.apache.polaris.service.types.LoadIcechunkTableResponse;
import org.jspecify.annotations.Nullable;

/**
 * A simple, non-exhaustive set of helper methods for accessing the Icechunk tables REST API.
 *
 * <p>The {@code ...IcechunkTable...} methods expect the request to succeed. The {@link #create},
 * {@link #load}, {@link #loadCredentials}, {@link #drop} and {@link #list} methods return the raw
 * response, which the caller is responsible for checking and closing.
 *
 * @see PolarisClient#icechunkTableApi(String)
 */
public class IcechunkTableApi extends PolarisRestApi {
  private static final String ACCESS_DELEGATION_HEADER = "Polaris-Icechunk-Table-Access-Delegation";
  private static final String TABLES_PATH = "polaris/v1/{cat}/namespaces/{ns}/icechunk-tables";
  private static final String TABLE_PATH = TABLES_PATH + "/{table}";

  IcechunkTableApi(Client client, PolarisApiEndpoints endpoints, String authToken, URI uri) {
    super(client, endpoints, authToken, uri);
  }

  public void purge(String catalog, Namespace ns) {
    listIcechunkTables(catalog, ns).forEach(t -> dropIcechunkTable(catalog, t));
  }

  public List<TableIdentifier> listIcechunkTables(String catalog, Namespace namespace) {
    List<TableIdentifier> identifiers = new ArrayList<>();
    String nextPageToken = null;
    do {
      ListIcechunkTablesResponse response =
          listIcechunkTables(catalog, namespace, nextPageToken, null);
      identifiers.addAll(response.getIdentifiers());
      nextPageToken = response.getNextPageToken();
    } while (nextPageToken != null);
    return identifiers;
  }

  public ListIcechunkTablesResponse listIcechunkTables(
      String catalog, Namespace namespace, @Nullable String pageToken, @Nullable String pageSize) {
    try (Response res = list(catalog, namespace, pageToken, pageSize)) {
      assertThat(res.getStatus()).isEqualTo(OK.getStatusCode());
      return res.readEntity(ListIcechunkTablesResponse.class);
    }
  }

  public LoadIcechunkTableResponse createIcechunkTable(
      String catalog, TableIdentifier id, String location, Map<String, String> properties) {
    CreateIcechunkTableRequest request =
        CreateIcechunkTableRequest.builder()
            .setName(id.name())
            .setLocation(location)
            .setProperties(properties)
            .build();
    try (Response res = create(catalog, id.namespace(), request, null)) {
      assertThat(res.getStatus()).isEqualTo(OK.getStatusCode());
      return res.readEntity(LoadIcechunkTableResponse.class);
    }
  }

  public LoadIcechunkTableResponse loadIcechunkTable(String catalog, TableIdentifier id) {
    try (Response res = load(catalog, id, null, null)) {
      assertThat(res.getStatus()).isEqualTo(OK.getStatusCode());
      return res.readEntity(LoadIcechunkTableResponse.class);
    }
  }

  public LoadIcechunkTableCredentialsResponse loadIcechunkTableCredentials(
      String catalog, TableIdentifier id, @Nullable String accessMode) {
    try (Response res = loadCredentials(catalog, id, accessMode)) {
      assertThat(res.getStatus()).isEqualTo(OK.getStatusCode());
      return res.readEntity(LoadIcechunkTableCredentialsResponse.class);
    }
  }

  public void dropIcechunkTable(String catalog, TableIdentifier id) {
    try (Response res = drop(catalog, id)) {
      assertThat(res.getStatus()).isEqualTo(NO_CONTENT.getStatusCode());
    }
  }

  public Response list(
      String catalog, Namespace namespace, @Nullable String pageToken, @Nullable String pageSize) {
    Map<String, String> queryParams = new HashMap<>();
    if (pageToken != null) {
      queryParams.put("pageToken", pageToken);
    }
    if (pageSize != null) {
      queryParams.put("pageSize", pageSize);
    }
    return request(TABLES_PATH, Map.of("cat", catalog, "ns", joinNamespace(namespace)), queryParams)
        .get();
  }

  /**
   * Sends a create request. A non-null {@code accessDelegation} is sent as the access delegation
   * header.
   */
  public Response create(
      String catalog,
      Namespace namespace,
      CreateIcechunkTableRequest createRequest,
      @Nullable String accessDelegation) {
    return withAccessDelegation(
            request(TABLES_PATH, Map.of("cat", catalog, "ns", joinNamespace(namespace))),
            accessDelegation)
        .post(Entity.json(createRequest));
  }

  /**
   * Sends a load request. A non-null {@code accessDelegation} is sent as the access delegation
   * header, and a non-null {@code accessMode} as the {@code access-mode} query parameter.
   */
  public Response load(
      String catalog,
      TableIdentifier id,
      @Nullable String accessDelegation,
      @Nullable String accessMode) {
    return withAccessDelegation(
            request(TABLE_PATH, tablePathValues(catalog, id), accessModeParameter(accessMode)),
            accessDelegation)
        .get();
  }

  /** Sends a credentials request, with a non-null {@code accessMode} as the query parameter. */
  public Response loadCredentials(String catalog, TableIdentifier id, @Nullable String accessMode) {
    return request(
            TABLE_PATH + "/credentials",
            tablePathValues(catalog, id),
            accessModeParameter(accessMode))
        .get();
  }

  public Response drop(String catalog, TableIdentifier id) {
    return request(TABLE_PATH, tablePathValues(catalog, id)).delete();
  }

  private static Invocation.Builder withAccessDelegation(
      Invocation.Builder request, @Nullable String accessDelegation) {
    return accessDelegation == null
        ? request
        : request.header(ACCESS_DELEGATION_HEADER, accessDelegation);
  }

  private static Map<String, String> accessModeParameter(@Nullable String accessMode) {
    return accessMode == null ? Map.of() : Map.of("access-mode", accessMode);
  }

  private static Map<String, String> tablePathValues(String catalog, TableIdentifier id) {
    return Map.of("cat", catalog, "ns", joinNamespace(id.namespace()), "table", id.name());
  }

  private static String joinNamespace(Namespace namespace) {
    return NamespaceUtils.joinNamespace(namespace, NamespaceUtils.DEFAULT_NAMESPACE_SEPARATOR);
  }
}
