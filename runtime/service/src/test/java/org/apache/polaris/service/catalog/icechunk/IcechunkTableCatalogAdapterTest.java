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
package org.apache.polaris.service.catalog.icechunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.rest.requests.CreateNamespaceRequest;
import org.apache.polaris.core.admin.model.Catalog;
import org.apache.polaris.core.admin.model.CatalogProperties;
import org.apache.polaris.core.admin.model.CreateCatalogRequest;
import org.apache.polaris.core.admin.model.FileStorageConfigInfo;
import org.apache.polaris.core.admin.model.PolarisCatalog;
import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.apache.polaris.service.TestServices;
import org.apache.polaris.service.catalog.api.PolarisCatalogIcechunkTableApi;
import org.apache.polaris.service.types.CreateIcechunkTableRequest;
import org.apache.polaris.service.types.IcechunkTable;
import org.apache.polaris.service.types.ListIcechunkTablesResponse;
import org.apache.polaris.service.types.LoadIcechunkTableResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

public class IcechunkTableCatalogAdapterTest {

  private static final String CATALOG = "test-catalog";
  private static final String NAMESPACE = "ns";
  private static final String CATALOG_LOCATION = "file:///tmp/icechunk-adapter-test";
  private static final String REPO_LOCATION = CATALOG_LOCATION + "/ns/repo";
  private static final TableIdentifier TABLE = TableIdentifier.of(Namespace.of(NAMESPACE), "repo");

  private final TestServices services = newServices(true);

  private static TestServices newServices(boolean icechunkTablesEnabled) {
    TestServices services =
        TestServices.builder()
            .config(
                Map.of(
                    "ENABLE_ICECHUNK_TABLES",
                    icechunkTablesEnabled,
                    "ALLOW_INSECURE_STORAGE_TYPES",
                    true,
                    "SUPPORTED_CATALOG_STORAGE_TYPES",
                    List.of("FILE", "S3")))
            .build();

    Catalog catalog =
        PolarisCatalog.builder()
            .setType(Catalog.TypeEnum.INTERNAL)
            .setName(CATALOG)
            .setProperties(new CatalogProperties(CATALOG_LOCATION))
            .setStorageConfigInfo(
                FileStorageConfigInfo.builder(StorageConfigInfo.StorageTypeEnum.FILE)
                    .setAllowedLocations(List.of(CATALOG_LOCATION))
                    .build())
            .build();
    try (Response response =
        services
            .catalogsApi()
            .createCatalog(
                new CreateCatalogRequest(catalog),
                services.realmContext(),
                services.securityContext())) {
      assertThat(response.getStatus()).isEqualTo(Response.Status.CREATED.getStatusCode());
    }
    try (Response response =
        services
            .restApi()
            .createNamespace(
                CATALOG,
                CreateNamespaceRequest.builder().withNamespace(Namespace.of(NAMESPACE)).build(),
                null,
                services.realmContext(),
                services.securityContext())) {
      assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
    }
    return services;
  }

  private PolarisCatalogIcechunkTableApi api() {
    return services.icechunkTableApi();
  }

  private Response create(CreateIcechunkTableRequest request, String accessDelegation) {
    return api()
        .createIcechunkTable(
            CATALOG,
            NAMESPACE,
            request,
            accessDelegation,
            services.realmContext(),
            services.securityContext());
  }

  private Response load(String accessDelegation, String accessMode) {
    return api()
        .loadIcechunkTable(
            CATALOG,
            NAMESPACE,
            TABLE.name(),
            accessDelegation,
            accessMode,
            services.realmContext(),
            services.securityContext());
  }

  private Response loadCredentials(String accessMode) {
    return api()
        .loadIcechunkTableCredentials(
            CATALOG,
            NAMESPACE,
            TABLE.name(),
            accessMode,
            services.realmContext(),
            services.securityContext());
  }

  private ListIcechunkTablesResponse list() {
    try (Response response =
        api()
            .listIcechunkTables(
                CATALOG,
                NAMESPACE,
                null,
                null,
                services.realmContext(),
                services.securityContext())) {
      assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
      return (ListIcechunkTablesResponse) response.getEntity();
    }
  }

  private static CreateIcechunkTableRequest createRequest(
      String name, String location, Map<String, String> properties) {
    return CreateIcechunkTableRequest.builder()
        .setName(name)
        .setLocation(location)
        .setProperties(properties)
        .build();
  }

