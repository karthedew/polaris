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

import static org.apache.iceberg.types.Types.NestedField.required;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import io.quarkus.test.junit.QuarkusMock;
import jakarta.inject.Inject;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.exceptions.NamespaceNotEmptyException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.ServiceFailureException;
import org.apache.iceberg.types.Types;
import org.apache.polaris.core.PolarisCallContext;
import org.apache.polaris.core.PolarisDiagnostics;
import org.apache.polaris.core.admin.model.AwsStorageConfigInfo;
import org.apache.polaris.core.admin.model.CreateCatalogRequest;
import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.apache.polaris.core.auth.PolarisAuthorizerImpl;
import org.apache.polaris.core.auth.PolarisPrincipal;
import org.apache.polaris.core.auth.PolarisPrincipalAttributes;
import org.apache.polaris.core.collection.ImmutableAttributeMap;
import org.apache.polaris.core.config.BehaviorChangeConfiguration;
import org.apache.polaris.core.config.FeatureConfiguration;
import org.apache.polaris.core.config.RealmConfig;
import org.apache.polaris.core.context.CallContext;
import org.apache.polaris.core.context.RealmContext;
import org.apache.polaris.core.entity.CatalogEntity;
import org.apache.polaris.core.entity.PolarisEntity;
import org.apache.polaris.core.entity.PolarisEntityConstants;
import org.apache.polaris.core.entity.PolarisEntitySubType;
import org.apache.polaris.core.entity.PolarisPrivilege;
import org.apache.polaris.core.entity.PrincipalEntity;
import org.apache.polaris.core.entity.table.IcechunkTableEntity;
import org.apache.polaris.core.identity.provider.ServiceIdentityProvider;
import org.apache.polaris.core.persistence.PolarisMetaStoreManager;
import org.apache.polaris.core.persistence.PolarisResolvedPathWrapper;
import org.apache.polaris.core.persistence.dao.entity.BaseResult;
import org.apache.polaris.core.persistence.dao.entity.DropEntityResult;
import org.apache.polaris.core.persistence.dao.entity.EntityResult;
import org.apache.polaris.core.persistence.pagination.Page;
import org.apache.polaris.core.persistence.pagination.PageToken;
import org.apache.polaris.core.persistence.resolver.PolarisResolutionManifest;
import org.apache.polaris.core.persistence.resolver.PolarisResolutionManifestCatalogView;
import org.apache.polaris.core.persistence.resolver.ResolutionManifestFactory;
import org.apache.polaris.core.persistence.resolver.ResolvedPathKey;
import org.apache.polaris.core.persistence.resolver.ResolverFactory;
import org.apache.polaris.core.persistence.resolver.ResolverPath;
import org.apache.polaris.core.persistence.resolver.ResolverStatus;
import org.apache.polaris.core.secrets.UserSecretsManager;
import org.apache.polaris.core.storage.PolarisStorageIntegrationProvider;
import org.apache.polaris.core.storage.aws.AwsCredentialsStorageIntegration;
import org.apache.polaris.core.storage.aws.AwsStorageConfigurationInfo;
import org.apache.polaris.core.storage.cache.StorageCredentialCache;
import org.apache.polaris.service.admin.PolarisAdminService;
import org.apache.polaris.service.admin.PolarisAdminServiceTestSupport;
import org.apache.polaris.service.catalog.PolarisPassthroughResolutionView;
import org.apache.polaris.service.catalog.common.LocationOverlapValidator;
import org.apache.polaris.service.catalog.generic.PolarisGenericTableCatalog;
import org.apache.polaris.service.catalog.iceberg.LocalIcebergCatalog;
import org.apache.polaris.service.catalog.io.FileIOFactory;
import org.apache.polaris.service.catalog.io.StorageAccessConfigProvider;
import org.apache.polaris.service.config.ReservedProperties;
import org.apache.polaris.service.context.catalog.PolarisPrincipalHolder;
import org.apache.polaris.service.events.PolarisEventMetadataFactory;
import org.apache.polaris.service.events.listeners.InMemoryEventCollector;
import org.apache.polaris.service.storage.PolarisStorageIntegrationProviderImpl;
import org.apache.polaris.service.task.TaskExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

public abstract class AbstractIcechunkTableCatalogTest {

  protected static final Namespace NS = Namespace.of("ns");
  protected static final TableIdentifier TABLE = TableIdentifier.of(NS, "repo");
  public static final String CATALOG_NAME = "polaris-catalog";
  public static final String TEST_ACCESS_KEY = "test_access_key";
  public static final String SECRET_ACCESS_KEY = "secret_access_key";
  public static final String SESSION_TOKEN = "session_token";
  private static final String STORAGE_LOCATION = "s3://my-bucket/path/to/data";
  private static final String NS_LOCATION = STORAGE_LOCATION + "/ns/";
  private static final String EXTERNAL_CATALOG_MESSAGE =
      "Icechunk tables are not supported in external catalogs";

