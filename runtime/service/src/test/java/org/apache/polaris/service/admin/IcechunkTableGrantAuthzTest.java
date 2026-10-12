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
package org.apache.polaris.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.rest.requests.ImmutableRegisterTableRequest;
import org.apache.iceberg.rest.requests.RegisterTableRequest;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.apache.polaris.core.admin.model.AddGrantRequest;
import org.apache.polaris.core.admin.model.GrantResource;
import org.apache.polaris.core.admin.model.GrantResources;
import org.apache.polaris.core.admin.model.RevokeGrantRequest;
import org.apache.polaris.core.admin.model.TableGrant;
import org.apache.polaris.core.admin.model.TablePrivilege;
import org.apache.polaris.core.auth.PolarisPrincipal;
import org.apache.polaris.core.auth.PolarisPrincipalAttributes;
import org.apache.polaris.core.collection.ImmutableAttributeMap;
import org.apache.polaris.core.entity.PolarisEntitySubType;
import org.apache.polaris.core.entity.PolarisEntityType;
import org.apache.polaris.core.entity.PolarisPrivilege;
import org.apache.polaris.core.entity.table.IcechunkTableEntity;
import org.apache.polaris.service.Profiles;
import org.apache.polaris.service.catalog.AccessDelegationMode;
import org.apache.polaris.service.catalog.iceberg.IcebergCatalogHandlerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

@QuarkusTest
@TestProfile(Profiles.PolarisAuthzBaseProfile.class)
class IcechunkTableGrantAuthzTest extends PolarisAuthzTestBase {
  private static final TableIdentifier TABLE = TableIdentifier.of(NS1, "icechunk");
  private static final TableIdentifier OTHER_TABLE = TableIdentifier.of(NS1, "other-icechunk");

  @Inject IcebergCatalogHandlerFactory icebergCatalogHandlerFactory;

  @BeforeEach
  void createTables() {
    var namespace =
        metaStoreManager
            .readEntityByName(
                polarisContext,
                List.of(catalogEntity),
                PolarisEntityType.NAMESPACE,
                PolarisEntitySubType.NULL_SUBTYPE,
                NS1.level(0))
            .getEntity();
    for (TableIdentifier identifier : List.of(TABLE, OTHER_TABLE)) {
      var table =
          new IcechunkTableEntity.Builder(identifier)
              .setId(metaStoreManager.generateNewEntityId(polarisContext).getId())
              .setCreateTimestamp(System.currentTimeMillis())
              .setCatalogId(catalogEntity.getId())
              .setParentId(namespace.getId())
              .setBaseLocation("file:///tmp/authz/" + identifier.name())
              .build();
      assertSuccess(
          metaStoreManager.createEntityIfNotExists(
              polarisContext, List.of(catalogEntity, namespace), table));
    }
  }

  /** The non-root principal, acting through {@code CATALOG_ROLE1} only. */
  private PolarisPrincipal callerPrincipal() {
    return PolarisPrincipal.of(
        principalEntity.getName(),
        ImmutableAttributeMap.builder()
            .put(PolarisPrincipalAttributes.PRINCIPAL_ENTITY_ATTRIBUTE_KEY, principalEntity)
            .build(),
        Set.of(PRINCIPAL_ROLE1));
  }

  private PolarisAdminService caller() {
    return PolarisAdminServiceTestSupport.newAdminService(
        callContext,
        resolutionManifestFactory,
        metaStoreManager,
        userSecretsManager,
        serviceIdentityProvider,
        callerPrincipal(),
        polarisAuthorizer,
        reservedProperties);
  }

  @ParameterizedTest
  @EnumSource(TablePrivilege.class)
  void grantsRoundTripThroughManagementApi(TablePrivilege privilege) throws Exception {
    assertSuccess(
        newRootAdminService()
            .grantPrivilegeOnCatalogToRole(
                CATALOG_NAME, CATALOG_ROLE1, PolarisPrivilege.CATALOG_MANAGE_ACCESS));
    var api =
        new PolarisServiceImpl(realmConfig, reservedProperties, caller(), serviceIdentityProvider);
    var grant =
        new TableGrant(
            List.of(TABLE.namespace().levels()),
            TABLE.name(),
            privilege,
            GrantResource.TypeEnum.TABLE);
    ObjectMapper mapper = new ObjectMapper();
    AddGrantRequest request =
        mapper.readValue(
            mapper.writeValueAsString(new AddGrantRequest(grant)), AddGrantRequest.class);
    assertThat(request.getGrant()).isEqualTo(grant);
    try (Response response =
        api.addGrantToCatalogRole(CATALOG_NAME, CATALOG_ROLE2, request, null, null)) {
      assertThat(response.getStatus()).isEqualTo(201);
    }
    try (Response response =
        api.listGrantsForCatalogRole(CATALOG_NAME, CATALOG_ROLE2, null, null)) {
      assertThat(((GrantResources) response.getEntity()).getGrants()).containsExactly(grant);
    }
    try (Response response =
        api.revokeGrantFromCatalogRole(
            CATALOG_NAME, CATALOG_ROLE2, false, new RevokeGrantRequest(grant), null, null)) {
      assertThat(response.getStatus()).isEqualTo(201);
    }
    assertThat(caller().listGrantsForCatalogRole(CATALOG_NAME, CATALOG_ROLE2)).isEmpty();
  }

