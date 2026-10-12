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
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.rest.requests.CreateNamespaceRequest;
import org.apache.polaris.core.admin.model.AwsStorageConfigInfo;
import org.apache.polaris.core.admin.model.Catalog;
import org.apache.polaris.core.admin.model.CatalogProperties;
import org.apache.polaris.core.admin.model.CreateCatalogRequest;
import org.apache.polaris.core.admin.model.PolarisCatalog;
import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.apache.polaris.core.admin.model.UpdateCatalogRequest;
import org.apache.polaris.core.config.FeatureConfiguration;
import org.apache.polaris.core.context.RealmContext;
import org.apache.polaris.core.storage.StorageAccessProperty;
import org.apache.polaris.service.TestServices;
import org.apache.polaris.service.types.CreateIcechunkTableRequest;
import org.apache.polaris.service.types.ListIcechunkTablesResponse;
import org.apache.polaris.service.types.LoadIcechunkTableCredentialsResponse;
import org.apache.polaris.service.types.LoadIcechunkTableResponse;
import org.apache.polaris.service.types.StorageAccessConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import software.amazon.awssdk.policybuilder.iam.IamAction;
import software.amazon.awssdk.policybuilder.iam.IamCondition;
import software.amazon.awssdk.policybuilder.iam.IamConditionOperator;
import software.amazon.awssdk.policybuilder.iam.IamEffect;
import software.amazon.awssdk.policybuilder.iam.IamPolicy;
import software.amazon.awssdk.policybuilder.iam.IamResource;
import software.amazon.awssdk.policybuilder.iam.IamStatement;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

public class IcechunkTableCredentialVendingTest {

  private static final String CATALOG = "test-catalog";
  private static final String NAMESPACE = "ns";
  private static final String TABLE_NAME = "repo";
  private static final String ROLE_ARN = "arn:aws:iam::012345678901:role/jdoe";
  private static final String CATALOG_LOCATION = "s3://bucket/path/to/data";
  private static final String REPO_ROOT = CATALOG_LOCATION + "/ns/repo/";
  private static final String BUCKET_ARN = "arn:aws:s3:::bucket";
  private static final String REPO_OBJECTS_ARN = "arn:aws:s3:::bucket/path/to/data/ns/repo/*";
  private static final String REPO_KEY_PREFIX = "path/to/data/ns/repo/*";
  private static final String ENDPOINT = "https://s3.example.com";
  private static final String INTERNAL_ENDPOINT = "http://s3-internal.example.com:9000";
  private static final String REGION = "us-west-2";
  private static final String CREDENTIALS_PATH =
      "polaris/v1/test-catalog/namespaces/ns/icechunk-tables/repo/credentials";
  private static final String NO_CREDENTIALS_MESSAGE =
      "Credential vending was requested for table ns.repo, but no credentials are available";
  public static final String TEST_ACCESS_KEY = "test_access_key";
  public static final String SECRET_ACCESS_KEY = "secret_access_key";
  public static final String SESSION_TOKEN = "session_token";
  private static final Instant EXPIRATION = Instant.parse("2100-01-01T00:00:00Z");

  private static final Set<String> READ_OBJECT_ACTIONS =
      Set.of("s3:GetObject", "s3:GetObjectVersion");
  private static final Set<String> READ_WRITE_OBJECT_ACTIONS =
      Set.of("s3:GetObject", "s3:GetObjectVersion", "s3:PutObject", "s3:DeleteObject");

  private final List<AssumeRoleRequest> assumeRoleRequests = new ArrayList<>();
  private TestServices services;

