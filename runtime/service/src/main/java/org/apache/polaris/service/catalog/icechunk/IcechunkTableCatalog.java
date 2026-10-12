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

import static java.util.Objects.requireNonNull;
import static org.apache.polaris.service.catalog.common.ExceptionUtils.alreadyExistsExceptionForTableLikeEntity;
import static org.apache.polaris.service.catalog.common.ExceptionUtils.noSuchNamespaceException;
import static org.apache.polaris.service.catalog.common.ExceptionUtils.notFoundExceptionForTableLikeEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.exceptions.ServiceFailureException;
import org.apache.polaris.core.catalog.PolarisCatalogHelpers;
import org.apache.polaris.core.config.BehaviorChangeConfiguration;
import org.apache.polaris.core.config.FeatureConfiguration;
import org.apache.polaris.core.config.RealmConfig;
import org.apache.polaris.core.context.CallContext;
import org.apache.polaris.core.entity.CatalogEntity;
import org.apache.polaris.core.entity.PolarisEntity;
import org.apache.polaris.core.entity.PolarisEntitySubType;
import org.apache.polaris.core.entity.PolarisEntityType;
import org.apache.polaris.core.entity.table.IcechunkTableEntity;
import org.apache.polaris.core.persistence.PolarisMetaStoreManager;
import org.apache.polaris.core.persistence.PolarisResolvedPathWrapper;
import org.apache.polaris.core.persistence.dao.entity.BaseResult;
import org.apache.polaris.core.persistence.dao.entity.DropEntityResult;
import org.apache.polaris.core.persistence.dao.entity.EntityResult;
import org.apache.polaris.core.persistence.pagination.Page;
import org.apache.polaris.core.persistence.pagination.PageToken;
import org.apache.polaris.core.persistence.resolver.PolarisResolutionManifestCatalogView;
import org.apache.polaris.core.persistence.resolver.ResolvedPathKey;
import org.apache.polaris.core.storage.StorageLocation;
import org.apache.polaris.service.catalog.common.CatalogUtils;
import org.apache.polaris.service.catalog.common.LocationOverlapValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates, loads, lists and drops Icechunk table entities. Polaris stores the identity and the
 * repository root of an Icechunk table; it never reads or writes the repository itself.
 */
public class IcechunkTableCatalog {
  private static final Logger LOGGER = LoggerFactory.getLogger(IcechunkTableCatalog.class);

  private final PolarisMetaStoreManager metaStoreManager;
  private final CallContext callContext;
  private final RealmConfig realmConfig;
  private final PolarisResolutionManifestCatalogView resolvedEntityView;
  private final CatalogEntity catalogEntity;
  private final LocationOverlapValidator locationOverlapValidator;

  public IcechunkTableCatalog(
      PolarisMetaStoreManager metaStoreManager,
      CallContext callContext,
      PolarisResolutionManifestCatalogView resolvedEntityView,
      LocationOverlapValidator locationOverlapValidator) {
    this.metaStoreManager = metaStoreManager;
    this.callContext = callContext;
    this.realmConfig = callContext.getRealmConfig();
    this.resolvedEntityView = requireNonNull(resolvedEntityView, "No resolved entity view");
    this.catalogEntity =
        requireNonNull(resolvedEntityView.getResolvedCatalogEntity(), "No resolved catalog entity");
    this.locationOverlapValidator = locationOverlapValidator;
  }

