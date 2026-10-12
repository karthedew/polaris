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
package org.apache.polaris.service.catalog.common;

import com.google.common.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.polaris.core.PolarisCallContext;
import org.apache.polaris.core.config.FeatureConfiguration;
import org.apache.polaris.core.config.RealmConfig;
import org.apache.polaris.core.entity.LocationBasedEntity;
import org.apache.polaris.core.entity.NamespaceEntity;
import org.apache.polaris.core.entity.PolarisEntity;
import org.apache.polaris.core.entity.PolarisEntitySubType;
import org.apache.polaris.core.entity.PolarisEntityType;
import org.apache.polaris.core.entity.PolarisEntityUtils;
import org.apache.polaris.core.exceptions.CommitConflictException;
import org.apache.polaris.core.persistence.PolarisMetaStoreManager;
import org.apache.polaris.core.persistence.PolarisResolvedPathWrapper;
import org.apache.polaris.core.persistence.dao.entity.ListEntitiesResult;
import org.apache.polaris.core.persistence.pagination.PageToken;
import org.apache.polaris.core.persistence.resolver.PolarisResolutionManifest;
import org.apache.polaris.core.persistence.resolver.ResolvedPathKey;
import org.apache.polaris.core.persistence.resolver.ResolverPath;
import org.apache.polaris.core.persistence.resolver.ResolverStatus;
import org.apache.polaris.core.storage.StorageLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates that the base location of an entity that is about to be created or relocated does not
 * overlap the location of a sibling table-like entity or namespace, and is not the location of one
 * of its parent namespaces.
 */
public class LocationOverlapValidator {
  private static final Logger LOGGER = LoggerFactory.getLogger(LocationOverlapValidator.class);

  private final RealmConfig realmConfig;
  private final PolarisMetaStoreManager metaStoreManager;
  private final PolarisCallContext polarisCallContext;
  private final Function<String, PolarisResolutionManifest> resolutionManifestForCatalog;

  /**
   * @param realmConfig the realm configuration
   * @param metaStoreManager the metastore manager used to find sibling entities
   * @param polarisCallContext the call context passed to the metastore manager
   * @param resolutionManifestForCatalog creates a resolution manifest for the catalog with the
   *     given name, used to resolve sibling entities
   */
  public LocationOverlapValidator(
      RealmConfig realmConfig,
      PolarisMetaStoreManager metaStoreManager,
      PolarisCallContext polarisCallContext,
      Function<String, PolarisResolutionManifest> resolutionManifestForCatalog) {
    this.realmConfig = realmConfig;
    this.metaStoreManager = metaStoreManager;
    this.polarisCallContext = polarisCallContext;
    this.resolutionManifestForCatalog = resolutionManifestForCatalog;
  }