  @BeforeEach
  public void before(TestInfo testInfo) {
    String realmName =
        "realm_%s_%s"
            .formatted(
                testInfo.getTestMethod().map(Method::getName).orElse("test"), System.nanoTime());
    RealmContext realmContext = () -> realmName;

    // Mock get subscoped creds, and capture every request
    StsClient stsClient = Mockito.mock(StsClient.class);
    when(stsClient.assumeRole(isA(AssumeRoleRequest.class)))
        .thenAnswer(
            invocation -> {
              assumeRoleRequests.add(invocation.getArgument(0));
              return AssumeRoleResponse.builder()
                  .credentials(
                      Credentials.builder()
                          .accessKeyId(TEST_ACCESS_KEY)
                          .secretAccessKey(SECRET_ACCESS_KEY)
                          .sessionToken(SESSION_TOKEN)
                          .expiration(EXPIRATION)
                          .build())
                  .build();
            });

    services =
        TestServices.builder()
            .config(
                Map.of(
                    "ENABLE_ICECHUNK_TABLES",
                    true,
                    "SUPPORTED_CATALOG_STORAGE_TYPES",
                    List.of("FILE", "S3")))
            .realmContext(realmContext)
            .stsClient(stsClient)
            .build();

    Catalog catalog =
        PolarisCatalog.builder()
            .setType(Catalog.TypeEnum.INTERNAL)
            .setName(CATALOG)
            .setProperties(new CatalogProperties(CATALOG_LOCATION))
            .setStorageConfigInfo(storageConfig(List.of(CATALOG_LOCATION)))
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
  }

  /** An S3-compatible store with an internal endpoint that only the server may use. */
  private static AwsStorageConfigInfo storageConfig(List<String> allowedLocations) {
    return storageConfigBuilder(allowedLocations).build();
  }

  /** The same store without STS: credential vending is requested, but no credentials exist. */
  private static AwsStorageConfigInfo storageConfigWithoutSts() {
    return storageConfigBuilder(List.of(CATALOG_LOCATION)).setStsUnavailable(true).build();
  }

  private static AwsStorageConfigInfo.Builder storageConfigBuilder(List<String> allowedLocations) {
    return AwsStorageConfigInfo.builder()
        .setStorageType(StorageConfigInfo.StorageTypeEnum.S3)
        .setRoleArn(ROLE_ARN)
        .setAllowedLocations(allowedLocations)
        .setRegion(REGION)
        .setEndpoint(ENDPOINT)
        .setEndpointInternal(INTERNAL_ENDPOINT)
        .setPathStyleAccess(true)
        .setKmsUnavailable(true);
  }

  /** Replaces the catalog's storage configuration and properties. */
  private void updateCatalog(AwsStorageConfigInfo storageConfig, Map<String, String> properties) {
    Catalog current;
    try (Response response =
        services
            .catalogsApi()
            .getCatalog(CATALOG, services.realmContext(), services.securityContext())) {
      current = response.readEntity(Catalog.class);
    }
    UpdateCatalogRequest updateRequest =
        UpdateCatalogRequest.builder()
            .setCurrentEntityVersion(current.getEntityVersion())
            .setStorageConfigInfo(storageConfig)
            .setProperties(properties)
            .build();
    try (Response response =
        services
            .catalogsApi()
            .updateCatalog(
                CATALOG, updateRequest, services.realmContext(), services.securityContext())) {
      assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
    }
  }

  private LoadIcechunkTableResponse create(String accessDelegation) {
    return create(accessDelegation, CATALOG_LOCATION + "/ns/repo");
  }

  private LoadIcechunkTableResponse create(String accessDelegation, String location) {
    try (Response response =
        services
            .icechunkTableApi()
            .createIcechunkTable(
                CATALOG,
                NAMESPACE,
                CreateIcechunkTableRequest.builder()
                    .setName(TABLE_NAME)
                    .setLocation(location)
                    .build(),
                accessDelegation,
                services.realmContext(),
                services.securityContext())) {
      assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
      return (LoadIcechunkTableResponse) response.getEntity();
    }
  }

  private LoadIcechunkTableResponse load(String accessDelegation, String accessMode) {
    try (Response response =
        services
            .icechunkTableApi()
            .loadIcechunkTable(
                CATALOG,
                NAMESPACE,
                TABLE_NAME,
                accessDelegation,
                accessMode,
                services.realmContext(),
                services.securityContext())) {
      assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
      return (LoadIcechunkTableResponse) response.getEntity();
    }
  }

  private LoadIcechunkTableCredentialsResponse loadCredentials(String accessMode) {
    try (Response response =
        services
            .icechunkTableApi()
            .loadIcechunkTableCredentials(
                CATALOG,
                NAMESPACE,
                TABLE_NAME,
                accessMode,
                services.realmContext(),
                services.securityContext())) {
      assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
      return (LoadIcechunkTableCredentialsResponse) response.getEntity();
    }
  }

