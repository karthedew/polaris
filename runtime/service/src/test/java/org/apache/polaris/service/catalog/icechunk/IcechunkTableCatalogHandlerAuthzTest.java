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

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.polaris.core.auth.PolarisPrincipal;
import org.apache.polaris.core.auth.PolarisPrincipalAttributes;
import org.apache.polaris.core.collection.ImmutableAttributeMap;
import org.apache.polaris.core.entity.PolarisGrantRecord;
import org.apache.polaris.core.entity.PolarisPrivilege;
import org.apache.polaris.core.persistence.dao.entity.BaseResult;
import org.apache.polaris.core.persistence.dao.entity.PrivilegeResult;
import org.apache.polaris.service.Profiles;
import org.apache.polaris.service.admin.PolarisAuthzTestBase;
import org.apache.polaris.service.admin.PolarisAuthzTestsFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

@QuarkusTest
@TestProfile(Profiles.PolarisAuthzBaseProfile.class)
public class IcechunkTableCatalogHandlerAuthzTest extends PolarisAuthzTestBase {

  // An Icechunk table directly under ns1, inside the namespace location so that it can be vended
  private static final TableIdentifier TABLE_NS1_1_ICECHUNK =
      TableIdentifier.of(NS1, "layer1_table_icechunk");
  private static final String TABLE_NS1_1_ICECHUNK_LOCATION =
      "file:///tmp/authz/ns1/layer1_table_icechunk";

  private static final TableIdentifier NEW_TABLE = TableIdentifier.of(NS2, "newtable");
  private static final String NEW_TABLE_LOCATION = "file:///tmp/authz/ns2/newtable";

  @BeforeEach
  void createIcechunkTable() {
    newWrapper(authenticatedRoot)
        .createIcechunkTable(
            TABLE_NS1_1_ICECHUNK, TABLE_NS1_1_ICECHUNK_LOCATION, Map.of(), false, Optional.empty());
  }

  private IcechunkTableCatalogHandler newWrapper() {
    return newWrapper(
        PolarisPrincipal.of(
            principalEntity.getName(),
            ImmutableAttributeMap.builder()
                .put(PolarisPrincipalAttributes.PRINCIPAL_ENTITY_ATTRIBUTE_KEY, principalEntity)
                .put(PolarisPrincipalAttributes.PRINCIPAL_ROLE_ALL_ATTRIBUTE_KEY, true)
                .build(),
            Set.of()));
  }

  private IcechunkTableCatalogHandler newWrapper(Set<String> activatedPrincipalRoles) {
    return newWrapper(
        PolarisPrincipal.of(
            principalEntity.getName(),
            ImmutableAttributeMap.builder()
                .put(PolarisPrincipalAttributes.PRINCIPAL_ENTITY_ATTRIBUTE_KEY, principalEntity)
                .build(),
            activatedPrincipalRoles));
  }

  private IcechunkTableCatalogHandler newWrapper(PolarisPrincipal authenticatedPrincipal) {
    return ImmutableIcechunkTableCatalogHandler.builder()
        .catalogName(PolarisAuthzTestBase.CATALOG_NAME)
        .polarisPrincipal(authenticatedPrincipal)
        .callContext(callContext)
        .resolutionManifestFactory(resolutionManifestFactory)
        .metaStoreManager(metaStoreManager)
        .authorizer(polarisAuthorizer)
        .storageAccessConfigProvider(storageAccessConfigProvider)
        .build();
  }

  @TestFactory
  Stream<DynamicNode> testListIcechunkTablesPrivileges() {
    return authzTestsBuilder("listIcechunkTables")
        .action(() -> newWrapper().listIcechunkTables(NS1, null, null))
        .shouldPassWith(PolarisPrivilege.TABLE_LIST)
        .shouldPassWith(PolarisPrivilege.TABLE_CREATE)
        .shouldPassWith(PolarisPrivilege.TABLE_READ_PROPERTIES)
        .shouldPassWith(PolarisPrivilege.TABLE_WRITE_PROPERTIES)
        .shouldPassWith(PolarisPrivilege.TABLE_READ_DATA)
        .shouldPassWith(PolarisPrivilege.TABLE_WRITE_DATA)
        .shouldPassWith(PolarisPrivilege.TABLE_FULL_METADATA)
        .shouldPassWith(PolarisPrivilege.CATALOG_MANAGE_CONTENT)
        .shouldPassWith(PolarisPrivilege.CATALOG_MANAGE_METADATA)
        .createTests();
  }