  /**
   * Validate no location overlap exists between the entity path and its sibling entities. This
   * resolves all siblings at the same level as the target entity (namespaces if the target entity
   * is a namespace whose parent is the catalog, namespaces and tables otherwise) and checks the
   * base-location property of each. The target entity's base location may not be a prefix or a
   * suffix of any sibling entity's base location.
   */
  public <T extends PolarisEntity & LocationBasedEntity> void validateNoLocationOverlap(
      T entity, List<PolarisEntity> parentPath) {

    String location = entity.getBaseLocation();
    String name = entity.getName();

    // Attempt to directly query for siblings
    boolean useOptimizedSiblingCheck =
        realmConfig.getConfig(FeatureConfiguration.OPTIMIZED_SIBLING_CHECK);
    if (useOptimizedSiblingCheck) {
      Optional<Optional<String>> directSiblingCheckResult =
          metaStoreManager.hasOverlappingSiblings(
              polarisCallContext, PolarisEntity.toCoreList(parentPath), entity);
      if (directSiblingCheckResult.isPresent()) {
        if (directSiblingCheckResult.get().isPresent()) {
          throw new ForbiddenException(
              "Unable to create entity at location '%s' because it conflicts with existing table or namespace at %s",
              location, directSiblingCheckResult.get().get());
        } else {
          return;
        }
      }
    }

    // if the entity path has more than just the catalog, check for tables as well as other
    // namespaces
    Optional<NamespaceEntity> parentNamespace =
        parentPath.size() > 1
            ? Optional.of(NamespaceEntity.of(parentPath.getLast()))
            : Optional.empty();

    // Fall through by listing everything:
    ListEntitiesResult siblingNamespacesResult =
        metaStoreManager.listEntities(
            polarisCallContext,
            PolarisEntity.toCoreList(parentPath),
            PolarisEntityType.NAMESPACE,
            PolarisEntitySubType.ANY_SUBTYPE,
            PageToken.readEverything());
    if (!siblingNamespacesResult.isSuccess()) {
      throw new IllegalStateException(
          "Unable to resolve siblings entities to validate location - could not list namespaces");
    }

    List<TableIdentifier> siblingTables =
        parentNamespace
            .map(
                ns -> {
                  ListEntitiesResult siblingTablesResult =
                      metaStoreManager.listEntities(
                          polarisCallContext,
                          PolarisEntity.toCoreList(parentPath),
                          PolarisEntityType.TABLE_LIKE,
                          PolarisEntitySubType.ANY_SUBTYPE,
                          PageToken.readEverything());
                  if (!siblingTablesResult.isSuccess()) {
                    throw new IllegalStateException(
                        "Unable to resolve siblings entities to validate location - could not list tables");
                  }
                  return siblingTablesResult.getEntities().stream()
                      .map(tbl -> TableIdentifier.of(ns.asNamespace(), tbl.getName()))
                      .collect(Collectors.toList());
                })
            .orElse(List.of());

    List<Namespace> siblingNamespaces =
        siblingNamespacesResult.getEntities().stream()
            .map(
                ns -> {
                  String[] nsLevels =
                      parentNamespace
                          .map(parent -> parent.asNamespace().levels())
                          .orElse(new String[0]);
                  String[] newLevels = Arrays.copyOf(nsLevels, nsLevels.length + 1);
                  newLevels[nsLevels.length] = ns.getName();
                  return Namespace.of(newLevels);
                })
            .toList();
    List<ResolvedPathKey> pathsToResolve =
        new ArrayList<>(siblingTables.size() + siblingNamespaces.size());
    siblingTables.forEach(
        tbl -> {
          if (!tbl.name().equals(name)) {
            pathsToResolve.add(ResolvedPathKey.ofTableLike(tbl));
          }
        });
    siblingNamespaces.forEach(
        ns -> {
          if (!ns.level(ns.length() - 1).equals(name)) {
            pathsToResolve.add(ResolvedPathKey.ofNamespace(ns));
          }
        });

    StorageLocation targetLocation = StorageLocation.of(location);

    // A parent namespace naturally contains the entity's location when locations follow the
    // namespace tree, as default locations do. That nesting is allowed, but the entity may not sit
    // at exactly a containing namespace's location: such a location is a prefix of every sibling
    // created later under that namespace (blocking all of them) and over-scopes any credentials
    // vended for the entity to the whole namespace subtree. The optimized sibling check already
    // rejects this; mirror the exact-location case here so the default list-based path has the same
    // coverage. Only the equality case is rejected (and only against namespaces, not the catalog):
    // an entity may legitimately sit at the catalog's own base location, and strict containment in
    // either direction is handled by normal nesting and the sibling checks below.
    for (PolarisEntity ancestor : parentPath) {
      if (ancestor.getType() != PolarisEntityType.NAMESPACE) {
        continue;
      }
      PolarisEntityUtils.asLocationBasedEntity(ancestor)
          .map(LocationBasedEntity::getBaseLocation)
          .filter(loc -> loc != null && !loc.isBlank())
          .map(StorageLocation::of)
          .ifPresent(
              ancestorLocation -> {
                if (targetLocation.isSameLocation(ancestorLocation)) {
                  throw new ForbiddenException(
                      "Unable to create entity at location '%s' because it conflicts with existing table or namespace at "
                          + "location '%s'",
                      targetLocation, ancestorLocation);
                }
              });
    }

    for (PolarisEntity entityToCheck :
        resolveOptionalPaths(pathsToResolve, parentPath.getFirst().getName())) {
      PolarisEntityUtils.asLocationBasedEntity(entityToCheck)
          .map(LocationBasedEntity::getBaseLocation)
          .map(StorageLocation::of)
          .ifPresent(
              siblingLocation -> {
                if (targetLocation.isChildOf(siblingLocation)
                    || siblingLocation.isChildOf(targetLocation)) {
                  throw new ForbiddenException(
                      "Unable to create entity at location '%s' because it conflicts with existing table or namespace at "
                          + "location '%s'",
                      targetLocation, siblingLocation);
                }
              });
    }
  }

  /** Resolves the given optional paths and returns the leaf entity of each path that exists. */
  @VisibleForTesting
  public List<PolarisEntity> resolveOptionalPaths(List<ResolvedPathKey> keys, String catalogName) {
    LOGGER.debug("Resolving {} sibling entities to validate location", keys.size());

    PolarisResolutionManifest resolutionManifest = resolutionManifestForCatalog.apply(catalogName);

    keys.forEach(
        k -> {
          resolutionManifest.addPath(new ResolverPath(k, true)); // optional path
        });

    ResolverStatus status = resolutionManifest.resolveAll();

    if (status.getStatus() != ResolverStatus.StatusEnum.SUCCESS) {
      String message =
          "Unable to resolve sibling entities to validate location - " + status.getStatus();
      if (status.getStatus().equals(ResolverStatus.StatusEnum.ENTITY_COULD_NOT_BE_RESOLVED)) {
        message += ". Could not resolve entity: " + status.getFailedToResolvedEntityName();
      } else if (status
          .getStatus()
          .equals(ResolverStatus.StatusEnum.PATH_COULD_NOT_BE_FULLY_RESOLVED)) {
        ResolverPath path = status.getFailedToResolvePath();
        if (path != null) {
          message += ". path: " + String.join(".", path.entityNames());
          message += ", failed index: " + status.getFailedToResolvedEntityIndex();
        }
      }

      throw new CommitConflictException(message);
    }

    List<PolarisEntity> result = new ArrayList<>(keys.size());
    keys.forEach(
        k -> {
          PolarisResolvedPathWrapper path = resolutionManifest.getResolvedPath(k);
          if (path != null) {
            PolarisEntity entity = path.getRawLeafEntity();
            if (entity != null) {
              result.add(entity);
            }
          }
        });

    return result;
  }
}