  public IcechunkTableEntity createIcechunkTable(
      TableIdentifier tableIdentifier, String location, Map<String, String> properties) {
    PolarisResolvedPathWrapper resolvedParent =
        resolvedEntityView.getResolvedPath(
            ResolvedPathKey.ofNamespace(tableIdentifier.namespace()));
    if (resolvedParent == null) {
      // Illegal state because the namespace should've already been in the static resolution set.
      throw new IllegalStateException(
          String.format(
              "Failed to fetch resolved parent for TableIdentifier '%s'", tableIdentifier));
    }

    PolarisResolvedPathWrapper existing =
        resolvedEntityView.getPassthroughResolvedPath(
            ResolvedPathKey.ofTableLike(tableIdentifier), PolarisEntitySubType.ANY_SUBTYPE);
    if (existing != null && existing.getRawLeafEntity() != null) {
      throw alreadyExistsExceptionForTableLikeEntity(
          tableIdentifier, existing.getRawLeafEntity().getSubType());
    }

    if (!realmConfig.getConfig(
        FeatureConfiguration.ALLOW_CLIENT_SPECIFIED_TABLE_LOCATION, catalogEntity)) {
      throw new BadRequestException(
          "Catalog %s does not allow client-specified table locations (%s), "
              + "but an Icechunk table requires one",
          catalogEntity.getName(),
          FeatureConfiguration.ALLOW_CLIENT_SPECIFIED_TABLE_LOCATION.key());
    }

    String normalizedLocation = StorageLocation.ensureTrailingSlash(location);

    // Validate against the namespace path: the allowed locations and, unless unstructured table
    // locations are allowed, the namespace location as the structural parent.
    CatalogUtils.validateLocationsForTableLike(
        realmConfig, tableIdentifier, Set.of(normalizedLocation), resolvedParent);

    List<PolarisEntity> parentPath = resolvedParent.getRawFullPath();
    long parentId = resolvedParent.getRawLeafEntity().getId();
    if (realmConfig.getConfig(FeatureConfiguration.ALLOW_TABLE_LOCATION_OVERLAP, catalogEntity)) {
      LOGGER.debug("Skipping location overlap validation for identifier '{}'", tableIdentifier);
    } else {
      // Unstructured table locations and custom namespace locations both let a location lie outside
      // of its parent namespace, where only the optimized sibling check finds overlaps.
      List<String> enabledFlags = new ArrayList<>();
      if (realmConfig.getConfig(
          FeatureConfiguration.ALLOW_UNSTRUCTURED_TABLE_LOCATION, catalogEntity)) {
        enabledFlags.add(FeatureConfiguration.ALLOW_UNSTRUCTURED_TABLE_LOCATION.key());
      }
      if (realmConfig.getConfig(
          BehaviorChangeConfiguration.ALLOW_NAMESPACE_CUSTOM_LOCATION, catalogEntity)) {
        enabledFlags.add(BehaviorChangeConfiguration.ALLOW_NAMESPACE_CUSTOM_LOCATION.key());
      }
      if (!enabledFlags.isEmpty()
          && !realmConfig.getConfig(FeatureConfiguration.OPTIMIZED_SIBLING_CHECK)) {
        throw new IllegalStateException(
            String.format(
                "%s and %s are both disabled, which means that table location overlap checks are being"
                    + " performed, but only within each namespace. However, %s %s enabled, which indicates"
                    + " that table or namespace locations may lie outside of their parent namespace. This"
                    + " is not a safe combination of configurations.",
                FeatureConfiguration.ALLOW_TABLE_LOCATION_OVERLAP.key(),
                FeatureConfiguration.OPTIMIZED_SIBLING_CHECK.key(),
                String.join(" and ", enabledFlags),
                enabledFlags.size() == 1 ? "is" : "are"));
      }
      // The overlap queries are scoped by catalog and parent, so both ids must be set.
      IcechunkTableEntity virtualEntity =
          new IcechunkTableEntity.Builder(tableIdentifier)
              .setCatalogId(catalogEntity.getId())
              .setParentId(parentId)
              .setBaseLocation(normalizedLocation)
              .build();
      locationOverlapValidator.validateNoLocationOverlap(virtualEntity, parentPath);
    }

    // Set the properties first, so that the validated location always wins.
    IcechunkTableEntity entity =
        new IcechunkTableEntity.Builder(tableIdentifier)
            .setCatalogId(catalogEntity.getId())
            .setParentId(parentId)
            .setId(
                metaStoreManager.generateNewEntityId(callContext.getPolarisCallContext()).getId())
            .setProperties(properties)
            .setBaseLocation(normalizedLocation)
            .setCreateTimestamp(System.currentTimeMillis())
            .build();

    EntityResult res =
        metaStoreManager.createEntityIfNotExists(
            callContext.getPolarisCallContext(), PolarisEntity.toCoreList(parentPath), entity);
    if (!res.isSuccess()) {
      if (requireNonNull(res.getReturnStatus()) == BaseResult.ReturnStatus.ENTITY_ALREADY_EXISTS) {
        throw alreadyExistsExceptionForTableLikeEntity(
            tableIdentifier, res.getAlreadyExistsEntitySubType());
      }
      throw new IllegalStateException(
          String.format(
              "Unknown error status for identifier %s: %s with extraInfo: %s",
              tableIdentifier, res.getReturnStatus(), res.getExtraInformation()));
    }
    IcechunkTableEntity resultEntity = IcechunkTableEntity.of(res.getEntity());
    LOGGER.debug(
        "Created Icechunk table entity {} with TableIdentifier {}", resultEntity, tableIdentifier);
    return resultEntity;
  }