  @TestFactory
  Stream<DynamicNode> testCreateIcechunkTablePrivileges() {
    assertSuccess(
        newRootAdminService()
            .grantPrivilegeOnCatalogToRole(
                CATALOG_NAME, CATALOG_ROLE2, PolarisPrivilege.TABLE_DROP));

    // Use PRINCIPAL_ROLE1 for privilege-testing, PRINCIPAL_ROLE2 for cleanup.
    return authzTestsBuilder("createIcechunkTable")
        .action(
            () ->
                newWrapper(Set.of(PRINCIPAL_ROLE1))
                    .createIcechunkTable(
                        NEW_TABLE, NEW_TABLE_LOCATION, Map.of(), false, Optional.empty()))
        .cleanupAction(() -> newWrapper(Set.of(PRINCIPAL_ROLE2)).dropIcechunkTable(NEW_TABLE))
        .shouldPassWith(PolarisPrivilege.TABLE_CREATE)
        .shouldPassWith(PolarisPrivilege.TABLE_FULL_METADATA)
        .shouldPassWith(PolarisPrivilege.CATALOG_MANAGE_CONTENT)
        .shouldPassWith(PolarisPrivilege.CATALOG_MANAGE_METADATA)
        .createTests();
  }

  @TestFactory
  Stream<DynamicNode> testCreateIcechunkTableWithWriteDelegationPrivileges() {
    assertSuccess(
        newRootAdminService()
            .grantPrivilegeOnCatalogToRole(
                CATALOG_NAME, CATALOG_ROLE2, PolarisPrivilege.TABLE_DROP));

    // Use PRINCIPAL_ROLE1 for privilege-testing, PRINCIPAL_ROLE2 for cleanup.
    return authzTestsBuilder("createIcechunkTableWithWriteDelegation")
        .action(
            () ->
                newWrapper(Set.of(PRINCIPAL_ROLE1))
                    .createIcechunkTable(
                        NEW_TABLE, NEW_TABLE_LOCATION, Map.of(), true, Optional.empty()))
        .cleanupAction(() -> newWrapper(Set.of(PRINCIPAL_ROLE2)).dropIcechunkTable(NEW_TABLE))
        .shouldPassWith(PolarisPrivilege.TABLE_CREATE, PolarisPrivilege.TABLE_WRITE_DATA)
        .shouldPassWith(PolarisPrivilege.CATALOG_MANAGE_CONTENT)
        // Creating is not enough: the vended credentials can write
        .shouldFailWith(PolarisPrivilege.TABLE_CREATE)
        .shouldFailWith(PolarisPrivilege.TABLE_FULL_METADATA)
        .createTests();
  }

  @TestFactory
  Stream<DynamicNode> testLoadIcechunkTablePrivileges() {
    return authzTestsBuilder("loadIcechunkTable")
        .action(
            () ->
                newWrapper()
                    .loadIcechunkTable(
                        TABLE_NS1_1_ICECHUNK,
                        false,
                        IcechunkTableAccessMode.READ,
                        Optional.empty()))
        .shouldPassWith(PolarisPrivilege.TABLE_READ_PROPERTIES)
        .shouldPassWith(PolarisPrivilege.TABLE_WRITE_PROPERTIES)
        .shouldPassWith(PolarisPrivilege.TABLE_READ_DATA)
        .shouldPassWith(PolarisPrivilege.TABLE_WRITE_DATA)
        .shouldPassWith(PolarisPrivilege.TABLE_FULL_METADATA)
        .shouldPassWith(PolarisPrivilege.CATALOG_MANAGE_CONTENT)
        .shouldPassWith(PolarisPrivilege.CATALOG_MANAGE_METADATA)
        .createTests();
  }