  protected static final Schema SCHEMA =
      new Schema(
          required(3, "id", Types.IntegerType.get(), "unique ID"),
          required(4, "data", Types.StringType.get()));

  @Inject ServiceIdentityProvider serviceIdentityProvider;
  @Inject StorageCredentialCache storageCredentialCache;
  @Inject PolarisStorageIntegrationProvider storageIntegrationProvider;
  @Inject PolarisDiagnostics diagServices;
  @Inject ResolverFactory resolverFactory;
  @Inject ResolutionManifestFactory resolutionManifestFactory;
  @Inject PolarisEventMetadataFactory eventMetadataFactory;
  @Inject PolarisMetaStoreManager metaStoreManager;
  @Inject UserSecretsManager userSecretsManager;
  @Inject CallContext callContext;
  @Inject RealmConfig realmConfig;
  @Inject StorageAccessConfigProvider storageAccessConfigProvider;
  @Inject FileIOFactory fileIOFactory;
  @Inject PolarisPrincipalHolder polarisPrincipalHolder;

  private IcechunkTableCatalog icechunkTableCatalog;
  private LocalIcebergCatalog icebergCatalog;
  private PolarisGenericTableCatalog genericTableCatalog;
  private String realmName;
  private PolarisCallContext polarisContext;
  private PolarisAdminService adminService;
  private PolarisPrincipal authenticatedRoot;

  @BeforeAll
  public static void setUpMocks() {
    PolarisStorageIntegrationProviderImpl mock =
        Mockito.mock(PolarisStorageIntegrationProviderImpl.class);
    QuarkusMock.installMockForType(mock, PolarisStorageIntegrationProviderImpl.class);
  }

  protected void bootstrapRealm(String realmName) {}

  @BeforeEach
  public void before(TestInfo testInfo) {
    storageCredentialCache.invalidateAll();

    realmName =
        "realm_%s_%s"
            .formatted(
                testInfo.getTestMethod().map(Method::getName).orElse("test"), System.nanoTime());
    bootstrapRealm(realmName);

    RealmContext realmContext = () -> realmName;
    QuarkusMock.installMockForType(realmContext, RealmContext.class);
    polarisContext = callContext.getPolarisCallContext();

    PrincipalEntity rootPrincipal =
        metaStoreManager.findRootPrincipal(polarisContext).orElseThrow();
    authenticatedRoot =
        PolarisPrincipal.of(
            rootPrincipal.getName(),
            ImmutableAttributeMap.builder()
                .put(PolarisPrincipalAttributes.PRINCIPAL_ENTITY_ATTRIBUTE_KEY, rootPrincipal)
                .put(PolarisPrincipalAttributes.PRINCIPAL_ROLE_ALL_ATTRIBUTE_KEY, true)
                .build(),
            Set.of());
    polarisPrincipalHolder.set(authenticatedRoot);

    adminService =
        PolarisAdminServiceTestSupport.newAdminService(
            polarisContext,
            resolutionManifestFactory,
            metaStoreManager,
            userSecretsManager,
            serviceIdentityProvider,
            authenticatedRoot,
            new PolarisAuthorizerImpl(),
            ReservedProperties.NONE);

    StsClient stsClient = Mockito.mock(StsClient.class);
    when(stsClient.assumeRole(isA(AssumeRoleRequest.class)))
        .thenReturn(
            AssumeRoleResponse.builder()
                .credentials(
                    Credentials.builder()
                        .accessKeyId(TEST_ACCESS_KEY)
                        .secretAccessKey(SECRET_ACCESS_KEY)
                        .sessionToken(SESSION_TOKEN)
                        .build())
                .build());
    AwsStorageConfigurationInfo mockAwsConfig =
        AwsStorageConfigurationInfo.builder()
            .roleARN("arn:aws:iam::012345678901:role/mock")
            .build();
    AwsCredentialsStorageIntegration storageIntegration =
        new AwsCredentialsStorageIntegration(
            (destination) -> stsClient,
            config -> Optional.empty(),
            storageCredentialCache,
            mockAwsConfig,
            callContext.getRealmConfig());
    when(storageIntegrationProvider.getStorageIntegration(Mockito.anyList()))
        .thenReturn(storageIntegration);

    // Structured locations with the list-based overlap check: this profile leaves
    // ALLOW_UNSTRUCTURED_TABLE_LOCATION, ALLOW_TABLE_LOCATION_OVERLAP and OPTIMIZED_SIBLING_CHECK
    // off.
    createCatalog(CATALOG_NAME, "INTERNAL", STORAGE_LOCATION, Map.of());
    icebergCatalog = newIcebergCatalog(CATALOG_NAME);
    genericTableCatalog =
        new PolarisGenericTableCatalog(
            metaStoreManager, polarisContext, passthroughView(CATALOG_NAME));
    genericTableCatalog.initialize(CATALOG_NAME, Map.of());
    icechunkTableCatalog = newIcechunkTableCatalog(CATALOG_NAME);
    icebergCatalog.createNamespace(NS);
  }