  public IcechunkTableEntity loadIcechunkTable(TableIdentifier tableIdentifier) {
    // The path resolved for authorization, so a table replaced since then is not acted on.
    PolarisResolvedPathWrapper resolvedEntities =
        resolvedEntityView.getResolvedPath(
            ResolvedPathKey.ofTableLike(tableIdentifier), PolarisEntitySubType.ICECHUNK_TABLE);
    IcechunkTableEntity entity =
        IcechunkTableEntity.of(
            resolvedEntities == null ? null : resolvedEntities.getRawLeafEntity());
    if (entity == null) {
      throw notFoundExceptionForTableLikeEntity(
          tableIdentifier, PolarisEntitySubType.ICECHUNK_TABLE);
    }
    return entity;
  }

  /** Removes the table from the catalog only. No cleanup task is scheduled for the repository. */
  public void dropIcechunkTable(TableIdentifier tableIdentifier) {
    // The path resolved for authorization, so a table replaced since then is not acted on.
    PolarisResolvedPathWrapper resolvedEntities =
        resolvedEntityView.getResolvedPath(
            ResolvedPathKey.ofTableLike(tableIdentifier), PolarisEntitySubType.ICECHUNK_TABLE);
    if (resolvedEntities == null) {
      throw notFoundExceptionForTableLikeEntity(
          tableIdentifier, PolarisEntitySubType.ICECHUNK_TABLE);
    }

    DropEntityResult dropEntityResult =
        metaStoreManager.dropEntityIfExists(
            callContext.getPolarisCallContext(),
            PolarisEntity.toCoreList(resolvedEntities.getRawParentPath()),
            resolvedEntities.getRawLeafEntity(),
            Map.of(),
            false);
    if (dropEntityResult.isSuccess()) {
      return;
    }
    switch (dropEntityResult.getReturnStatus()) {
      case ENTITY_NOT_FOUND, CATALOG_PATH_CANNOT_BE_RESOLVED ->
          throw notFoundExceptionForTableLikeEntity(
              tableIdentifier, PolarisEntitySubType.ICECHUNK_TABLE);
      case ENTITY_UNDROPPABLE ->
          throw new ForbiddenException(
              "Icechunk table %s cannot be dropped: %s",
              tableIdentifier, dropEntityResult.getExtraInformation());
      default ->
          throw new ServiceFailureException(
              "Failed to drop Icechunk table %s, status=%s, extraInfo=%s",
              tableIdentifier,
              dropEntityResult.getReturnStatus(),
              dropEntityResult.getExtraInformation());
    }
  }

  public Page<TableIdentifier> listIcechunkTables(Namespace namespace, PageToken pageToken) {
    PolarisResolvedPathWrapper resolvedEntities =
        resolvedEntityView.getResolvedPath(ResolvedPathKey.ofNamespace(namespace));
    if (resolvedEntities == null) {
      throw noSuchNamespaceException(namespace);
    }

    List<PolarisEntity> catalogPath = resolvedEntities.getRawFullPath();
    Namespace parentNamespace = PolarisCatalogHelpers.parentNamespace(catalogPath);
    return metaStoreManager
        .listEntities(
            callContext.getPolarisCallContext(),
            PolarisEntity.toCoreList(catalogPath),
            PolarisEntityType.TABLE_LIKE,
            PolarisEntitySubType.ICECHUNK_TABLE,
            pageToken)
        .getPage()
        .map(record -> TableIdentifier.of(parentNamespace, record.getName()));
  }
}