  @TestFactory
  Stream<DynamicNode> testLoadIcechunkTableWithReadDelegationPrivileges() {
    return authzTestsBuilder("loadIcechunkTableWithReadDelegation")
        .action(
            () ->
                newWrapper()
                    .loadIcechunkTable(
                        TABLE_NS1_1_ICECHUNK, true, IcechunkTableAccessMode.READ, Optional.empty()))
        .shouldPassWith(PolarisPrivilege.TABLE_READ_DATA)
        .shouldPassWith(PolarisPrivilege.TABLE_WRITE_DATA)
        .shouldPassWith(PolarisPrivilege.CATALOG_MANAGE_CONTENT)
        .shouldFailWith(PolarisPrivilege.TABLE_FULL_METADATA)
        .createTests();
  }

  @TestFactory
  Stream<DynamicNode> testLoadIcechunkTableWithWriteDelegationPrivileges() {
    return authzTestsBuilder("loadIcechunkTableWithWriteDelegation")
        .action(
            () ->
                newWrapper()
                    .loadIcechunkTable(
                        TABLE_NS1_1_ICECHUNK,
                        true,
                        IcechunkTableAccessMode.READ_WRITE,
                        Optional.empty()))
        .shouldPassWith(PolarisPrivilege.TABLE_WRITE_DATA)
        .shouldPassWith(PolarisPrivilege.CATALOG_MANAGE_CONTENT)
        // Read access never turns into write credentials
        .shouldFailWith(PolarisPrivilege.TABLE_READ_DATA)
        .shouldFailWith(PolarisPrivilege.TABLE_FULL_METADATA)
        .createTests();
  }

  @TestFactory
  Stream<DynamicNode> testLoadIcechunkTableCredentialsPrivileges() {
    return authzTestsBuilder("loadIcechunkTableCredentials")
        .action(
            () ->
                newWrapper()
                    .loadIcechunkTableCredentials(
                        TABLE_NS1_1_ICECHUNK, IcechunkTableAccessMode.READ, Optional.empty()))
        .shouldPassWith(PolarisPrivilege.TABLE_READ_DATA)
        .shouldPassWith(PolarisPrivilege.TABLE_WRITE_DATA)
        .shouldPassWith(PolarisPrivilege.CATALOG_MANAGE_CONTENT)
        .shouldFailWith(PolarisPrivilege.TABLE_READ_PROPERTIES)
        .shouldFailWith(PolarisPrivilege.TABLE_FULL_METADATA)
        .createTests();
  }

  @TestFactory
  Stream<DynamicNode> testLoadIcechunkTableReadWriteCredentialsPrivileges() {
    return authzTestsBuilder("loadIcechunkTableReadWriteCredentials")
        .action(
            () ->
                newWrapper()
                    .loadIcechunkTableCredentials(
                        TABLE_NS1_1_ICECHUNK, IcechunkTableAccessMode.READ_WRITE, Optional.empty()))
        .shouldPassWith(PolarisPrivilege.TABLE_WRITE_DATA)
        .shouldPassWith(PolarisPrivilege.CATALOG_MANAGE_CONTENT)
        .shouldFailWith(PolarisPrivilege.TABLE_READ_DATA)
        .shouldFailWith(PolarisPrivilege.TABLE_FULL_METADATA)
        .createTests();
  }

  @TestFactory
  Stream<DynamicNode> testDropIcechunkTablePrivileges() {
    assertSuccess(
        newRootAdminService()
            .grantPrivilegeOnCatalogToRole(
                CATALOG_NAME, CATALOG_ROLE2, PolarisPrivilege.TABLE_CREATE));

    // Use PRINCIPAL_ROLE1 for privilege-testing, PRINCIPAL_ROLE2 for cleanup.
    return authzTestsBuilder("dropIcechunkTable")
        .action(() -> newWrapper(Set.of(PRINCIPAL_ROLE1)).dropIcechunkTable(TABLE_NS1_1_ICECHUNK))
        .cleanupAction(
            () ->
                newWrapper(Set.of(PRINCIPAL_ROLE2))
                    .createIcechunkTable(
                        TABLE_NS1_1_ICECHUNK,
                        TABLE_NS1_1_ICECHUNK_LOCATION,
                        Map.of(),
                        false,
                        Optional.empty()))
        .shouldPassWith(PolarisPrivilege.TABLE_DROP)
        .shouldPassWith(PolarisPrivilege.TABLE_FULL_METADATA)
        .shouldPassWith(PolarisPrivilege.CATALOG_MANAGE_CONTENT)
        .shouldPassWith(PolarisPrivilege.CATALOG_MANAGE_METADATA)
        .createTests();
  }