  @Test
  void tableGrantManagementRespectsScopeAndGranteePermissions() {
    assertThatThrownBy(
            () ->
                caller()
                    .grantPrivilegeOnTableToRole(
                        CATALOG_NAME, CATALOG_ROLE2, TABLE, PolarisPrivilege.TABLE_READ_DATA))
        .isInstanceOf(ForbiddenException.class);
    assertSuccess(
        newRootAdminService()
            .grantPrivilegeOnTableToRole(
                CATALOG_NAME,
                CATALOG_ROLE1,
                TABLE,
                PolarisPrivilege.TABLE_MANAGE_GRANTS_ON_SECURABLE));
    assertSuccess(
        caller()
            .grantPrivilegeOnTableToRole(
                CATALOG_NAME, CATALOG_ROLE2, TABLE, PolarisPrivilege.TABLE_READ_DATA));
    assertThatThrownBy(
            () ->
                caller()
                    .grantPrivilegeOnTableToRole(
                        CATALOG_NAME, CATALOG_ROLE2, OTHER_TABLE, PolarisPrivilege.TABLE_READ_DATA))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> caller().listGrantsForCatalogRole(CATALOG_NAME, CATALOG_ROLE2))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(
            () ->
                caller()
                    .revokePrivilegeOnTableFromRole(
                        CATALOG_NAME, CATALOG_ROLE2, TABLE, PolarisPrivilege.TABLE_READ_DATA))
        .isInstanceOf(ForbiddenException.class);
    assertSuccess(
        newRootAdminService()
            .grantPrivilegeOnCatalogToRole(
                CATALOG_NAME,
                CATALOG_ROLE1,
                PolarisPrivilege.CATALOG_ROLE_MANAGE_GRANTS_FOR_GRANTEE));
    assertThat(caller().listGrantsForCatalogRole(CATALOG_NAME, CATALOG_ROLE2))
        .containsExactly(
            new TableGrant(
                List.of(NS1.levels()),
                TABLE.name(),
                TablePrivilege.TABLE_READ_DATA,
                GrantResource.TypeEnum.TABLE));
    assertSuccess(
        caller()
            .revokePrivilegeOnTableFromRole(
                CATALOG_NAME, CATALOG_ROLE2, TABLE, PolarisPrivilege.TABLE_READ_DATA));
    assertThat(caller().listGrantsForCatalogRole(CATALOG_NAME, CATALOG_ROLE2)).isEmpty();
  }

  @Test
  void listGrantsIncludesIcechunkIcebergAndGenericTables() {
    List<TableIdentifier> identifiers = List.of(TABLE, TABLE_NS1_1, TABLE_NS1_1_GENERIC);
    for (TableIdentifier identifier : identifiers) {
      assertSuccess(
          newRootAdminService()
              .grantPrivilegeOnTableToRole(
                  CATALOG_NAME, CATALOG_ROLE2, identifier, PolarisPrivilege.TABLE_READ_DATA));
    }
    assertThat(newRootAdminService().listGrantsForCatalogRole(CATALOG_NAME, CATALOG_ROLE2))
        .containsExactlyInAnyOrderElementsOf(
            identifiers.stream()
                .map(
                    identifier ->
                        new TableGrant(
                            List.of(identifier.namespace().levels()),
                            identifier.name(),
                            TablePrivilege.TABLE_READ_DATA,
                            GrantResource.TypeEnum.TABLE))
                .toList());
  }

  @Test
  void icebergRenameCannotReplaceIcechunkTable() throws Exception {
    // The caller may drop the source but lacks the privileges the destination namespace needs, so
    // only the same-name check that runs before authorization reports the conflict.
    assertSuccess(
        newRootAdminService()
            .grantPrivilegeOnTableToRole(
                CATALOG_NAME, CATALOG_ROLE1, TABLE_NS1_1, PolarisPrivilege.TABLE_DROP));
    var namespace =
        metaStoreManager
            .readEntityByName(
                polarisContext,
                List.of(catalogEntity),
                PolarisEntityType.NAMESPACE,
                PolarisEntitySubType.NULL_SUBTYPE,
                NS1.level(0))
            .getEntity();
    var before =
        metaStoreManager.listFullEntitiesAll(
            polarisContext,
            List.of(catalogEntity, namespace),
            PolarisEntityType.TABLE_LIKE,
            PolarisEntitySubType.ANY_SUBTYPE);
    try (var handler =
        icebergCatalogHandlerFactory.createHandler(CATALOG_NAME, callerPrincipal())) {
      var request =
          RenameTableRequest.builder().withSource(TABLE_NS1_1).withDestination(TABLE).build();
      assertThatThrownBy(() -> handler.renameTable(request))
          .isInstanceOf(AlreadyExistsException.class)
          .hasMessage("Cannot rename %s to %s. Icechunk table already exists", TABLE_NS1_1, TABLE);
    }
    assertThat(
            metaStoreManager.listFullEntitiesAll(
                polarisContext,
                List.of(catalogEntity, namespace),
                PolarisEntityType.TABLE_LIKE,
                PolarisEntitySubType.ANY_SUBTYPE))
        .containsExactlyInAnyOrderElementsOf(before);
  }

  @ParameterizedTest
  @CsvSource({
    "icechunk, Icechunk table, false",
    "icechunk, Icechunk table, true",
    "layer1_table_generic, Generic table, false",
    "layer1_table_generic, Generic table, true"
  })
  void icebergRegisterOverwriteCannotReplaceNonIcebergTable(
      String name, String label, boolean vendCredentials) throws Exception {
    TableIdentifier identifier = TableIdentifier.of(NS1, name);
    RegisterTableRequest request =
        ImmutableRegisterTableRequest.builder()
            .name(name)
            .metadataLocation("file:///tmp/authz/" + name + "/metadata/v1.metadata.json")
            .overwrite(true)
            .build();
    EnumSet<AccessDelegationMode> delegationModes =
        vendCredentials
            ? EnumSet.of(AccessDelegationMode.VENDED_CREDENTIALS)
            : EnumSet.noneOf(AccessDelegationMode.class);
    var namespace =
        metaStoreManager
            .readEntityByName(
                polarisContext,
                List.of(catalogEntity),
                PolarisEntityType.NAMESPACE,
                PolarisEntitySubType.NULL_SUBTYPE,
                NS1.level(0))
            .getEntity();
    var before =
        metaStoreManager.listFullEntitiesAll(
            polarisContext,
            List.of(catalogEntity, namespace),
            PolarisEntityType.TABLE_LIKE,
            PolarisEntitySubType.ANY_SUBTYPE);

    // Without the privileges that registering needs, the caller learns nothing about the name.
    try (var handler =
        icebergCatalogHandlerFactory.createHandler(CATALOG_NAME, callerPrincipal())) {
      assertThatThrownBy(
              () -> handler.registerTable(NS1, request, delegationModes, Optional.empty()))
          .isInstanceOf(ForbiddenException.class);
    }

    assertSuccess(
        newRootAdminService()
            .grantPrivilegeOnNamespaceToRole(
                CATALOG_NAME, CATALOG_ROLE1, NS1, PolarisPrivilege.TABLE_CREATE));
    if (vendCredentials) {
      // REGISTER_TABLE_WITH_READ_DELEGATION requires TABLE_CREATE and TABLE_READ_DATA.
      assertSuccess(
          newRootAdminService()
              .grantPrivilegeOnNamespaceToRole(
                  CATALOG_NAME, CATALOG_ROLE1, NS1, PolarisPrivilege.TABLE_READ_DATA));
    }
    // The conflict is reported before the metadata file, which does not exist, is read.
    try (var handler =
        icebergCatalogHandlerFactory.createHandler(CATALOG_NAME, callerPrincipal())) {
      assertThatThrownBy(
              () -> handler.registerTable(NS1, request, delegationModes, Optional.empty()))
          .isInstanceOf(AlreadyExistsException.class)
          .hasMessage("%s with same name already exists: %s", label, identifier);
    }
    assertThat(
            metaStoreManager.listFullEntitiesAll(
                polarisContext,
                List.of(catalogEntity, namespace),
                PolarisEntityType.TABLE_LIKE,
                PolarisEntitySubType.ANY_SUBTYPE))
        .containsExactlyInAnyOrderElementsOf(before);
  }

  @Test
  void externalTableGrantStillCreatesSyntheticIcebergTable() {
    assertSuccess(
        newRootAdminService()
            .grantPrivilegeOnTableToRole(
                FEDERATED_CATALOG_NAME, CATALOG_ROLE2, TABLE, PolarisPrivilege.TABLE_READ_DATA));
    var namespace =
        metaStoreManager
            .readEntityByName(
                polarisContext,
                List.of(federatedCatalogEntity),
                PolarisEntityType.NAMESPACE,
                PolarisEntitySubType.NULL_SUBTYPE,
                NS1.level(0))
            .getEntity();
    var table =
        metaStoreManager.readEntityByName(
            polarisContext,
            List.of(federatedCatalogEntity, namespace),
            PolarisEntityType.TABLE_LIKE,
            PolarisEntitySubType.ANY_SUBTYPE,
            TABLE.name());
    assertSuccess(table);
    assertThat(table.getEntity().getSubType()).isEqualTo(PolarisEntitySubType.ICEBERG_TABLE);
  }
}