  /**
   * Checks the single config vended for the repository: its prefix, credentials, the public storage
   * settings and the refresh endpoint for {@code grantedAccessMode}. The internal endpoint never
   * reaches the client.
   */
  private static void assertVendedConfig(
      List<StorageAccessConfig> configs, IcechunkTableAccessMode grantedAccessMode) {
    assertThat(configs).hasSize(1);
    StorageAccessConfig config = configs.getFirst();
    assertThat(config.getPrefix()).isEqualTo(REPO_ROOT);
    assertThat(config.getConfig())
        .containsEntry(StorageAccessProperty.AWS_KEY_ID.getPropertyName(), TEST_ACCESS_KEY)
        .containsEntry(StorageAccessProperty.AWS_SECRET_KEY.getPropertyName(), SECRET_ACCESS_KEY)
        .containsEntry(StorageAccessProperty.AWS_TOKEN.getPropertyName(), SESSION_TOKEN)
        .containsEntry(
            StorageAccessProperty.AWS_SESSION_TOKEN_EXPIRES_AT_MS.getPropertyName(),
            String.valueOf(EXPIRATION.toEpochMilli()))
        .containsEntry(StorageAccessProperty.AWS_ENDPOINT.getPropertyName(), ENDPOINT)
        .containsEntry(StorageAccessProperty.AWS_PATH_STYLE_ACCESS.getPropertyName(), "true")
        .containsEntry(StorageAccessProperty.CLIENT_REGION.getPropertyName(), REGION)
        .containsEntry(
            StorageAccessProperty.AWS_REFRESH_CREDENTIALS_ENDPOINT.getPropertyName(),
            CREDENTIALS_PATH + "?access-mode=" + grantedAccessMode.queryValue())
        .doesNotContainValue(INTERNAL_ENDPOINT)
        .hasSize(8);
  }

  /**
   * Checks that the session policy grants exactly {@code expectedObjectActions} on the objects
   * under the repository root, list access to that prefix only and the bucket location, and nothing
   * else.
   */
  private static void assertPolicyScopedToRepository(
      AssumeRoleRequest request, Set<String> expectedObjectActions) {
    assertThat(request.roleArn()).isEqualTo(ROLE_ARN);
    Set<String> objectActions = new HashSet<>();
    Set<String> bucketActions = new HashSet<>();
    for (IamStatement statement : IamPolicy.fromJson(request.policy()).statements()) {
      assertThat(statement.effect()).isEqualTo(IamEffect.ALLOW);
      List<String> actions = statement.actions().stream().map(IamAction::value).toList();
      List<String> resources = statement.resources().stream().map(IamResource::value).toList();
      if (actions.equals(List.of("s3:ListBucket"))) {
        assertThat(resources).containsExactly(BUCKET_ARN);
        assertThat(statement.conditions())
            .containsExactly(
                IamCondition.builder()
                    .operator(IamConditionOperator.STRING_LIKE)
                    .key("s3:prefix")
                    .value(REPO_KEY_PREFIX)
                    .build());
        bucketActions.addAll(actions);
      } else if (actions.equals(List.of("s3:GetBucketLocation"))) {
        assertThat(resources).containsExactly(BUCKET_ARN);
        assertThat(statement.conditions()).isEmpty();
        bucketActions.addAll(actions);
      } else {
        assertThat(resources).containsExactly(REPO_OBJECTS_ARN);
        assertThat(statement.conditions()).isEmpty();
        objectActions.addAll(actions);
      }
    }
    assertThat(bucketActions).containsExactlyInAnyOrder("s3:ListBucket", "s3:GetBucketLocation");
    assertThat(objectActions).containsExactlyInAnyOrderElementsOf(expectedObjectActions);
  }

  @Test
  public void testCreateWithDelegationVendsReadWriteCredentials() {
    LoadIcechunkTableResponse response = create("vended-credentials");

    assertThat(response.getTable().getLocation()).isEqualTo(REPO_ROOT);
    assertVendedConfig(response.getStorageAccessConfigs(), IcechunkTableAccessMode.READ_WRITE);
    assertThat(assumeRoleRequests).hasSize(1);
    assertPolicyScopedToRepository(assumeRoleRequests.getFirst(), READ_WRITE_OBJECT_ACTIONS);
  }