  @AfterEach
  public void after() {
    metaStoreManager.purge(polarisContext);
  }

  private PolarisPassthroughResolutionView passthroughView(String catalogName) {
    return new PolarisPassthroughResolutionView(
        resolutionManifestFactory, authenticatedRoot, catalogName);
  }

  private void createCatalog(
      String catalogName, String catalogType, String location, Map<String, String> properties) {
    CatalogEntity.Builder catalog =
        new CatalogEntity.Builder()
            .setName(catalogName)
            .setCatalogType(catalogType)
            .setDefaultBaseLocation(location)
            .setStorageConfigurationInfo(
                realmConfig,
                AwsStorageConfigInfo.builder()
                    .setRoleArn("arn:aws:iam::012345678901:role/jdoe")
                    .setExternalId("externalId")
                    .setUserArn("aws::a:user:arn")
                    .setStorageType(StorageConfigInfo.StorageTypeEnum.S3)
                    .setAllowedLocations(List.of(location))
                    .build());
    properties.forEach(catalog::addProperty);
    adminService.createCatalog(
        new CreateCatalogRequest(catalog.build().asCatalog(serviceIdentityProvider)));
  }

  private LocalIcebergCatalog newIcebergCatalog(String catalogName) {
    TaskExecutor taskExecutor = Mockito.mock();
    LocalIcebergCatalog catalog =
        new LocalIcebergCatalog(
            diagServices,
            resolverFactory,
            metaStoreManager,
            polarisContext,
            passthroughView(catalogName),
            authenticatedRoot,
            taskExecutor,
            storageAccessConfigProvider,
            fileIOFactory,
            new InMemoryEventCollector(),
            eventMetadataFactory);
    catalog.initialize(
        catalogName,
        ImmutableMap.of(
            CatalogProperties.FILE_IO_IMPL, "org.apache.iceberg.inmemory.InMemoryFileIO"));
    return catalog;
  }

  private IcechunkTableCatalog newIcechunkTableCatalog(String catalogName) {
    return newIcechunkTableCatalog(catalogName, metaStoreManager);
  }

  private IcechunkTableCatalog newIcechunkTableCatalog(
      String catalogName, PolarisMetaStoreManager catalogMetaStoreManager) {
    return newIcechunkTableCatalog(passthroughView(catalogName), catalogMetaStoreManager);
  }

  private IcechunkTableCatalog newIcechunkTableCatalog(
      PolarisResolutionManifestCatalogView resolvedEntityView,
      PolarisMetaStoreManager catalogMetaStoreManager) {
    return new IcechunkTableCatalog(
        catalogMetaStoreManager,
        polarisContext,
        resolvedEntityView,
        new LocationOverlapValidator(
            polarisContext.getRealmConfig(),
            catalogMetaStoreManager,
            polarisContext,
            referenceCatalogName ->
                resolutionManifestFactory.createResolutionManifest(
                    authenticatedRoot, referenceCatalogName)));
  }

  /** Creates a catalog with the given catalog properties, and namespace {@link #NS} in it. */
  private IcechunkTableCatalog newCatalogWithNamespace(
      String catalogName, String location, Map<String, String> properties) {
    createCatalog(catalogName, "INTERNAL", location, properties);
    newIcebergCatalog(catalogName).createNamespace(NS);
    return newIcechunkTableCatalog(catalogName);
  }

  private IcechunkTableCatalogHandler newHandler(String catalogName) {
    return ImmutableIcechunkTableCatalogHandler.builder()
        .catalogName(catalogName)
        .polarisPrincipal(authenticatedRoot)
        .callContext(callContext)
        .resolutionManifestFactory(resolutionManifestFactory)
        .metaStoreManager(metaStoreManager)
        .authorizer(new PolarisAuthorizerImpl())
        .storageAccessConfigProvider(storageAccessConfigProvider)
        .build();
  }

  /** Creates a table-like entity of the given subtype under {@link #NS}, at a default location. */
  private void createTableLike(PolarisEntitySubType subType, TableIdentifier identifier) {
    switch (subType) {
      case ICEBERG_TABLE -> icebergCatalog.createTable(identifier, SCHEMA);
      case ICEBERG_VIEW ->
          icebergCatalog
              .buildView(identifier)
              .withSchema(SCHEMA)
              .withDefaultNamespace(NS)
              .withQuery("spark", "SELECT 1")
              .create();
      case GENERIC_TABLE ->
          genericTableCatalog.createGenericTable(identifier, "format", null, "doc", Map.of());
      case ICECHUNK_TABLE ->
          icechunkTableCatalog.createIcechunkTable(
              identifier, NS_LOCATION + identifier.name(), Map.of());
      default -> throw new IllegalArgumentException("Unexpected subtype: " + subType);
    }
  }

