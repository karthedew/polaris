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
package org.apache.polaris.service.it.test;

import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;
import static jakarta.ws.rs.core.Response.Status.CONFLICT;
import static jakarta.ws.rs.core.Response.Status.FORBIDDEN;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;
import static jakarta.ws.rs.core.Response.Status.OK;
import static org.apache.polaris.service.it.env.PolarisClient.polarisClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.NamespaceNotEmptyException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.iceberg.types.Types;
import org.apache.polaris.core.admin.model.Catalog;
import org.apache.polaris.core.admin.model.CatalogProperties;
import org.apache.polaris.core.admin.model.FileStorageConfigInfo;
import org.apache.polaris.core.admin.model.GrantResource;
import org.apache.polaris.core.admin.model.PolarisCatalog;
import org.apache.polaris.core.admin.model.PrincipalWithCredentials;
import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.apache.polaris.core.admin.model.TableGrant;
import org.apache.polaris.core.admin.model.TablePrivilege;
import org.apache.polaris.core.config.FeatureConfiguration;
import org.apache.polaris.core.entity.PolarisEntityConstants;
import org.apache.polaris.core.rest.IcechunkTableEndpoints;
import org.apache.polaris.service.it.env.CatalogApi;
import org.apache.polaris.service.it.env.ClientPrincipal;
import org.apache.polaris.service.it.env.IcebergHelper;
import org.apache.polaris.service.it.env.IcechunkTableApi;
import org.apache.polaris.service.it.env.IntegrationTestsHelper;
import org.apache.polaris.service.it.env.ManagementApi;
import org.apache.polaris.service.it.env.PolarisApiEndpoints;
import org.apache.polaris.service.it.env.PolarisClient;
import org.apache.polaris.service.it.ext.PolarisIntegrationTestExtension;
import org.apache.polaris.service.types.CreateIcechunkTableRequest;
import org.apache.polaris.service.types.IcechunkTable;
import org.apache.polaris.service.types.ListIcechunkTablesResponse;
import org.apache.polaris.service.types.LoadIcechunkTableResponse;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the Icechunk table REST API over HTTP, with a catalog on the local filesystem.
 *
 * @implSpec This test expects the server to be configured with {@link
 *     FeatureConfiguration#ENABLE_ICECHUNK_TABLES} set to {@code true}.
 */
@ExtendWith(PolarisIntegrationTestExtension.class)
public class PolarisIcechunkTableIntegrationTest {
  private static final Namespace NS1 = Namespace.of("ns1");
  private static final TableIdentifier REPO = TableIdentifier.of(NS1, "repo");
  private static final TableIdentifier ICEBERG_TABLE = TableIdentifier.of(NS1, "tbl");
  private static final Schema SCHEMA =
      new Schema(Types.NestedField.required(1, "id", Types.LongType.get()));
  private static final String VENDED_CREDENTIALS = "vended-credentials";

  private static ClientPrincipal adminPrincipal;
  private static PolarisApiEndpoints endpoints;
  private static PolarisClient client;
  private static String baseLocation;

  private String adminToken;
  private ManagementApi managementApi;
  private CatalogApi catalogApi;
  private IcechunkTableApi icechunkTableApi;
  private RESTCatalog restCatalog;
  private String currentCatalogName;
  private String catalogBaseLocation;

  @BeforeAll
  static void setup(
      PolarisApiEndpoints apiEndpoints, ClientPrincipal admin, @TempDir Path tempDir) {
    adminPrincipal = admin;
    endpoints = apiEndpoints;
    client = polarisClient(endpoints);
    URI tempDirURI = IntegrationTestsHelper.getTemporaryDirectory(tempDir);
    baseLocation = stripTrailingSlash(tempDirURI.toString());
  }

  @AfterAll
  static void close() throws Exception {
    client.close();
  }

  @BeforeEach
  public void before(TestInfo testInfo) {
    adminToken = client.obtainToken(adminPrincipal);
    managementApi = client.managementApi(adminToken);

    String principalName = client.newEntityName("snowman-icechunk");
    String principalRoleName = client.newEntityName("icechunk-admin");
    PrincipalWithCredentials principal =
        managementApi.createPrincipalWithRole(principalName, principalRoleName);
    String principalToken = client.obtainToken(principal);
    catalogApi = client.catalogApi(principalToken);
    icechunkTableApi = client.icechunkTableApi(principalToken);

    Method method = testInfo.getTestMethod().orElseThrow();
    currentCatalogName = client.newEntityName(method.getName());
    catalogBaseLocation = baseLocation + "/" + currentCatalogName;
    Catalog catalog =
        PolarisCatalog.builder()
            .setType(Catalog.TypeEnum.INTERNAL)
            .setName(currentCatalogName)
            .setProperties(
                CatalogProperties.builder(catalogBaseLocation)
                    .addProperty(
                        FeatureConfiguration.LIST_PAGINATION_ENABLED.catalogConfig(), "true")
                    .build())
            .setStorageConfigInfo(
                FileStorageConfigInfo.builder()
                    .setStorageType(StorageConfigInfo.StorageTypeEnum.FILE)
                    .setAllowedLocations(List.of(catalogBaseLocation))
                    .build())
            .build();
    managementApi.createCatalog(principalRoleName, catalog);

    restCatalog =
        IcebergHelper.restCatalog(endpoints, currentCatalogName, Map.of(), principalToken);
    restCatalog.createNamespace(NS1);
  }

  @AfterEach
  public void cleanUp() throws IOException {
    try {
      if (restCatalog != null) {
        restCatalog.close();
      }
      // PolarisClient.cleanUp() drops the namespaces, which fails while they hold Icechunk tables.
      catalogApi
          .listAllNamespacesChildFirst(currentCatalogName)
          .forEach(namespace -> icechunkTableApi.purge(currentCatalogName, namespace));
    } finally {
      client.cleanUp(adminToken);
    }
  }

  @Test
  public void testCreateLoadListAndDropIcechunkTable() {
    String location = repoLocation(REPO);
    // The repository root is stored with a trailing slash, and it is not one of the properties.
    IcechunkTable expected =
        new IcechunkTable(REPO.name(), location + "/", Map.of("owner", "alice"));

    LoadIcechunkTableResponse created =
        icechunkTableApi.createIcechunkTable(
            currentCatalogName, REPO, location, Map.of("owner", "alice"));
    assertThat(created.getTable()).isEqualTo(expected);
    assertThat(created.getStorageAccessConfigs()).isEmpty();

    LoadIcechunkTableResponse loaded = icechunkTableApi.loadIcechunkTable(currentCatalogName, REPO);
    assertThat(loaded.getTable()).isEqualTo(expected);
    assertThat(loaded.getStorageAccessConfigs()).isEmpty();
    assertThat(icechunkTableApi.listIcechunkTables(currentCatalogName, NS1)).containsExactly(REPO);

    assertThatThrownBy(() -> restCatalog.dropNamespace(NS1))
        .isInstanceOf(NamespaceNotEmptyException.class)
        .hasMessageContaining("Namespace ns1 is not empty");

    icechunkTableApi.dropIcechunkTable(currentCatalogName, REPO);

    assertThat(icechunkTableApi.listIcechunkTables(currentCatalogName, NS1)).isEmpty();
    assertErrorResponse(
        icechunkTableApi.load(currentCatalogName, REPO, null, null),
        NOT_FOUND,
        "Icechunk table does not exist: ns1.repo");
    assertThat(restCatalog.dropNamespace(NS1)).isTrue();
  }

  @Test
  public void testPaginatedListIcechunkTables() {
    List<TableIdentifier> tables =
        IntStream.range(0, 5).mapToObj(i -> TableIdentifier.of(NS1, "repo" + i)).toList();
    tables.forEach(
        table ->
            icechunkTableApi.createIcechunkTable(
                currentCatalogName, table, repoLocation(table), Map.of()));

    assertThat(icechunkTableApi.listIcechunkTables(currentCatalogName, NS1))
        .containsExactlyInAnyOrderElementsOf(tables);
    for (var pageSize : List.of(1, 2, 3, 5, 10)) {
      List<TableIdentifier> listed = new ArrayList<>();
      String pageToken = null;
      do {
        ListIcechunkTablesResponse response =
            icechunkTableApi.listIcechunkTables(
                currentCatalogName, NS1, pageToken, String.valueOf(pageSize));
        assertThat(response.getIdentifiers()).hasSizeLessThanOrEqualTo(pageSize);
        listed.addAll(response.getIdentifiers());
        pageToken = response.getNextPageToken();
      } while (pageToken != null);
      assertThat(listed)
          .as("Paginated results for pageSize = " + pageSize)
          .containsExactlyInAnyOrderElementsOf(tables);
    }
  }

  @Test
  public void testMissingIcechunkTableOrNamespaceIsNotFound() {
    TableIdentifier missing = TableIdentifier.of(NS1, "missing");
    String noSuchTable = "Icechunk table does not exist: ns1.missing";
    assertErrorResponse(
        icechunkTableApi.load(currentCatalogName, missing, null, null), NOT_FOUND, noSuchTable);
    assertErrorResponse(
        icechunkTableApi.load(currentCatalogName, missing, VENDED_CREDENTIALS, "read-write"),
        NOT_FOUND,
        noSuchTable);
    assertErrorResponse(
        icechunkTableApi.loadCredentials(currentCatalogName, missing, null),
        NOT_FOUND,
        noSuchTable);
    assertErrorResponse(icechunkTableApi.drop(currentCatalogName, missing), NOT_FOUND, noSuchTable);

    Namespace missingNamespace = Namespace.of("missing");
    String noSuchNamespace = "Namespace does not exist: missing";
    assertErrorResponse(
        icechunkTableApi.list(currentCatalogName, missingNamespace, null, null),
        NOT_FOUND,
        noSuchNamespace);
    assertErrorResponse(
        icechunkTableApi.create(
            currentCatalogName,
            missingNamespace,
            createRequest("repo", catalogBaseLocation + "/missing/repo", Map.of()),
            null),
        NOT_FOUND,
        noSuchNamespace);
  }

  @Test
  public void testCreateIcechunkTableWithTakenNameConflicts() {
    icechunkTableApi.createIcechunkTable(currentCatalogName, REPO, repoLocation(REPO), Map.of());
    restCatalog.createTable(ICEBERG_TABLE, SCHEMA);

    String otherLocation = catalogBaseLocation + "/ns1/other";
    assertErrorResponse(
        create(createRequest(REPO.name(), otherLocation, Map.of())),
        CONFLICT,
        "Icechunk table already exists: ns1.repo");
    assertErrorResponse(
        create(createRequest(ICEBERG_TABLE.name(), otherLocation, Map.of())),
        CONFLICT,
        "Table already exists: ns1.tbl");
    assertThat(icechunkTableApi.listIcechunkTables(currentCatalogName, NS1)).containsExactly(REPO);
  }

  @Test
  public void testCreateIcechunkTableRejectsForbiddenLocations() {
    icechunkTableApi.createIcechunkTable(currentCatalogName, REPO, repoLocation(REPO), Map.of());
    String icebergTableLocation = restCatalog.createTable(ICEBERG_TABLE, SCHEMA).location();

    // Outside the catalog's allowed locations.
    assertErrorResponse(
        create(createRequest("other", baseLocation + "/outside/other", Map.of())),
        FORBIDDEN,
        "Invalid locations");
    // Inside the allowed locations, but outside the namespace location.
    assertErrorResponse(
        create(createRequest("other", catalogBaseLocation + "/elsewhere/other", Map.of())),
        FORBIDDEN,
        "Invalid locations");
    // The namespace location itself, and the locations of the Icechunk repository and of the
    // Iceberg table, or locations inside them.
    for (String location :
        List.of(
            catalogBaseLocation + "/ns1",
            repoLocation(REPO),
            repoLocation(REPO) + "/other",
            icebergTableLocation,
            icebergTableLocation + "/other")) {
      assertErrorResponse(
          create(createRequest("other", location, Map.of())),
          FORBIDDEN,
          "conflicts with existing table or namespace");
    }
    assertThat(icechunkTableApi.listIcechunkTables(currentCatalogName, NS1)).containsExactly(REPO);

    // A sibling whose location only shares a string prefix with the repository does not overlap.
    TableIdentifier sibling = TableIdentifier.of(NS1, "repo_other");
    icechunkTableApi.createIcechunkTable(
        currentCatalogName, sibling, repoLocation(REPO) + "-other", Map.of());
    assertThat(icechunkTableApi.listIcechunkTables(currentCatalogName, NS1))
        .containsExactlyInAnyOrder(REPO, sibling);
  }

  @Test
  public void testInvalidIcechunkTableRequestsAreRejected() {
    String location = repoLocation(REPO);
    assertErrorResponse(
        create(createRequest(REPO.name(), null, Map.of())),
        BAD_REQUEST,
        "location: must not be null");
    assertErrorResponse(
        create(
            createRequest(
                REPO.name(),
                location,
                Map.of(PolarisEntityConstants.ENTITY_BASE_LOCATION, location))),
        BAD_REQUEST,
        "must not contain 'location'");
    assertErrorResponse(
        create(createRequest(REPO.name(), location, Map.of("polaris.reserved", "true"))),
        BAD_REQUEST,
        "reserved prefix");
    assertErrorResponse(
        create(createRequest("bad/name", location, Map.of())), BAD_REQUEST, "Entity name");
    assertErrorResponse(
        icechunkTableApi.create(
            currentCatalogName,
            NS1,
            createRequest(REPO.name(), location, Map.of()),
            "remote-signing"),
        BAD_REQUEST,
        "Unsupported access delegation mode 'remote-signing'");
    assertThat(icechunkTableApi.listIcechunkTables(currentCatalogName, NS1)).isEmpty();

    icechunkTableApi.createIcechunkTable(currentCatalogName, REPO, location, Map.of());
    assertErrorResponse(
        icechunkTableApi.load(currentCatalogName, REPO, "remote-signing", null),
        BAD_REQUEST,
        "Unsupported access delegation mode 'remote-signing'");
    assertErrorResponse(
        icechunkTableApi.load(currentCatalogName, REPO, VENDED_CREDENTIALS, "write"),
        BAD_REQUEST,
        "Unsupported access-mode 'write'");
    assertErrorResponse(
        icechunkTableApi.loadCredentials(currentCatalogName, REPO, "write"),
        BAD_REQUEST,
        "Unsupported access-mode 'write'");
  }

  @Test
  public void testGrantsOnIcechunkTable() {
    icechunkTableApi.createIcechunkTable(currentCatalogName, REPO, repoLocation(REPO), Map.of());
    managementApi.createCatalogRole(currentCatalogName, "catalogrole1");

    List<TableGrant> tableGrants =
        Arrays.stream(TablePrivilege.values())
            .map(PolarisIcechunkTableIntegrationTest::repoGrant)
            .toList();
    tableGrants.forEach(g -> managementApi.addGrant(currentCatalogName, "catalogrole1", g));

    assertThat(managementApi.listGrants(currentCatalogName, "catalogrole1").getGrants())
        .containsExactlyInAnyOrderElementsOf(tableGrants);
  }

  @Test
  public void testTableGrantsControlAccessToIcechunkTable() {
    icechunkTableApi.createIcechunkTable(currentCatalogName, REPO, repoLocation(REPO), Map.of());

    String readerRoleName = client.newEntityName("icechunk-reader-role");
    PrincipalWithCredentials reader =
        managementApi.createPrincipalWithRole(
            client.newEntityName("icechunk-reader"), readerRoleName);
    managementApi.createCatalogRole(currentCatalogName, "icechunk_reader");
    managementApi.addGrant(
        currentCatalogName, "icechunk_reader", repoGrant(TablePrivilege.TABLE_READ_DATA));
    managementApi.grantCatalogRoleToPrincipalRole(
        readerRoleName,
        currentCatalogName,
        managementApi.getCatalogRole(currentCatalogName, "icechunk_reader"));
    IcechunkTableApi readerApi = client.icechunkTableApi(client.obtainToken(reader));

    // TABLE_READ_DATA allows loading the table and read access to the repository. FILE storage
    // vends no credentials, so no storage access configs are returned.
    assertThat(readerApi.loadIcechunkTable(currentCatalogName, REPO).getTable().getName())
        .isEqualTo(REPO.name());
    try (Response response = readerApi.load(currentCatalogName, REPO, VENDED_CREDENTIALS, "read")) {
      assertThat(response.getStatus()).isEqualTo(OK.getStatusCode());
    }
    assertThat(
            readerApi
                .loadIcechunkTableCredentials(currentCatalogName, REPO, "read")
                .getStorageAccessConfigs())
        .isEmpty();

    // Write access to the repository requires TABLE_WRITE_DATA, and dropping the table TABLE_DROP.
    String writeDenied = "is not authorized for op LOAD_TABLE_WITH_WRITE_DELEGATION";
    assertErrorResponse(
        readerApi.load(currentCatalogName, REPO, VENDED_CREDENTIALS, "read-write"),
        FORBIDDEN,
        writeDenied);
    assertErrorResponse(
        readerApi.loadCredentials(currentCatalogName, REPO, "read-write"), FORBIDDEN, writeDenied);
    assertErrorResponse(
        readerApi.drop(currentCatalogName, REPO),
        FORBIDDEN,
        "is not authorized for op DROP_TABLE_WITHOUT_PURGE");

    managementApi.addGrant(
        currentCatalogName, "icechunk_reader", repoGrant(TablePrivilege.TABLE_WRITE_DATA));
    assertThat(
            readerApi
                .loadIcechunkTableCredentials(currentCatalogName, REPO, "read-write")
                .getStorageAccessConfigs())
        .isEmpty();
  }

  @Test
  public void testIcechunkTableEndpointsAreAdvertised() {
    try (Response response =
        catalogApi.request("v1/config", Map.of(), Map.of("warehouse", currentCatalogName)).get()) {
      assertThat(response.getStatus()).isEqualTo(OK.getStatusCode());
      assertThat(response.readEntity(ConfigResponse.class).endpoints())
          .containsAll(IcechunkTableEndpoints.ICECHUNK_TABLE_ENDPOINTS);
    }
  }

  @Test
  public void testIcebergApisDoNotOperateOnIcechunkTables() {
    icechunkTableApi.createIcechunkTable(currentCatalogName, REPO, repoLocation(REPO), Map.of());
    restCatalog.createTable(ICEBERG_TABLE, SCHEMA);

    assertThat(restCatalog.tableExists(REPO)).isFalse();
    assertThatThrownBy(() -> restCatalog.loadTable(REPO))
        .isInstanceOf(NoSuchTableException.class)
        .hasMessageContaining("Table does not exist: ns1.repo");
    assertThat(restCatalog.dropTable(REPO)).isFalse();
    assertThat(restCatalog.listTables(NS1)).containsExactly(ICEBERG_TABLE);
    assertThatThrownBy(() -> restCatalog.createTable(REPO, SCHEMA))
        .isInstanceOf(AlreadyExistsException.class)
        .hasMessageContaining("Icechunk table with same name already exists: ns1.repo");
    assertThatThrownBy(
            () ->
                restCatalog
                    .buildView(REPO)
                    .withSchema(SCHEMA)
                    .withDefaultNamespace(NS1)
                    .withQuery("spark", "select * from ns1.tbl")
                    .create())
        .isInstanceOf(AlreadyExistsException.class)
        .hasMessageContaining("Icechunk table with same name already exists: ns1.repo");
    assertThatThrownBy(() -> restCatalog.renameTable(ICEBERG_TABLE, REPO))
        .isInstanceOf(AlreadyExistsException.class)
        .hasMessageContaining("Cannot rename ns1.tbl to ns1.repo. Icechunk table already exists");

    // The Icechunk table is unchanged, and the Icechunk API does not see the Iceberg table.
    assertThat(
            icechunkTableApi.loadIcechunkTable(currentCatalogName, REPO).getTable().getLocation())
        .isEqualTo(repoLocation(REPO) + "/");
    assertThat(icechunkTableApi.listIcechunkTables(currentCatalogName, NS1)).containsExactly(REPO);
    assertErrorResponse(
        icechunkTableApi.load(currentCatalogName, ICEBERG_TABLE, null, null),
        NOT_FOUND,
        "Icechunk table does not exist: ns1.tbl");
  }

  private Response create(CreateIcechunkTableRequest request) {
    return icechunkTableApi.create(currentCatalogName, NS1, request, null);
  }

  private String repoLocation(TableIdentifier identifier) {
    return catalogBaseLocation
        + "/"
        + String.join("/", identifier.namespace().levels())
        + "/"
        + identifier.name();
  }

  private static CreateIcechunkTableRequest createRequest(
      String name, @Nullable String location, Map<String, String> properties) {
    return CreateIcechunkTableRequest.builder()
        .setName(name)
        .setLocation(location)
        .setProperties(properties)
        .build();
  }

  private static TableGrant repoGrant(TablePrivilege privilege) {
    return new TableGrant(
        List.of(NS1.levels()), REPO.name(), privilege, GrantResource.TypeEnum.TABLE);
  }

  private static void assertErrorResponse(
      Response response, Response.Status status, String message) {
    try (response) {
      assertThat(response.getStatus()).isEqualTo(status.getStatusCode());
      assertThat(response.readEntity(ErrorResponse.class).message()).contains(message);
    }
  }

  private static String stripTrailingSlash(String uri) {
    return uri.endsWith("/") && uri.length() > "file:///".length()
        ? uri.substring(0, uri.length() - 1)
        : uri;
  }
}
