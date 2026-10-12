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
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.rest.requests.CreateNamespaceRequest;
import org.apache.polaris.core.admin.model.AwsStorageConfigInfo;
import org.apache.polaris.core.admin.model.Catalog;
import org.apache.polaris.core.admin.model.CatalogProperties;
import org.apache.polaris.core.admin.model.CreateCatalogRequest;
import org.apache.polaris.core.admin.model.PolarisCatalog;
import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.apache.polaris.service.TestServices;
import org.apache.polaris.service.catalog.api.PolarisCatalogIcechunkTableApi;
import org.apache.polaris.service.events.EventAttributes;
import org.apache.polaris.service.events.PolarisEvent;
import org.apache.polaris.service.events.PolarisEventType;
import org.apache.polaris.service.events.listeners.InMemoryEventCollector;
import org.apache.polaris.service.types.CreateIcechunkTableRequest;
import org.apache.polaris.service.types.LoadIcechunkTableResponse;
import org.junit.jupiter.api.Test;

public class CatalogIcechunkTableEventServiceDelegatorTest {

  private static final String CATALOG = "test-catalog";
  private static final String NAMESPACE = "ns";
  private static final String CATALOG_LOCATION = "s3://bucket/path/to/data";
  private static final TableIdentifier TABLE = TableIdentifier.of(Namespace.of(NAMESPACE), "repo");

  private final TestServices services =
      TestServices.builder()
          .config(
              Map.of(
                  "ENABLE_ICECHUNK_TABLES",
                  true,
                  "SUPPORTED_CATALOG_STORAGE_TYPES",
                  List.of("FILE", "S3")))
          .withEventDelegator(true)
          .build();
  private final InMemoryEventCollector events =
      (InMemoryEventCollector) services.polarisEventDispatcher();

  private PolarisCatalogIcechunkTableApi createCatalogAndNamespace() {
    Catalog catalog =
        PolarisCatalog.builder()
            .setType(Catalog.TypeEnum.INTERNAL)
            .setName(CATALOG)
            .setProperties(new CatalogProperties(CATALOG_LOCATION))
            .setStorageConfigInfo(
                AwsStorageConfigInfo.builder(StorageConfigInfo.StorageTypeEnum.S3)
                    .setRoleArn("arn:aws:iam::012345678901:role/jdoe")
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
    return services.icechunkTableApi();
  }

  /** Single-table events carry the catalog name and the table identifier, and nothing else. */
  private void assertTableEvent(PolarisEventType type) {
    PolarisEvent event = events.getLatest(type);
    assertThat(event.attributes().keySet())
        .containsExactlyInAnyOrder(EventAttributes.CATALOG_NAME, EventAttributes.TABLE_IDENTIFIER);
    assertThat(event.attributes().getRequired(EventAttributes.CATALOG_NAME)).isEqualTo(CATALOG);
    assertThat(event.attributes().getRequired(EventAttributes.TABLE_IDENTIFIER)).isEqualTo(TABLE);
  }

  @Test
  public void testEventsCarryOnlyCatalogNameAndIdentifier() {
    PolarisCatalogIcechunkTableApi api = createCatalogAndNamespace();

    // The response of a create with delegation carries credentials; the events must not.
    try (Response response =
        api.createIcechunkTable(
            CATALOG,
            NAMESPACE,
            CreateIcechunkTableRequest.builder()
                .setName(TABLE.name())
                .setLocation(CATALOG_LOCATION + "/ns/repo")
                .build(),
            "vended-credentials",
            services.realmContext(),
            services.securityContext())) {
      assertThat(((LoadIcechunkTableResponse) response.getEntity()).getStorageAccessConfigs())
          .isNotEmpty();
    }
    assertTableEvent(PolarisEventType.BEFORE_CREATE_ICECHUNK_TABLE);
    assertTableEvent(PolarisEventType.AFTER_CREATE_ICECHUNK_TABLE);

    try (Response response =
        api.loadIcechunkTable(
            CATALOG,
            NAMESPACE,
            TABLE.name(),
            "vended-credentials",
            "read-write",
            services.realmContext(),
            services.securityContext())) {
      assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
    }
    assertTableEvent(PolarisEventType.BEFORE_LOAD_ICECHUNK_TABLE);
    assertTableEvent(PolarisEventType.AFTER_LOAD_ICECHUNK_TABLE);

    try (Response response =
        api.loadIcechunkTableCredentials(
            CATALOG,
            NAMESPACE,
            TABLE.name(),
            "read",
            services.realmContext(),
            services.securityContext())) {
      assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
    }
    assertTableEvent(PolarisEventType.BEFORE_LOAD_ICECHUNK_TABLE_CREDENTIALS);
    assertTableEvent(PolarisEventType.AFTER_LOAD_ICECHUNK_TABLE_CREDENTIALS);

    try (Response response =
        api.listIcechunkTables(
            CATALOG, NAMESPACE, null, null, services.realmContext(), services.securityContext())) {
      assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
    }
    for (PolarisEventType type :
        List.of(
            PolarisEventType.BEFORE_LIST_ICECHUNK_TABLES,
            PolarisEventType.AFTER_LIST_ICECHUNK_TABLES)) {
      PolarisEvent event = events.getLatest(type);
      assertThat(event.attributes().keySet())
          .containsExactlyInAnyOrder(EventAttributes.CATALOG_NAME, EventAttributes.NAMESPACE);
      assertThat(event.attributes().getRequired(EventAttributes.CATALOG_NAME)).isEqualTo(CATALOG);
      assertThat(event.attributes().getRequired(EventAttributes.NAMESPACE))
          .isEqualTo(TABLE.namespace());
    }

    try (Response response =
        api.dropIcechunkTable(
            CATALOG,
            NAMESPACE,
            TABLE.name(),
            services.realmContext(),
            services.securityContext())) {
      assertThat(response.getStatus()).isEqualTo(Response.Status.NO_CONTENT.getStatusCode());
    }
    assertTableEvent(PolarisEventType.BEFORE_DROP_ICECHUNK_TABLE);
    assertTableEvent(PolarisEventType.AFTER_DROP_ICECHUNK_TABLE);
  }

  @Test
  public void testFailedCallEmitsOnlyBeforeEvent() {
    PolarisCatalogIcechunkTableApi api = createCatalogAndNamespace();

    assertThatThrownBy(
            () ->
                api.loadIcechunkTable(
                    CATALOG,
                    NAMESPACE,
                    TABLE.name(),
                    null,
                    null,
                    services.realmContext(),
                    services.securityContext()))
        .isInstanceOf(NoSuchTableException.class);

    assertTableEvent(PolarisEventType.BEFORE_LOAD_ICECHUNK_TABLE);
    assertThatThrownBy(() -> events.getLatest(PolarisEventType.AFTER_LOAD_ICECHUNK_TABLE))
        .isInstanceOf(IllegalStateException.class);
  }
}