  private PolarisEntitySubType tableLikeSubType(TableIdentifier identifier) {
    return passthroughView(CATALOG_NAME)
        .getPassthroughResolvedPath(
            ResolvedPathKey.ofTableLike(identifier), PolarisEntitySubType.ANY_SUBTYPE)
        .getRawLeafEntity()
        .getSubType();
  }

  private void assertIcechunkTableDoesNotExist(TableIdentifier identifier) {
    assertThatThrownBy(() -> icechunkTableCatalog.loadIcechunkTable(identifier))
        .isInstanceOf(NoSuchTableException.class)
        .hasMessage("Icechunk table does not exist: %s", identifier);
  }

  @ParameterizedTest
  @ValueSource(strings = {"repo", "repo/"})
  public void testCreateIcechunkTableRoundTrip(String relativeLocation) {
    Map<String, String> properties = Map.of("a", "b", "c", "d");
    IcechunkTableEntity created =
        icechunkTableCatalog.createIcechunkTable(TABLE, NS_LOCATION + relativeLocation, properties);

    IcechunkTableEntity loaded = icechunkTableCatalog.loadIcechunkTable(TABLE);
    assertThat(loaded.getId()).isEqualTo(created.getId());
    assertThat(loaded.getName()).isEqualTo(TABLE.name());
    assertThat(loaded.getSubType()).isEqualTo(PolarisEntitySubType.ICECHUNK_TABLE);
    // The location is normalized to end with a slash.
    assertThat(loaded.getBaseLocation()).isEqualTo(NS_LOCATION + "repo/");
    assertThat(loaded.getPropertiesAsMap())
        .containsAllEntriesOf(properties)
        .containsEntry(PolarisEntityConstants.ENTITY_BASE_LOCATION, NS_LOCATION + "repo/")
        .hasSize(properties.size() + 1);
  }

  @Test
  public void testLocationPropertyCannotReplaceValidatedLocation() {
    IcechunkTableEntity created =
        icechunkTableCatalog.createIcechunkTable(
            TABLE,
            NS_LOCATION + "repo",
            Map.of(PolarisEntityConstants.ENTITY_BASE_LOCATION, "s3://other-bucket/repo/"));

    assertThat(created.getBaseLocation()).isEqualTo(NS_LOCATION + "repo/");
    assertThat(icechunkTableCatalog.loadIcechunkTable(TABLE).getBaseLocation())
        .isEqualTo(NS_LOCATION + "repo/");
  }

  @Test
  public void testDropIcechunkTable() {
    icechunkTableCatalog.createIcechunkTable(TABLE, NS_LOCATION + "repo", Map.of());

    icechunkTableCatalog.dropIcechunkTable(TABLE);

    assertIcechunkTableDoesNotExist(TABLE);
    assertThat(icechunkTableCatalog.listIcechunkTables(NS, PageToken.readEverything()).items())
        .isEmpty();
    // The location is free again.
    assertThatCode(
            () -> icechunkTableCatalog.createIcechunkTable(TABLE, NS_LOCATION + "repo", Map.of()))
        .doesNotThrowAnyException();
  }

  @Test
  public void testDropDoesNotScheduleCleanupTask() {
    icechunkTableCatalog.createIcechunkTable(TABLE, NS_LOCATION + "repo", Map.of());

    icechunkTableCatalog.dropIcechunkTable(TABLE);

    assertThat(
            metaStoreManager
                .loadTasks(polarisContext, "testExecutor", PageToken.fromLimit(10))
                .getEntities())
        .isEmpty();
  }

  @Test
  public void testLoadNonExistentIcechunkTable() {
    assertIcechunkTableDoesNotExist(TABLE);
  }

  @Test
  public void testDropNonExistentIcechunkTable() {
    assertThatThrownBy(() -> icechunkTableCatalog.dropIcechunkTable(TABLE))
        .isInstanceOf(NoSuchTableException.class)
        .hasMessage("Icechunk table does not exist: %s", TABLE);
  }

  @Test
  public void testDropIcechunkTableInNonExistentNamespace() {
    TableIdentifier identifier = TableIdentifier.of("missing", "repo");
    assertThatThrownBy(() -> icechunkTableCatalog.dropIcechunkTable(identifier))
        .isInstanceOf(NoSuchTableException.class)
        .hasMessage("Icechunk table does not exist: %s", identifier);
  }

