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

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.polaris.core.StructuredLogKeys;
import org.apache.polaris.core.auth.PolarisAuthorizableOperation;
import org.apache.polaris.core.config.FeatureConfiguration;
import org.apache.polaris.core.entity.CatalogEntity;
import org.apache.polaris.core.entity.PolarisEntityConstants;
import org.apache.polaris.core.entity.PolarisEntitySubType;
import org.apache.polaris.core.entity.table.IcechunkTableEntity;
import org.apache.polaris.core.persistence.PolarisResolvedPathWrapper;
import org.apache.polaris.core.persistence.ResolvedPolarisEntity;
import org.apache.polaris.core.persistence.pagination.Page;
import org.apache.polaris.core.persistence.pagination.PageToken;
import org.apache.polaris.core.persistence.resolver.ResolvedPathKey;
import org.apache.polaris.core.storage.PolarisStorageActions;
import org.apache.polaris.immutables.PolarisImmutable;
import org.apache.polaris.service.catalog.common.CatalogHandler;
import org.apache.polaris.service.catalog.common.CatalogUtils;
import org.apache.polaris.service.catalog.common.LocationOverlapValidator;
import org.apache.polaris.service.catalog.io.StorageAccessConfigProvider;
import org.apache.polaris.service.types.IcechunkTable;
import org.apache.polaris.service.types.ListIcechunkTablesResponse;
import org.apache.polaris.service.types.LoadIcechunkTableCredentialsResponse;
import org.apache.polaris.service.types.LoadIcechunkTableResponse;
import org.apache.polaris.service.types.StorageAccessConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PolarisImmutable
@SuppressWarnings("immutables:incompat")
public abstract class IcechunkTableCatalogHandler extends CatalogHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(IcechunkTableCatalogHandler.class);

  protected abstract StorageAccessConfigProvider storageAccessConfigProvider();

  private IcechunkTableCatalog icechunkTableCatalog;

  @Override
  protected void initializeCatalog() {
    CatalogEntity resolvedCatalogEntity = resolutionManifest.getResolvedCatalogEntity();
    // Passthrough facades skip the subtype filtering of resolved paths, and Polaris does not
    // manage the contents of external catalogs.
    if (resolvedCatalogEntity.isExternal()
        || resolvedCatalogEntity.getConnectionConfigInfoDpo() != null) {
      throw new BadRequestException("Icechunk tables are not supported in external catalogs");
    }
    this.icechunkTableCatalog =
        new IcechunkTableCatalog(
            metaStoreManager(),
            callContext(),
            this.resolutionManifest,
            new LocationOverlapValidator(
                realmConfig(),
                metaStoreManager(),
                callContext().getPolarisCallContext(),
                referenceCatalogName ->
                    resolutionManifestFactory()
                        .createResolutionManifest(polarisPrincipal(), referenceCatalogName)));
  }

  public ListIcechunkTablesResponse listIcechunkTables(
      Namespace parent, String pageToken, Integer pageSize) {
    PolarisAuthorizableOperation op = PolarisAuthorizableOperation.LIST_TABLES;
    authorizeBasicNamespaceOperationOrThrow(op, parent);

    PageToken pageRequest =
        PageToken.build(pageToken, pageSize, maxPageSize(), this::shouldDecodeToken);
    Page<TableIdentifier> page = icechunkTableCatalog.listIcechunkTables(parent, pageRequest);
    rejectIncompleteListing(pageToken, pageSize, page.encodedResponseToken());
    return ListIcechunkTablesResponse.builder()
        .setIdentifiers(new LinkedHashSet<>(page.items()))
        .setNextPageToken(page.encodedResponseToken())
        .build();
  }

  /**
   * Creates an Icechunk table. With {@code vendCredentials}, the response carries read-write
   * credentials for the repository root, so that the caller can initialize the repository.
   */
  public LoadIcechunkTableResponse createIcechunkTable(
      TableIdentifier identifier,
      String location,
      Map<String, String> properties,
      boolean vendCredentials,
      Optional<String> refreshCredentialsEndpoint) {
    PolarisAuthorizableOperation op =
        vendCredentials
            ? PolarisAuthorizableOperation.CREATE_TABLE_DIRECT_WITH_WRITE_DELEGATION
            : PolarisAuthorizableOperation.CREATE_TABLE_DIRECT;
    authorizeCreateTableLikeUnderNamespaceOperationOrThrow(op, identifier);

    IcechunkTableEntity createdEntity =
        icechunkTableCatalog.createIcechunkTable(identifier, location, properties);
    LoadIcechunkTableResponse.Builder response =
        LoadIcechunkTableResponse.builder().setTable(toIcechunkTable(createdEntity));
    if (vendCredentials) {
      // The new table is not part of the resolved paths. Append it to the namespace path, so that
      // the credential vending context names the table.
      PolarisResolvedPathWrapper namespacePath =
          resolutionManifest.getResolvedPath(ResolvedPathKey.ofNamespace(identifier.namespace()));
      List<ResolvedPolarisEntity> tablePath = new ArrayList<>(namespacePath.getResolvedFullPath());
      tablePath.add(new ResolvedPolarisEntity(createdEntity, List.of(), List.of()));
      response.setStorageAccessConfigs(
          vendStorageAccessConfigs(
              identifier,
              createdEntity,
              storageActions(IcechunkTableAccessMode.READ_WRITE),
              refreshCredentialsEndpoint,
              new PolarisResolvedPathWrapper(tablePath),
              namespacePath));
    }
    return response.build();
  }

  /**
   * Loads an Icechunk table. With {@code vendCredentials}, the response carries credentials for the
   * repository root with the access of {@code accessMode}; write access is never granted unless
   * {@code accessMode} asks for it.
   */
  public LoadIcechunkTableResponse loadIcechunkTable(
      TableIdentifier identifier,
      boolean vendCredentials,
      IcechunkTableAccessMode accessMode,
      Optional<String> refreshCredentialsEndpoint) {
    PolarisAuthorizableOperation op =
        vendCredentials ? delegationOperation(accessMode) : PolarisAuthorizableOperation.LOAD_TABLE;
    resolveAndAuthorizeBasicTableLikeOperationOrThrow(
        op, PolarisEntitySubType.ICECHUNK_TABLE, identifier);

    IcechunkTableEntity loadedEntity = icechunkTableCatalog.loadIcechunkTable(identifier);
    LoadIcechunkTableResponse.Builder response =
        LoadIcechunkTableResponse.builder().setTable(toIcechunkTable(loadedEntity));
    if (vendCredentials) {
      response.setStorageAccessConfigs(
          vendStorageAccessConfigs(
              identifier, loadedEntity, accessMode, refreshCredentialsEndpoint));
    }
    return response.build();
  }

  public LoadIcechunkTableCredentialsResponse loadIcechunkTableCredentials(
      TableIdentifier identifier,
      IcechunkTableAccessMode accessMode,
      Optional<String> refreshCredentialsEndpoint) {
    resolveAndAuthorizeBasicTableLikeOperationOrThrow(
        delegationOperation(accessMode), PolarisEntitySubType.ICECHUNK_TABLE, identifier);

    IcechunkTableEntity loadedEntity = icechunkTableCatalog.loadIcechunkTable(identifier);
    return LoadIcechunkTableCredentialsResponse.builder()
        .setStorageAccessConfigs(
            vendStorageAccessConfigs(
                identifier, loadedEntity, accessMode, refreshCredentialsEndpoint))
        .build();
  }

  public void dropIcechunkTable(TableIdentifier identifier) {
    PolarisAuthorizableOperation op = PolarisAuthorizableOperation.DROP_TABLE_WITHOUT_PURGE;
    resolveAndAuthorizeBasicTableLikeOperationOrThrow(
        op, PolarisEntitySubType.ICECHUNK_TABLE, identifier);

    icechunkTableCatalog.dropIcechunkTable(identifier);
  }

  /** Vends credentials for an existing table, whose path was resolved during authorization. */
  private List<StorageAccessConfig> vendStorageAccessConfigs(
      TableIdentifier identifier,
      IcechunkTableEntity entity,
      IcechunkTableAccessMode accessMode,
      Optional<String> refreshCredentialsEndpoint) {
    PolarisResolvedPathWrapper tablePath =
        CatalogUtils.findResolvedStorageEntity(
            resolutionManifest, identifier, PolarisEntitySubType.ICECHUNK_TABLE);
    return vendStorageAccessConfigs(
        identifier,
        entity,
        storageActions(accessMode),
        refreshCredentialsEndpoint,
        tablePath,
        new PolarisResolvedPathWrapper(tablePath.getResolvedParentPath()));
  }

  /**
   * Vends credentials for the repository root of the table (on Azure Blob endpoints and on ADLS
   * without a hierarchical namespace, the SAS token covers the whole container). Before every vend,
   * the root is validated again against the namespace path, so that a root which no longer
   * satisfies the catalog's location rules gets no credentials. The table path cannot be used for
   * this: its leaf's own location would act as the structural parent, so the check would always
   * pass.
   */
  private List<StorageAccessConfig> vendStorageAccessConfigs(
      TableIdentifier identifier,
      IcechunkTableEntity entity,
      Set<PolarisStorageActions> actions,
      Optional<String> refreshCredentialsEndpoint,
      PolarisResolvedPathWrapper tablePath,
      PolarisResolvedPathWrapper namespacePath) {
    String root = entity.getBaseLocation();
    if (root == null || root.isBlank()) {
      throw new IllegalStateException(
          String.format("Icechunk table %s has no location", identifier));
    }

    try {
      CatalogUtils.validateLocationsForTableLike(
          realmConfig(), identifier, Set.of(root), namespacePath);
    } catch (ForbiddenException e) {
      LOGGER
          .atError()
          .addKeyValue(StructuredLogKeys.TABLE_IDENTIFIER, identifier)
          .addKeyValue(StructuredLogKeys.TABLE_LOCATION, root)
          .log("Icechunk table location validation failed for credential vending");
      throw new ForbiddenException(
          "Icechunk table '%s' has a location that the catalog no longer allows: %s",
          identifier, e.getMessage());
    }

    var storageAccessConfig =
        storageAccessConfigProvider()
            .getStorageAccessConfig(
                identifier, Set.of(root), actions, refreshCredentialsEndpoint, tablePath);
    // Internal properties, such as an internal endpoint, are for the server only and are never
    // returned.
    Map<String, String> extraProperties = storageAccessConfig.extraProperties();
    if (storageAccessConfig.credentials().isEmpty()) {
      Preconditions.checkArgument(
          !storageAccessConfig.supportsCredentialVending()
              || realmConfig()
                  .getConfig(FeatureConfiguration.SKIP_CREDENTIAL_SUBSCOPING_INDIRECTION),
          "Credential vending was requested for table %s, but no credentials are available",
          identifier);
      return List.of();
    }
    Map<String, String> config = new HashMap<>(storageAccessConfig.credentials());
    config.putAll(extraProperties);
    return List.of(new StorageAccessConfig(root, config));
  }

  private static IcechunkTable toIcechunkTable(IcechunkTableEntity entity) {
    Map<String, String> properties = new HashMap<>(entity.getPropertiesAsMap());
    properties.remove(PolarisEntityConstants.ENTITY_BASE_LOCATION);
    return IcechunkTable.builder()
        .setName(entity.getName())
        .setLocation(entity.getBaseLocation())
        .setProperties(properties)
        .build();
  }

  private static PolarisAuthorizableOperation delegationOperation(
      IcechunkTableAccessMode accessMode) {
    return switch (accessMode) {
      case READ -> PolarisAuthorizableOperation.LOAD_TABLE_WITH_READ_DELEGATION;
      case READ_WRITE -> PolarisAuthorizableOperation.LOAD_TABLE_WITH_WRITE_DELEGATION;
    };
  }

  private static Set<PolarisStorageActions> storageActions(IcechunkTableAccessMode accessMode) {
    return switch (accessMode) {
      case READ -> Set.of(PolarisStorageActions.READ, PolarisStorageActions.LIST);
      case READ_WRITE ->
          Set.of(
              PolarisStorageActions.READ, PolarisStorageActions.WRITE, PolarisStorageActions.LIST);
    };
  }
}
