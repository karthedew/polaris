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

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.polaris.core.auth.PolarisPrincipal;
import org.apache.polaris.core.config.FeatureConfiguration;
import org.apache.polaris.core.config.RealmConfig;
import org.apache.polaris.core.context.CallContext;
import org.apache.polaris.core.context.RealmContext;
import org.apache.polaris.core.entity.PolarisEntityConstants;
import org.apache.polaris.core.rest.NamespaceUtils;
import org.apache.polaris.core.rest.PolarisResourcePaths;
import org.apache.polaris.service.catalog.AccessDelegationMode;
import org.apache.polaris.service.catalog.CatalogPrefixParser;
import org.apache.polaris.service.catalog.api.PolarisCatalogIcechunkTableApiService;
import org.apache.polaris.service.catalog.common.CatalogAdapter;
import org.apache.polaris.service.catalog.validation.EntityNameValidator;
import org.apache.polaris.service.config.ReservedProperties;
import org.apache.polaris.service.types.CreateIcechunkTableRequest;
import org.apache.polaris.service.types.ListIcechunkTablesResponse;
import org.apache.polaris.service.types.LoadIcechunkTableCredentialsResponse;
import org.apache.polaris.service.types.LoadIcechunkTableResponse;

@RequestScoped
public class IcechunkTableCatalogAdapter
    implements PolarisCatalogIcechunkTableApiService, CatalogAdapter {

  private final RealmConfig realmConfig;
  private final ReservedProperties reservedProperties;
  private final CatalogPrefixParser prefixParser;
  private final IcechunkTableCatalogHandlerFactory handlerFactory;

  @Inject
  public IcechunkTableCatalogAdapter(
      CallContext callContext,
      CatalogPrefixParser prefixParser,
      ReservedProperties reservedProperties,
      IcechunkTableCatalogHandlerFactory handlerFactory) {
    this.realmConfig = callContext.getRealmConfig();
    this.prefixParser = prefixParser;
    this.reservedProperties = reservedProperties;
    this.handlerFactory = handlerFactory;
  }

  private IcechunkTableCatalogHandler newHandler(SecurityContext securityContext, String prefix) {
    FeatureConfiguration.enforceFeatureEnabledOrThrow(
        realmConfig, FeatureConfiguration.ENABLE_ICECHUNK_TABLES);
    PolarisPrincipal principal = validatePrincipal(securityContext);
    String catalogName = prefixParser.prefixToCatalogName(prefix);
    return handlerFactory.createHandler(catalogName, principal);
  }

  @Override
  public Response createIcechunkTable(
      String prefix,
      String namespace,
      CreateIcechunkTableRequest createIcechunkTableRequest,
      String polarisIcechunkTableAccessDelegation,
      RealmContext realmContext,
      SecurityContext securityContext) {
    IcechunkTableCatalogHandler handler = newHandler(securityContext, prefix);
    TableIdentifier identifier = tableIdentifier(namespace, createIcechunkTableRequest.getName());
    EntityNameValidator.validateIdentifier(identifier);
    String location = createIcechunkTableRequest.getLocation();
    if (location == null || location.isBlank()) {
      throw new BadRequestException("Icechunk table location is required");
    }
    if (createIcechunkTableRequest
        .getProperties()
        .containsKey(PolarisEntityConstants.ENTITY_BASE_LOCATION)) {
      throw new BadRequestException(
          "Icechunk table properties must not contain '%s'; the repository root goes in the "
              + "location field",
          PolarisEntityConstants.ENTITY_BASE_LOCATION);
    }
    Map<String, String> properties =
        reservedProperties.removeReservedProperties(createIcechunkTableRequest.getProperties());
    boolean vendCredentials = isVendedCredentialsRequested(polarisIcechunkTableAccessDelegation);
    // Create vends read-write credentials, so a refresh must ask for the same access.
    Optional<String> refreshCredentialsEndpoint =
        vendCredentials
            ? Optional.of(
                refreshCredentialsEndpoint(prefix, identifier, IcechunkTableAccessMode.READ_WRITE))
            : Optional.empty();
    LoadIcechunkTableResponse response =
        handler.createIcechunkTable(
            identifier, location, properties, vendCredentials, refreshCredentialsEndpoint);
    return Response.ok(response).build();
  }

  @Override
  public Response dropIcechunkTable(
      String prefix,
      String namespace,
      String icechunkTable,
      RealmContext realmContext,
      SecurityContext securityContext) {
    IcechunkTableCatalogHandler handler = newHandler(securityContext, prefix);
    handler.dropIcechunkTable(tableIdentifier(namespace, icechunkTable));
    return Response.noContent().build();
  }

  @Override
  public Response listIcechunkTables(
      String prefix,
      String namespace,
      String pageToken,
      Integer pageSize,
      RealmContext realmContext,
      SecurityContext securityContext) {
    IcechunkTableCatalogHandler handler = newHandler(securityContext, prefix);
    ListIcechunkTablesResponse response =
        handler.listIcechunkTables(
            NamespaceUtils.splitNamespace(namespace, NamespaceUtils.DEFAULT_NAMESPACE_SEPARATOR),
            pageToken,
            pageSize);
    return Response.ok(response).build();
  }

  @Override
  public Response loadIcechunkTable(
      String prefix,
      String namespace,
      String icechunkTable,
      String polarisIcechunkTableAccessDelegation,
      String accessMode,
      RealmContext realmContext,
      SecurityContext securityContext) {
    IcechunkTableCatalogHandler handler = newHandler(securityContext, prefix);
    TableIdentifier identifier = tableIdentifier(namespace, icechunkTable);
    boolean vendCredentials = isVendedCredentialsRequested(polarisIcechunkTableAccessDelegation);
    IcechunkTableAccessMode mode = IcechunkTableAccessMode.fromQueryValue(accessMode);
    Optional<String> refreshCredentialsEndpoint =
        vendCredentials
            ? Optional.of(refreshCredentialsEndpoint(prefix, identifier, mode))
            : Optional.empty();
    LoadIcechunkTableResponse response =
        handler.loadIcechunkTable(identifier, vendCredentials, mode, refreshCredentialsEndpoint);
    return Response.ok(response).build();
  }

  @Override
  public Response loadIcechunkTableCredentials(
      String prefix,
      String namespace,
      String icechunkTable,
      String accessMode,
      RealmContext realmContext,
      SecurityContext securityContext) {
    IcechunkTableCatalogHandler handler = newHandler(securityContext, prefix);
    TableIdentifier identifier = tableIdentifier(namespace, icechunkTable);
    IcechunkTableAccessMode mode = IcechunkTableAccessMode.fromQueryValue(accessMode);
    LoadIcechunkTableCredentialsResponse response =
        handler.loadIcechunkTableCredentials(
            identifier, mode, Optional.of(refreshCredentialsEndpoint(prefix, identifier, mode)));
    return Response.ok(response).build();
  }

  private static TableIdentifier tableIdentifier(String namespace, String name) {
    return TableIdentifier.of(
        NamespaceUtils.splitNamespace(namespace, NamespaceUtils.DEFAULT_NAMESPACE_SEPARATOR), name);
  }

  /**
   * Returns whether the access delegation header asks for vended credentials. An absent header asks
   * for none; any other mode, such as remote signing, is rejected.
   */
  private static boolean isVendedCredentialsRequested(String accessDelegation) {
    if (accessDelegation == null || accessDelegation.isBlank()) {
      return false;
    }
    if (AccessDelegationMode.fromProtocolValuesList(List.of(accessDelegation))
        .contains(AccessDelegationMode.VENDED_CREDENTIALS)) {
      return true;
    }
    throw new BadRequestException(
        "Unsupported access delegation mode '%s'; Icechunk tables support only %s",
        accessDelegation, AccessDelegationMode.VENDED_CREDENTIALS.protocolValue());
  }

  /** The credentials endpoint of the table, relative to the catalog API base URI. */
  private static String refreshCredentialsEndpoint(
      String prefix, TableIdentifier identifier, IcechunkTableAccessMode accessMode) {
    return new PolarisResourcePaths(prefix, NamespaceUtils.DEFAULT_NAMESPACE_SEPARATOR_ENCODED)
            .icechunkTableCredentials(identifier)
        + "?access-mode="
        + accessMode.queryValue();
  }
}