  @Test
  public void testLoadWithoutDelegationVendsNothing() {
    create(null);

    LoadIcechunkTableResponse response =
        load(null, IcechunkTableAccessMode.READ_WRITE.queryValue());

    assertThat(response.getTable().getLocation()).isEqualTo(REPO_ROOT);
    assertThat(response.getStorageAccessConfigs()).isEmpty();
    assertThat(assumeRoleRequests).isEmpty();
  }

  static Stream<Arguments> testVendedCredentialsFollowAccessMode() {
    return Stream.of(
        Arguments.of(false, null, IcechunkTableAccessMode.READ),
        Arguments.of(false, "", IcechunkTableAccessMode.READ),
        Arguments.of(false, "read", IcechunkTableAccessMode.READ),
        Arguments.of(false, "read-write", IcechunkTableAccessMode.READ_WRITE),
        Arguments.of(true, null, IcechunkTableAccessMode.READ),
        Arguments.of(true, "", IcechunkTableAccessMode.READ),
        Arguments.of(true, "read", IcechunkTableAccessMode.READ),
        Arguments.of(true, "read-write", IcechunkTableAccessMode.READ_WRITE));
  }

  @ParameterizedTest
  @MethodSource
  public void testVendedCredentialsFollowAccessMode(
      boolean credentialsEndpoint,
      String requestedAccessMode,
      IcechunkTableAccessMode grantedAccessMode) {
    create(null);

    List<StorageAccessConfig> configs =
        credentialsEndpoint
            ? loadCredentials(requestedAccessMode).getStorageAccessConfigs()
            : load("vended-credentials", requestedAccessMode).getStorageAccessConfigs();

    assertVendedConfig(configs, grantedAccessMode);
    assertThat(assumeRoleRequests).hasSize(1);
    assertPolicyScopedToRepository(
        assumeRoleRequests.getFirst(),
        grantedAccessMode == IcechunkTableAccessMode.READ
            ? READ_OBJECT_ACTIONS
            : READ_WRITE_OBJECT_ACTIONS);
  }

  @Test
  public void testTighteningAllowedLocationsStopsVending() {
    create(null);

    // Move the catalog away from the repository's location.
    String otherLocation = "s3://bucket/other-data";
    updateCatalog(
        storageConfig(List.of(otherLocation)), Map.of("default-base-location", otherLocation));

    assertThatThrownBy(() -> loadCredentials("read"))
        .isInstanceOf(ForbiddenException.class)
        .hasMessageContaining(
            "Icechunk table 'ns.repo' has a location that the catalog no longer allows");
    assertThatThrownBy(() -> load("vended-credentials", "read"))
        .isInstanceOf(ForbiddenException.class)
        .hasMessageContaining(
            "Icechunk table 'ns.repo' has a location that the catalog no longer allows");
    assertThat(assumeRoleRequests).isEmpty();
    // Loading without credentials still works.
    assertThat(load(null, null).getTable().getLocation()).isEqualTo(REPO_ROOT);
  }

  @Test
  public void testVendingRevalidatesAgainstNamespaceLocation() {
    // Create the repository outside of its namespace while the catalog allows that. Allowing
    // overlapping locations as well keeps the namespace-scoped overlap check out of the way.
    updateCatalog(
        storageConfig(List.of(CATALOG_LOCATION)),
        Map.of(
            "default-base-location",
            CATALOG_LOCATION,
            FeatureConfiguration.ALLOW_UNSTRUCTURED_TABLE_LOCATION.catalogConfig(),
            "true",
            FeatureConfiguration.ALLOW_TABLE_LOCATION_OVERLAP.catalogConfig(),
            "true"));
    create(null, CATALOG_LOCATION + "/elsewhere/repo");

    // The allowed locations still contain the repository, but its namespace location does not.
    updateCatalog(
        storageConfig(List.of(CATALOG_LOCATION)),
        Map.of("default-base-location", CATALOG_LOCATION));

    assertThatThrownBy(() -> loadCredentials("read"))
        .isInstanceOf(ForbiddenException.class)
        .hasMessageContaining(
            "Icechunk table 'ns.repo' has a location that the catalog no longer allows");
    assertThatThrownBy(() -> load("vended-credentials", "read"))
        .isInstanceOf(ForbiddenException.class)
        .hasMessageContaining(
            "Icechunk table 'ns.repo' has a location that the catalog no longer allows");
    assertThat(assumeRoleRequests).isEmpty();
    assertThat(load(null, null).getTable().getLocation())
        .isEqualTo(CATALOG_LOCATION + "/elsewhere/repo/");
  }

