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

import com.google.common.collect.ImmutableSet;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.apache.iceberg.rest.Endpoint;
import org.apache.polaris.core.config.FeatureConfiguration;
import org.apache.polaris.core.config.RealmConfig;
import org.apache.polaris.core.rest.IcechunkTableEndpoints;
import org.apache.polaris.service.catalog.spi.CatalogConfigEndpointContributor;

// Advertised right after the generic table endpoints (300) and before the policy endpoints (400).
@ApplicationScoped
@Priority(350)
public class IcechunkTableConfigEndpoints implements CatalogConfigEndpointContributor {
  private final RealmConfig realmConfig;

  @Inject
  public IcechunkTableConfigEndpoints(RealmConfig realmConfig) {
    this.realmConfig = realmConfig;
  }

  @Override
  public Set<Endpoint> endpoints() {
    return getSupportedIcechunkTableEndpoints(realmConfig);
  }

  /**
   * Get the Icechunk table endpoints. Returns ICECHUNK_TABLE_ENDPOINTS if ENABLE_ICECHUNK_TABLES is
   * set to true, otherwise, returns an empty set.
   */
  public static Set<Endpoint> getSupportedIcechunkTableEndpoints(RealmConfig realmConfig) {
    boolean icechunkTablesEnabled =
        realmConfig.getConfig(FeatureConfiguration.ENABLE_ICECHUNK_TABLES);
    return icechunkTablesEnabled
        ? IcechunkTableEndpoints.ICECHUNK_TABLE_ENDPOINTS
        : ImmutableSet.of();
  }
}
