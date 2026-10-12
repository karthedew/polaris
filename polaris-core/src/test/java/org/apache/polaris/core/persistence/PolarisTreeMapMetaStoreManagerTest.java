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
package org.apache.polaris.core.persistence;

import static org.apache.polaris.core.persistence.PrincipalSecretsGenerator.RANDOM_SECRETS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.polaris.core.PolarisCallContext;
import org.apache.polaris.core.PolarisDefaultDiagServiceImpl;
import org.apache.polaris.core.PolarisDiagnostics;
import org.apache.polaris.core.entity.PolarisBaseEntity;
import org.apache.polaris.core.entity.PolarisEntityConstants;
import org.apache.polaris.core.entity.PolarisEntityCore;
import org.apache.polaris.core.entity.PolarisEntitySubType;
import org.apache.polaris.core.entity.PolarisEntityType;
import org.apache.polaris.core.entity.table.IcechunkTableEntity;
import org.apache.polaris.core.persistence.transactional.TransactionalMetaStoreManagerImpl;
import org.apache.polaris.core.persistence.transactional.TreeMapMetaStore;
import org.apache.polaris.core.persistence.transactional.TreeMapTransactionalPersistenceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class PolarisTreeMapMetaStoreManagerTest extends BasePolarisMetaStoreManagerTest {
  @Override
  public PolarisTestMetaStoreManager createPolarisTestMetaStoreManager() {
    PolarisDiagnostics diagServices = new PolarisDefaultDiagServiceImpl();
    TreeMapMetaStore store = new TreeMapMetaStore(diagServices);
    TreeMapTransactionalPersistenceImpl metaStore =
        new TreeMapTransactionalPersistenceImpl(
            diagServices, store, Mockito.mock(), RANDOM_SECRETS);
    TransactionalMetaStoreManagerImpl metaStoreManager =
        new TransactionalMetaStoreManagerImpl(clock, diagServices);
    PolarisCallContext callCtx = new PolarisCallContext(() -> "testRealm", metaStore);
    return new PolarisTestMetaStoreManager(metaStoreManager, callCtx);
  }

  @Test
  void testOverlappingSiblingsSkipsEntitiesWithoutLocation() {
    PolarisMetaStoreManager metaStoreManager = polarisTestMetaStoreManager.polarisMetaStoreManager;
    PolarisCallContext callCtx = polarisTestMetaStoreManager.polarisCallContext;
    PolarisBaseEntity catalog = polarisTestMetaStoreManager.createTestCatalog("catalog");
    // Neither the namespaces nor the first Icechunk table carry a location property.
    PolarisBaseEntity namespace =
        polarisTestMetaStoreManager.createEntity(
            List.of(catalog), PolarisEntityType.NAMESPACE, "ns");
    List<PolarisEntityCore> path = List.of(catalog, namespace);
    polarisTestMetaStoreManager.createEntity(
        path, PolarisEntityType.TABLE_LIKE, PolarisEntitySubType.ICECHUNK_TABLE, "no-location");
    IcechunkTableEntity candidate =
        new IcechunkTableEntity.Builder(TableIdentifier.of("ns", "candidate"))
            .setCatalogId(catalog.getId())
            .setParentId(namespace.getId())
            .setBaseLocation("s3://bucket/repo/child/")
            .build();
    assertThat(metaStoreManager.hasOverlappingSiblings(callCtx, path, candidate))
        .contains(Optional.empty());

    // A sibling that carries a location is still found.
    polarisTestMetaStoreManager.createEntity(
        path,
        PolarisEntityType.TABLE_LIKE,
        PolarisEntitySubType.ICECHUNK_TABLE,
        "repo",
        Map.of(PolarisEntityConstants.ENTITY_BASE_LOCATION, "s3://bucket/repo/"));
    assertThat(metaStoreManager.hasOverlappingSiblings(callCtx, path, candidate))
        .hasValueSatisfying(sibling -> assertThat(sibling).isPresent());
  }
}