  @Test
  public void testCrudThroughApi() {
    try (Response response =
        create(createRequest(TABLE.name(), REPO_LOCATION, Map.of("owner", "alice")), null)) {
      assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
      LoadIcechunkTableResponse created = (LoadIcechunkTableResponse) response.getEntity();
      assertThat(created.getTable())
          .isEqualTo(
              new IcechunkTable(TABLE.name(), REPO_LOCATION + "/", Map.of("owner", "alice")));
      assertThat(created.getStorageAccessConfigs()).isEmpty();
    }

    try (Response response = load(null, null)) {
      assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
      LoadIcechunkTableResponse loaded = (LoadIcechunkTableResponse) response.getEntity();
      // The location is a field of the table, never one of its properties.
      assertThat(loaded.getTable())
          .isEqualTo(
              new IcechunkTable(TABLE.name(), REPO_LOCATION + "/", Map.of("owner", "alice")));
      assertThat(loaded.getStorageAccessConfigs()).isEmpty();
    }

    assertThat(list().getIdentifiers()).containsExactly(TABLE);

    try (Response response =
        api()
            .dropIcechunkTable(
                CATALOG,
                NAMESPACE,
                TABLE.name(),
                services.realmContext(),
                services.securityContext())) {
      assertThat(response.getStatus()).isEqualTo(Response.Status.NO_CONTENT.getStatusCode());
    }
    assertThatThrownBy(() -> load(null, null))
        .isInstanceOf(NoSuchTableException.class)
        .hasMessage("Icechunk table does not exist: %s", TABLE);
    assertThat(list().getIdentifiers()).isEmpty();
  }

  @Test
  public void testFeatureDisabled() {
    TestServices disabled = newServices(false);
    PolarisCatalogIcechunkTableApi api = disabled.icechunkTableApi();
    String message = "Feature not enabled: ENABLE_ICECHUNK_TABLES";

    assertThatThrownBy(
            () ->
                api.listIcechunkTables(
                    CATALOG,
                    NAMESPACE,
                    null,
                    null,
                    disabled.realmContext(),
                    disabled.securityContext()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(message);
    assertThatThrownBy(
            () ->
                api.createIcechunkTable(
                    CATALOG,
                    NAMESPACE,
                    createRequest(TABLE.name(), REPO_LOCATION, Map.of()),
                    null,
                    disabled.realmContext(),
                    disabled.securityContext()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(message);
    assertThatThrownBy(
            () ->
                api.loadIcechunkTable(
                    CATALOG,
                    NAMESPACE,
                    TABLE.name(),
                    null,
                    null,
                    disabled.realmContext(),
                    disabled.securityContext()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(message);
    assertThatThrownBy(
            () ->
                api.loadIcechunkTableCredentials(
                    CATALOG,
                    NAMESPACE,
                    TABLE.name(),
                    null,
                    disabled.realmContext(),
                    disabled.securityContext()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(message);
    assertThatThrownBy(
            () ->
                api.dropIcechunkTable(
                    CATALOG,
                    NAMESPACE,
                    TABLE.name(),
                    disabled.realmContext(),
                    disabled.securityContext()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(message);
  }

  @Test
  public void testCreateRejectsLocationProperty() {
    assertThatThrownBy(
            () ->
                create(
                    createRequest(TABLE.name(), REPO_LOCATION, Map.of("location", REPO_LOCATION)),
                    null))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(
            "Icechunk table properties must not contain 'location'; the repository root goes in "
                + "the location field");
    assertThat(list().getIdentifiers()).isEmpty();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  public void testCreateRequiresLocation(String location) {
    assertThatThrownBy(() -> create(createRequest(TABLE.name(), location, Map.of()), null))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("Icechunk table location is required");
    assertThat(list().getIdentifiers()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"bad/name", "..", " repo"})
  public void testCreateRejectsInvalidName(String name) {
    assertThatThrownBy(() -> create(createRequest(name, REPO_LOCATION, Map.of()), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("Entity name must not");
    assertThat(list().getIdentifiers()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"write", "READ", "read_write", "read,read-write"})
  public void testUnknownAccessModeIsRejected(String accessMode) {
    try (Response response = create(createRequest(TABLE.name(), REPO_LOCATION, Map.of()), null)) {
      assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
    }
    String message =
        "Unsupported access-mode '%s'; supported values are 'read' and 'read-write'"
            .formatted(accessMode);

    assertThatThrownBy(() -> load("vended-credentials", accessMode))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(message);
    assertThatThrownBy(() -> loadCredentials(accessMode))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(message);
  }

  @ParameterizedTest
  @ValueSource(strings = {"remote-signing", "unknown"})
  public void testUnsupportedAccessDelegationModeIsRejected(String accessDelegation) {
    String message =
        "Unsupported access delegation mode '%s'; Icechunk tables support only vended-credentials"
            .formatted(accessDelegation);

    assertThatThrownBy(
            () -> create(createRequest(TABLE.name(), REPO_LOCATION, Map.of()), accessDelegation))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(message);
    assertThat(list().getIdentifiers()).isEmpty();

    try (Response response = create(createRequest(TABLE.name(), REPO_LOCATION, Map.of()), null)) {
      assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
    }
    assertThatThrownBy(() -> load(accessDelegation, null))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(message);
  }
}