  private PolarisAuthzTestsFactory.Builder tableLevelAuthzTestsBuilder(
      String operationName, TableIdentifier tableId) {
    return authzTestsBuilder(operationName)
        .grantAction(
            priv ->
                newRootAdminService()
                    .grantPrivilegeOnTableToRole(CATALOG_NAME, CATALOG_ROLE1, tableId, priv))
        .revokeAction(
            priv -> {
              PrivilegeResult res =
                  newRootAdminService()
                      .revokePrivilegeOnTableFromRole(CATALOG_NAME, CATALOG_ROLE1, tableId, priv);
              // After table drop + recreate, grants on the old entity no longer exist on the
              // new entity. Only treat GRANT_NOT_FOUND or ENTITY_NOT_FOUND as acceptable —
              // any other failure should propagate to avoid masking real errors.
              if (!res.isSuccess()) {
                BaseResult.ReturnStatus status = res.getReturnStatus();
                if (status == BaseResult.ReturnStatus.GRANT_NOT_FOUND
                    || status == BaseResult.ReturnStatus.ENTITY_NOT_FOUND) {
                  return new PrivilegeResult(new PolarisGrantRecord());
                }
              }
              return res;
            });
  }

  /**
   * Tests that the read-write credentials honor table-level privilege grants on the Icechunk table,
   * which requires the authorization to resolve the Icechunk table entity.
   */
  @TestFactory
  Stream<DynamicNode> testLoadIcechunkTableReadWriteCredentialsWithTableLevelPrivileges() {
    return tableLevelAuthzTestsBuilder(
            "loadIcechunkTableReadWriteCredentialsWithTableLevelGrant", TABLE_NS1_1_ICECHUNK)
        .action(
            () ->
                newWrapper(Set.of(PRINCIPAL_ROLE1))
                    .loadIcechunkTableCredentials(
                        TABLE_NS1_1_ICECHUNK, IcechunkTableAccessMode.READ_WRITE, Optional.empty()))
        .shouldPassWith(PolarisPrivilege.TABLE_WRITE_DATA)
        .shouldPassWith(PolarisPrivilege.CATALOG_MANAGE_CONTENT)
        .shouldFailWith(PolarisPrivilege.TABLE_READ_DATA)
        .createTests();
  }

  /**
   * Tests that dropIcechunkTable works with table-level privilege grants. This verifies that the
   * authorization resolves the table entity (not just the namespace), which is required for
   * table-level grants to take effect.
   */
  @TestFactory
  Stream<DynamicNode> testDropIcechunkTableWithTableLevelPrivileges() {
    assertSuccess(
        newRootAdminService()
            .grantPrivilegeOnCatalogToRole(
                CATALOG_NAME, CATALOG_ROLE2, PolarisPrivilege.TABLE_CREATE));

    return tableLevelAuthzTestsBuilder("dropIcechunkTableWithTableLevelGrant", TABLE_NS1_1_ICECHUNK)
        .action(() -> newWrapper(Set.of(PRINCIPAL_ROLE1)).dropIcechunkTable(TABLE_NS1_1_ICECHUNK))
        .cleanupAction(
            () ->
                newWrapper(Set.of(PRINCIPAL_ROLE2))
                    .createIcechunkTable(
                        TABLE_NS1_1_ICECHUNK,
                        TABLE_NS1_1_ICECHUNK_LOCATION,
                        Map.of(),
                        false,
                        Optional.empty()))
        .shouldPassWith(PolarisPrivilege.TABLE_DROP)
        .shouldPassWith(PolarisPrivilege.TABLE_FULL_METADATA)
        .shouldPassWith(PolarisPrivilege.CATALOG_MANAGE_CONTENT)
        .shouldPassWith(PolarisPrivilege.CATALOG_MANAGE_METADATA)
        .createTests();
  }
}
