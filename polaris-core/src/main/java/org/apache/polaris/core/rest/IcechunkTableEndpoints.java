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
package org.apache.polaris.core.rest;

import com.google.common.collect.ImmutableSet;
import java.util.Set;
import org.apache.iceberg.rest.Endpoint;

// TODO: Shares the Icechunk table endpoint constants with clients, like GenericTableEndpoints.
// Endpoint definitions should not be exposed from polaris-core; move both classes to a dedicated
// shared API module when one exists.
public class IcechunkTableEndpoints {
  private IcechunkTableEndpoints() {}

  public static final Endpoint V1_LIST_ICECHUNK_TABLES =
      Endpoint.create("GET", PolarisResourcePaths.V1_ICECHUNK_TABLES);
  public static final Endpoint V1_CREATE_ICECHUNK_TABLE =
      Endpoint.create("POST", PolarisResourcePaths.V1_ICECHUNK_TABLES);
  public static final Endpoint V1_LOAD_ICECHUNK_TABLE =
      Endpoint.create("GET", PolarisResourcePaths.V1_ICECHUNK_TABLE);
  public static final Endpoint V1_DELETE_ICECHUNK_TABLE =
      Endpoint.create("DELETE", PolarisResourcePaths.V1_ICECHUNK_TABLE);
  public static final Endpoint V1_LOAD_ICECHUNK_TABLE_CREDENTIALS =
      Endpoint.create("GET", PolarisResourcePaths.V1_ICECHUNK_TABLE_CREDENTIALS);

  public static final Set<Endpoint> ICECHUNK_TABLE_ENDPOINTS =
      ImmutableSet.<Endpoint>builder()
          .add(V1_LIST_ICECHUNK_TABLES)
          .add(V1_CREATE_ICECHUNK_TABLE)
          .add(V1_DELETE_ICECHUNK_TABLE)
          .add(V1_LOAD_ICECHUNK_TABLE)
          .add(V1_LOAD_ICECHUNK_TABLE_CREDENTIALS)
          .build();
}
