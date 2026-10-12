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

import com.google.common.annotations.VisibleForTesting;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.polaris.core.collection.ImmutableAttributeMap;
import org.apache.polaris.core.context.RealmContext;
import org.apache.polaris.core.rest.NamespaceUtils;
import org.apache.polaris.service.catalog.CatalogPrefixParser;
import org.apache.polaris.service.catalog.api.PolarisCatalogIcechunkTableApiService;
import org.apache.polaris.service.catalog.common.CatalogAdapter;
import org.apache.polaris.service.events.EventAttributes;
import org.apache.polaris.service.events.PolarisEvent;
import org.apache.polaris.service.events.PolarisEventDispatcher;
import org.apache.polaris.service.events.PolarisEventMetadataFactory;
import org.apache.polaris.service.events.PolarisEventType;
import org.apache.polaris.service.types.CreateIcechunkTableRequest;

/**
 * Emits Icechunk table events. Events carry only the catalog name and the table identifier (or the
 * namespace, for list), never request or response objects: responses can carry storage credentials.
 */
@Decorator
@Priority(1000)
public class CatalogIcechunkTableEventServiceDelegator
    implements PolarisCatalogIcechunkTableApiService, CatalogAdapter {

  @Inject @Delegate IcechunkTableCatalogAdapter delegate;
  @Inject PolarisEventDispatcher polarisEventDispatcher;
  @Inject PolarisEventMetadataFactory eventMetadataFactory;
  @Inject CatalogPrefixParser prefixParser;

  // Default constructor for CDI
  public CatalogIcechunkTableEventServiceDelegator() {}

  @VisibleForTesting
  public CatalogIcechunkTableEventServiceDelegator(
      IcechunkTableCatalogAdapter delegate,
      PolarisEventDispatcher polarisEventDispatcher,
      PolarisEventMetadataFactory eventMetadataFactory,
      CatalogPrefixParser prefixParser) {
    this.delegate = delegate;
    this.polarisEventDispatcher = polarisEventDispatcher;
    this.eventMetadataFactory = eventMetadataFactory;
    this.prefixParser = prefixParser;
  }

  @Override
  public Response createIcechunkTable(
      String prefix,
      String namespace,
      CreateIcechunkTableRequest createIcechunkTableRequest,
      String polarisIcechunkTableAccessDelegation,
      RealmContext realmContext,
      SecurityContext securityContext) {
    String catalogName = prefixParser.prefixToCatalogName(prefix);
    TableIdentifier identifier = tableIdentifier(namespace, createIcechunkTableRequest.getName());
    dispatchTableEvent(PolarisEventType.BEFORE_CREATE_ICECHUNK_TABLE, catalogName, identifier);
    Response resp =
        delegate.createIcechunkTable(
            prefix,
            namespace,
            createIcechunkTableRequest,
            polarisIcechunkTableAccessDelegation,
            realmContext,
            securityContext);
    dispatchTableEvent(PolarisEventType.AFTER_CREATE_ICECHUNK_TABLE, catalogName, identifier);
    return resp;
  }

  @Override
  public Response dropIcechunkTable(
      String prefix,
      String namespace,
      String icechunkTable,
      RealmContext realmContext,
      SecurityContext securityContext) {
    String catalogName = prefixParser.prefixToCatalogName(prefix);
    TableIdentifier identifier = tableIdentifier(namespace, icechunkTable);
    dispatchTableEvent(PolarisEventType.BEFORE_DROP_ICECHUNK_TABLE, catalogName, identifier);
    Response resp =
        delegate.dropIcechunkTable(prefix, namespace, icechunkTable, realmContext, securityContext);
    dispatchTableEvent(PolarisEventType.AFTER_DROP_ICECHUNK_TABLE, catalogName, identifier);
    return resp;
  }

  @Override
  public Response listIcechunkTables(
      String prefix,
      String namespace,
      String pageToken,
      Integer pageSize,
      RealmContext realmContext,
      SecurityContext securityContext) {
    String catalogName = prefixParser.prefixToCatalogName(prefix);
    Namespace namespaceObj =
        NamespaceUtils.splitNamespace(namespace, NamespaceUtils.DEFAULT_NAMESPACE_SEPARATOR);
    if (polarisEventDispatcher.hasListeners(PolarisEventType.BEFORE_LIST_ICECHUNK_TABLES)) {
      polarisEventDispatcher.dispatch(
          new PolarisEvent(
              PolarisEventType.BEFORE_LIST_ICECHUNK_TABLES,
              eventMetadataFactory.create(),
              ImmutableAttributeMap.builder()
                  .put(EventAttributes.CATALOG_NAME, catalogName)
                  .put(EventAttributes.NAMESPACE, namespaceObj)
                  .build()));
    }
    Response resp =
        delegate.listIcechunkTables(
            prefix, namespace, pageToken, pageSize, realmContext, securityContext);
    if (polarisEventDispatcher.hasListeners(PolarisEventType.AFTER_LIST_ICECHUNK_TABLES)) {
      polarisEventDispatcher.dispatch(
          new PolarisEvent(
              PolarisEventType.AFTER_LIST_ICECHUNK_TABLES,
              eventMetadataFactory.create(),
              ImmutableAttributeMap.builder()
                  .put(EventAttributes.CATALOG_NAME, catalogName)
                  .put(EventAttributes.NAMESPACE, namespaceObj)
                  .build()));
    }
    return resp;
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
    String catalogName = prefixParser.prefixToCatalogName(prefix);
    TableIdentifier identifier = tableIdentifier(namespace, icechunkTable);
    dispatchTableEvent(PolarisEventType.BEFORE_LOAD_ICECHUNK_TABLE, catalogName, identifier);
    Response resp =
        delegate.loadIcechunkTable(
            prefix,
            namespace,
            icechunkTable,
            polarisIcechunkTableAccessDelegation,
            accessMode,
            realmContext,
            securityContext);
    dispatchTableEvent(PolarisEventType.AFTER_LOAD_ICECHUNK_TABLE, catalogName, identifier);
    return resp;
  }

  @Override
  public Response loadIcechunkTableCredentials(
      String prefix,
      String namespace,
      String icechunkTable,
      String accessMode,
      RealmContext realmContext,
      SecurityContext securityContext) {
    String catalogName = prefixParser.prefixToCatalogName(prefix);
    TableIdentifier identifier = tableIdentifier(namespace, icechunkTable);
    dispatchTableEvent(
        PolarisEventType.BEFORE_LOAD_ICECHUNK_TABLE_CREDENTIALS, catalogName, identifier);
    Response resp =
        delegate.loadIcechunkTableCredentials(
            prefix, namespace, icechunkTable, accessMode, realmContext, securityContext);
    dispatchTableEvent(
        PolarisEventType.AFTER_LOAD_ICECHUNK_TABLE_CREDENTIALS, catalogName, identifier);
    return resp;
  }

  private void dispatchTableEvent(
      PolarisEventType type, String catalogName, TableIdentifier identifier) {
    if (polarisEventDispatcher.hasListeners(type)) {
      polarisEventDispatcher.dispatch(
          new PolarisEvent(
              type,
              eventMetadataFactory.create(),
              ImmutableAttributeMap.builder()
                  .put(EventAttributes.CATALOG_NAME, catalogName)
                  .put(EventAttributes.TABLE_IDENTIFIER, identifier)
                  .build()));
    }
  }

  private static TableIdentifier tableIdentifier(String namespace, String name) {
    return TableIdentifier.of(
        NamespaceUtils.splitNamespace(namespace, NamespaceUtils.DEFAULT_NAMESPACE_SEPARATOR), name);
  }
}