  @ParameterizedTest
  @EnumSource(
      value = PolarisEntitySubType.class,
      names = {"ICEBERG_TABLE", "ICEBERG_VIEW", "GENERIC_TABLE"})
  public void testLoadAndDropIgnoreOtherTableLikeEntities(PolarisEntitySubType subType) {
    createTableLike(subType, TABLE);

    assertIcechunkTableDoesNotExist(TABLE);
    assertThatThrownBy(() -> icechunkTableCatalog.dropIcechunkTable(TABLE))
        .isInstanceOf(NoSuchTableException.class)
        .hasMessage("Icechunk table does not exist: %s", TABLE);
    assertThat(tableLikeSubType(TABLE)).isEqualTo(subType);
  }

  @ParameterizedTest
  @CsvSource({
    "ICEBERG_TABLE, Table",
    "ICEBERG_VIEW, View",
    "GENERIC_TABLE, Generic table",
    "ICECHUNK_TABLE, Icechunk table"
  })
  public void testCreateRejectsExistingTableLikeEntity(PolarisEntitySubType subType, String label) {
    createTableLike(subType, TABLE);

    // The name conflict is reported before the location is validated: this location is outside
    // the allowed locations.
    assertThatThrownBy(
            () ->
                icechunkTableCatalog.createIcechunkTable(
                    TABLE, "s3://other-bucket/ns/repo", Map.of()))
        .isInstanceOf(AlreadyExistsException.class)
        .hasMessage("%s already exists: %s", label, TABLE);
    assertThat(tableLikeSubType(TABLE)).isEqualTo(subType);
  }

  @Test
  public void testCreateReportsConcurrentlyCreatedEntityAsAlreadyExists() {
    // Another request creates a generic table with the same name after the existence check.
    PolarisMetaStoreManager concurrentlyCreated = Mockito.spy(metaStoreManager);
    Mockito.doReturn(
            new EntityResult(
                BaseResult.ReturnStatus.ENTITY_ALREADY_EXISTS,
                PolarisEntitySubType.GENERIC_TABLE.getCode()))
        .when(concurrentlyCreated)
        .createEntityIfNotExists(Mockito.any(), Mockito.any(), Mockito.any());
    IcechunkTableCatalog catalog = newIcechunkTableCatalog(CATALOG_NAME, concurrentlyCreated);

    assertThatThrownBy(() -> catalog.createIcechunkTable(TABLE, NS_LOCATION + "repo", Map.of()))
        .isInstanceOf(AlreadyExistsException.class)
        .hasMessage("Generic table already exists: %s", TABLE);
  }

  @ParameterizedTest
  @EnumSource(
      value = BaseResult.ReturnStatus.class,
      names = {"ENTITY_NOT_FOUND", "CATALOG_PATH_CANNOT_BE_RESOLVED"})
  public void testDropIcechunkTableDisappearsAfterResolution(BaseResult.ReturnStatus status) {
    icechunkTableCatalog.createIcechunkTable(TABLE, NS_LOCATION + "repo", Map.of());

    // Resolve the existing table normally, then simulate a concurrent deletion at persistence.
    IcechunkTableCatalog catalog =
        newIcechunkTableCatalog(CATALOG_NAME, failingDrop(new DropEntityResult(status, "x")));

    assertThatThrownBy(() -> catalog.dropIcechunkTable(TABLE))
        .isInstanceOf(NoSuchTableException.class)
        .hasMessage("Icechunk table does not exist: %s", TABLE);
  }

  static Stream<Arguments> testDropIcechunkTableFailureIsNeverSilent() {
    return Stream.of(
        Arguments.of(BaseResult.ReturnStatus.ENTITY_UNDROPPABLE, ForbiddenException.class),
        Arguments.of(
            BaseResult.ReturnStatus.TARGET_ENTITY_CONCURRENTLY_MODIFIED,
            ServiceFailureException.class));
  }

  @ParameterizedTest
  @MethodSource
  public void testDropIcechunkTableFailureIsNeverSilent(
      BaseResult.ReturnStatus status, Class<? extends RuntimeException> expectedException) {
    icechunkTableCatalog.createIcechunkTable(TABLE, NS_LOCATION + "repo", Map.of());

    IcechunkTableCatalog catalog =
        newIcechunkTableCatalog(CATALOG_NAME, failingDrop(new DropEntityResult(status, "x")));

    assertThatThrownBy(() -> catalog.dropIcechunkTable(TABLE))
        .isInstanceOf(expectedException)
        .hasMessageContaining(TABLE.toString());
    assertThat(icechunkTableCatalog.loadIcechunkTable(TABLE)).isNotNull();
  }

  private PolarisMetaStoreManager failingDrop(DropEntityResult result) {
    PolarisMetaStoreManager failing = Mockito.spy(metaStoreManager);
    Mockito.doReturn(result)
        .when(failing)
        .dropEntityIfExists(
            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.eq(false));
    return failing;
  }