  @Test
  public void testVendingWithoutAvailableCredentialsIsRejected() {
    create(null);
    updateCatalog(storageConfigWithoutSts(), Map.of("default-base-location", CATALOG_LOCATION));

    // The REST API maps this exception to 400 Bad Request.
    assertThatThrownBy(() -> loadCredentials("read"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(NO_CREDENTIALS_MESSAGE);
    assertThatThrownBy(() -> load("vended-credentials", "read"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(NO_CREDENTIALS_MESSAGE);
    assertThat(assumeRoleRequests).isEmpty();
    assertThat(load(null, null).getTable().getLocation()).isEqualTo(REPO_ROOT);
  }

  @Test
  public void testCreateWithDelegationKeepsTableWhenVendingFails() {
    updateCatalog(storageConfigWithoutSts(), Map.of("default-base-location", CATALOG_LOCATION));

    assertThatThrownBy(() -> create("vended-credentials"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(NO_CREDENTIALS_MESSAGE);
    assertThat(assumeRoleRequests).isEmpty();
    // The table is registered before the credentials are requested, and stays registered.
    assertThat(load(null, null).getTable().getLocation()).isEqualTo(REPO_ROOT);
    assertThatThrownBy(() -> create(null))
        .isInstanceOf(AlreadyExistsException.class)
        .hasMessage("Icechunk table already exists: ns.repo");
  }

  @Test
  public void testNestedNamespaceRefreshEndpoint() {
    try (Response response =
        services
            .restApi()
            .createNamespace(
                CATALOG,
                CreateNamespaceRequest.builder()
                    .withNamespace(Namespace.of(NAMESPACE, "era5"))
                    .build(),
                null,
                services.realmContext(),
                services.securityContext())) {
      assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
    }
    // The levels of a namespace path parameter are separated by the unit separator.
    String namespace = NAMESPACE + "\u001Fera5";
    try (Response response =
        services
            .icechunkTableApi()
            .createIcechunkTable(
                CATALOG,
                namespace,
                CreateIcechunkTableRequest.builder()
                    .setName(TABLE_NAME)
                    .setLocation(CATALOG_LOCATION + "/ns/era5/repo")
                    .build(),
                null,
                services.realmContext(),
                services.securityContext())) {
      assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
    }

    try (Response response =
        services
            .icechunkTableApi()
            .loadIcechunkTableCredentials(
                CATALOG,
                namespace,
                TABLE_NAME,
                "read",
                services.realmContext(),
                services.securityContext())) {
      assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
      List<StorageAccessConfig> configs =
          ((LoadIcechunkTableCredentialsResponse) response.getEntity()).getStorageAccessConfigs();
      assertThat(configs).hasSize(1);
      assertThat(configs.getFirst().getPrefix()).isEqualTo(CATALOG_LOCATION + "/ns/era5/repo/");
      assertThat(configs.getFirst().getConfig())
          .containsEntry(
              StorageAccessProperty.AWS_REFRESH_CREDENTIALS_ENDPOINT.getPropertyName(),
              "polaris/v1/test-catalog/namespaces/ns%1Fera5/icechunk-tables/repo/credentials"
                  + "?access-mode=read");
    }
    try (Response response =
        services
            .icechunkTableApi()
            .listIcechunkTables(
                CATALOG,
                namespace,
                null,
                null,
                services.realmContext(),
                services.securityContext())) {
      assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
      assertThat(((ListIcechunkTablesResponse) response.getEntity()).getIdentifiers())
          .containsExactly(TableIdentifier.of(NAMESPACE, "era5", TABLE_NAME));
    }
  }
}