  @Test
  public void testLoadAndDropUseTheResolvedTable() {
    icechunkTableCatalog.createIcechunkTable(TABLE, NS_LOCATION + "repo", Map.of());
    // Resolve the table as the handler does for authorization. The passthrough view cannot be used
    // here: it resolves every path again.
    PolarisResolutionManifest manifest =
        resolutionManifestFactory.createResolutionManifest(authenticatedRoot, CATALOG_NAME);
    manifest.addPassthroughPath(new ResolverPath(ResolvedPathKey.ofTableLike(TABLE), true));
    assertThat(manifest.resolveAll().getStatus()).isEqualTo(ResolverStatus.StatusEnum.SUCCESS);
    IcechunkTableCatalog resolvedCatalog = newIcechunkTableCatalog(manifest, metaStoreManager);

    // Another request replaces the table after this one resolved it.
    icechunkTableCatalog.dropIcechunkTable(TABLE);
    icechunkTableCatalog.createIcechunkTable(TABLE, NS_LOCATION + "repo2", Map.of());

    assertThat(resolvedCatalog.loadIcechunkTable(TABLE).getBaseLocation())
        .isEqualTo(NS_LOCATION + "repo/");
    assertThatThrownBy(() -> resolvedCatalog.dropIcechunkTable(TABLE))
        .isInstanceOf(NoSuchTableException.class)
        .hasMessage("Icechunk table does not exist: %s", TABLE);
    assertThat(icechunkTableCatalog.loadIcechunkTable(TABLE).getBaseLocation())
        .isEqualTo(NS_LOCATION + "repo2/");
  }

  @Test
  public void testListIcechunkTablesExcludesOtherTableLikeEntities() {
    List<TableIdentifier> icechunkTables = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      TableIdentifier identifier = TableIdentifier.of(NS, "repo" + i);
      icechunkTableCatalog.createIcechunkTable(
          identifier, NS_LOCATION + identifier.name(), Map.of());
      icechunkTables.add(identifier);
    }
    TableIdentifier icebergTable = TableIdentifier.of(NS, "table");
    TableIdentifier icebergView = TableIdentifier.of(NS, "view");
    TableIdentifier genericTable = TableIdentifier.of(NS, "generic");
    createTableLike(PolarisEntitySubType.ICEBERG_TABLE, icebergTable);
    createTableLike(PolarisEntitySubType.ICEBERG_VIEW, icebergView);
    createTableLike(PolarisEntitySubType.GENERIC_TABLE, genericTable);

    assertThat(icechunkTableCatalog.listIcechunkTables(NS, PageToken.readEverything()).items())
        .containsExactlyInAnyOrderElementsOf(icechunkTables);
    // Icechunk tables stay out of the other lists.
    assertThat(icebergCatalog.listTables(NS)).containsExactly(icebergTable);
    assertThat(icebergCatalog.listViews(NS)).containsExactly(icebergView);
    assertThat(genericTableCatalog.listGenericTables(NS, PageToken.readEverything()).items())
        .containsExactly(genericTable);
  }

  @Test
  public void testPaginatedListIcechunkTables() {
    List<TableIdentifier> icechunkTables = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      TableIdentifier identifier = TableIdentifier.of(NS, "repo" + i);
      icechunkTableCatalog.createIcechunkTable(
          identifier, NS_LOCATION + identifier.name(), Map.of());
      icechunkTables.add(identifier);
    }

    // List without pagination
    assertThat(icechunkTableCatalog.listIcechunkTables(NS, PageToken.readEverything()).items())
        .hasSize(5);

    // List with a limit
    Page<TableIdentifier> result1 =
        icechunkTableCatalog.listIcechunkTables(NS, PageToken.fromLimit(2));
    assertThat(result1.items()).hasSize(2);
    assertThat(result1.encodedResponseToken()).isNotNull().isNotEmpty();

    // List using previous token
    Page<TableIdentifier> result2 =
        icechunkTableCatalog.listIcechunkTables(NS, nextRequest(result1));
    assertThat(result2.items()).hasSize(2);
    assertThat(result2.encodedResponseToken()).isNotNull().isNotEmpty();

    // List using the final token
    Page<TableIdentifier> result3 =
        icechunkTableCatalog.listIcechunkTables(NS, nextRequest(result2));
    assertThat(result3.items()).hasSize(1);
    assertThat(result3.encodedResponseToken()).isNull();

    List<TableIdentifier> allPages = new ArrayList<>(result1.items());
    allPages.addAll(result2.items());
    allPages.addAll(result3.items());
    assertThat(allPages).containsExactlyInAnyOrderElementsOf(icechunkTables);
  }

  private static PageToken nextRequest(Page<?> previousPage) {
    return PageToken.build(previousPage.encodedResponseToken(), null, -1, () -> true);
  }

  @Test
  public void testListIcechunkTablesInNonExistentNamespace() {
    assertThatThrownBy(
            () ->
                icechunkTableCatalog.listIcechunkTables(
                    Namespace.of("missing"), PageToken.readEverything()))
        .isInstanceOf(NoSuchNamespaceException.class)
        .hasMessage("Namespace does not exist: missing");
  }

  @ParameterizedTest
  @CsvSource({
    // Outside the allowed locations
    "s3://other-bucket/ns/repo, is not in the list of allowed locations",
    "s3://my-bucket/path/to/data-other/ns/repo, is not in the list of allowed locations",
    // Inside the allowed locations, but outside the namespace location
    "s3://my-bucket/path/to/data/elsewhere/repo, is not in the list of allowed locations",
    "s3://my-bucket/path/to/data, is not in the list of allowed locations",
    // Equal to the namespace location
    "s3://my-bucket/path/to/data/ns, conflicts with existing table or namespace",
    "s3://my-bucket/path/to/data/ns/, conflicts with existing table or namespace"
  })
  public void testCreateRejectsForbiddenLocation(String location, String message) {
    assertThatThrownBy(() -> icechunkTableCatalog.createIcechunkTable(TABLE, location, Map.of()))
        .isInstanceOf(ForbiddenException.class)
        .hasMessageContaining(message);
    assertIcechunkTableDoesNotExist(TABLE);
  }

  @ParameterizedTest
  @CsvSource({
    // Equal to an existing repository, parent of it, child of it
    "ICECHUNK_TABLE, a/repo",
    "ICECHUNK_TABLE, a/repo/",
    "ICECHUNK_TABLE, a",
    "ICECHUNK_TABLE, a/repo/child",
    // The same against an Iceberg table
    "ICEBERG_TABLE, a/repo",
    "ICEBERG_TABLE, a",
    "ICEBERG_TABLE, a/repo/child"
  })
  public void testCreateRejectsOverlappingLocation(
      PolarisEntitySubType existingSubType, String relativeLocation) {
    TableIdentifier existing = TableIdentifier.of(NS, "existing");
    String existingLocation = NS_LOCATION + "a/repo";
    if (existingSubType == PolarisEntitySubType.ICEBERG_TABLE) {
      icebergCatalog.buildTable(existing, SCHEMA).withLocation(existingLocation).create();
    } else {
      icechunkTableCatalog.createIcechunkTable(existing, existingLocation, Map.of());
    }

    assertThatThrownBy(
            () ->
                icechunkTableCatalog.createIcechunkTable(
                    TABLE, NS_LOCATION + relativeLocation, Map.of()))
        .isInstanceOf(ForbiddenException.class)
        .hasMessageContaining("conflicts with existing table or namespace");
    assertIcechunkTableDoesNotExist(TABLE);
  }

  @Test
  public void testSiblingSharingOnlyStringPrefixIsAccepted() {
    icechunkTableCatalog.createIcechunkTable(TABLE, NS_LOCATION + "repo", Map.of());

    IcechunkTableEntity sibling =
        icechunkTableCatalog.createIcechunkTable(
            TableIdentifier.of(NS, "repo-other"), NS_LOCATION + "repo-other", Map.of());

    assertThat(sibling.getBaseLocation()).isEqualTo(NS_LOCATION + "repo-other/");
  }

  @Test
  public void testTableLocationOverlapAllowedByCatalog() {
    String location = "s3://overlap-bucket/data";
    IcechunkTableCatalog catalog =
        newCatalogWithNamespace(
            "overlap-catalog",
            location,
            Map.of(FeatureConfiguration.ALLOW_TABLE_LOCATION_OVERLAP.catalogConfig(), "true"));
    catalog.createIcechunkTable(TABLE, location + "/ns/repo", Map.of());

    // Equal to the existing repository, a child of it, and the namespace location.
    assertThatCode(
            () ->
                catalog.createIcechunkTable(
                    TableIdentifier.of(NS, "same"), location + "/ns/repo", Map.of()))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                catalog.createIcechunkTable(
                    TableIdentifier.of(NS, "child"), location + "/ns/repo/child", Map.of()))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                catalog.createIcechunkTable(
                    TableIdentifier.of(NS, "namespace"), location + "/ns", Map.of()))
        .doesNotThrowAnyException();
  }

  @Test
  public void testClientSpecifiedTableLocationDisabled() {
    String location = "s3://fixed-location-bucket/data";
    IcechunkTableCatalog catalog =
        newCatalogWithNamespace(
            "fixed-location-catalog",
            location,
            Map.of(
                FeatureConfiguration.ALLOW_CLIENT_SPECIFIED_TABLE_LOCATION.catalogConfig(),
                "false"));

    assertThatThrownBy(() -> catalog.createIcechunkTable(TABLE, location + "/ns/repo", Map.of()))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(
            "Catalog fixed-location-catalog does not allow client-specified table locations "
                + "(ALLOW_CLIENT_SPECIFIED_TABLE_LOCATION), but an Icechunk table requires one");
  }

  @ParameterizedTest
  @CsvSource({
    "true, false, ALLOW_UNSTRUCTURED_TABLE_LOCATION is enabled",
    "false, true, ALLOW_NAMESPACE_CUSTOM_LOCATION is enabled",
    "true, true, ALLOW_UNSTRUCTURED_TABLE_LOCATION and ALLOW_NAMESPACE_CUSTOM_LOCATION are enabled"
  })
  public void testLocationsOutsideNamespaceWithoutOptimizedSiblingCheckAreRejected(
      boolean unstructuredTableLocation, boolean customNamespaceLocation, String enabledFlags) {
    // When a table or namespace location may lie outside of its parent namespace, a
    // namespace-scoped overlap check is not enough.
    String location = "s3://unstructured-bucket/data";
    IcechunkTableCatalog catalog =
        newCatalogWithNamespace(
            "unstructured-catalog",
            location,
            Map.of(
                FeatureConfiguration.ALLOW_UNSTRUCTURED_TABLE_LOCATION.catalogConfig(),
                String.valueOf(unstructuredTableLocation),
                BehaviorChangeConfiguration.ALLOW_NAMESPACE_CUSTOM_LOCATION.catalogConfig(),
                String.valueOf(customNamespaceLocation)));

    assertThatThrownBy(() -> catalog.createIcechunkTable(TABLE, location + "/ns/repo", Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("However, " + enabledFlags)
        .hasMessageContaining("This is not a safe combination of configurations");
  }

  @Test
  public void testNamespaceWithIcechunkTableCannotBeDropped() {
    icechunkTableCatalog.createIcechunkTable(TABLE, NS_LOCATION + "repo", Map.of());

    assertThatThrownBy(() -> icebergCatalog.dropNamespace(NS))
        .isInstanceOf(NamespaceNotEmptyException.class)
        .hasMessage("Namespace %s is not empty", NS);

    icechunkTableCatalog.dropIcechunkTable(TABLE);
    assertThat(icebergCatalog.dropNamespace(NS)).isTrue();
  }

  @Test
  public void testExternalCatalogIsRejected() {
    String catalogName = "external-catalog";
    String location = "s3://external-bucket/data";
    createCatalog(catalogName, "EXTERNAL", location, Map.of());
    newIcebergCatalog(catalogName).createNamespace(NS);
    // The rejection follows authorization, and vending needs data access on the catalog.
    assertThat(
            adminService
                .grantPrivilegeOnCatalogToRole(
                    catalogName,
                    PolarisEntityConstants.getNameOfCatalogAdminRole(),
                    PolarisPrivilege.CATALOG_MANAGE_CONTENT)
                .isSuccess())
        .isTrue();

    assertThatThrownBy(
            () ->
                newHandler(catalogName)
                    .createIcechunkTable(
                        TABLE, location + "/ns/repo", Map.of(), false, Optional.empty()))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(EXTERNAL_CATALOG_MESSAGE);
    assertThatThrownBy(() -> newHandler(catalogName).listIcechunkTables(NS, null, null))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(EXTERNAL_CATALOG_MESSAGE);

    // An Icechunk entity in an external catalog is rejected as well.
    PolarisResolvedPathWrapper namespacePath =
        passthroughView(catalogName).getResolvedPath(ResolvedPathKey.ofNamespace(NS));
    IcechunkTableEntity entity =
        new IcechunkTableEntity.Builder(TABLE)
            .setId(metaStoreManager.generateNewEntityId(polarisContext).getId())
            .setCatalogId(namespacePath.getRawFullPath().getFirst().getId())
            .setParentId(namespacePath.getRawLeafEntity().getId())
            .setBaseLocation(location + "/ns/repo/")
            .setCreateTimestamp(System.currentTimeMillis())
            .build();
    assertThat(
            metaStoreManager
                .createEntityIfNotExists(
                    polarisContext,
                    PolarisEntity.toCoreList(namespacePath.getRawFullPath()),
                    entity)
                .isSuccess())
        .isTrue();
    assertThatThrownBy(
            () ->
                newHandler(catalogName)
                    .loadIcechunkTable(
                        TABLE, false, IcechunkTableAccessMode.READ, Optional.empty()))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(EXTERNAL_CATALOG_MESSAGE);
    assertThatThrownBy(
            () ->
                newHandler(catalogName)
                    .loadIcechunkTableCredentials(
                        TABLE, IcechunkTableAccessMode.READ, Optional.empty()))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(EXTERNAL_CATALOG_MESSAGE);
    assertThatThrownBy(() -> newHandler(catalogName).dropIcechunkTable(TABLE))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(EXTERNAL_CATALOG_MESSAGE);
  }
}
