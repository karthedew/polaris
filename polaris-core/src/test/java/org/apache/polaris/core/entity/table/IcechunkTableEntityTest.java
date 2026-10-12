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
package org.apache.polaris.core.entity.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.stream.Stream;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.polaris.core.entity.PolarisBaseEntity;
import org.apache.polaris.core.entity.PolarisEntity;
import org.apache.polaris.core.entity.PolarisEntityConstants;
import org.apache.polaris.core.entity.PolarisEntitySubType;
import org.apache.polaris.core.entity.PolarisEntityType;
import org.apache.polaris.core.entity.PolarisEntityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class IcechunkTableEntityTest {
  private static final String LOCATION = "s3://bucket/icechunk/weather";

  static Stream<Namespace> namespaces() {
    return Stream.of(Namespace.empty(), Namespace.of("weather"), Namespace.of("a/b", "%1F", "c d"));
  }

  @ParameterizedTest
  @MethodSource("namespaces")
  void testEntityRoundTrip(Namespace namespace) throws Exception {
    TableIdentifier identifier = TableIdentifier.of(namespace, "forecast");
    IcechunkTableEntity original =
        new IcechunkTableEntity.Builder(identifier)
            .setId(3)
            .setCatalogId(1)
            .setParentId(2)
            .setBaseLocation(LOCATION)
            .addProperty("description", "Weather forecast")
            .build();
    ObjectMapper mapper = new ObjectMapper();
    PolarisEntity persisted =
        mapper.readValue(mapper.writeValueAsString(original), PolarisEntity.class);
    IcechunkTableEntity restored = IcechunkTableEntity.of(persisted);

    assertThat(restored).isEqualTo(original);
    assertThat(restored.getType()).isEqualTo(PolarisEntityType.TABLE_LIKE);
    assertThat(restored.getSubType()).isEqualTo(PolarisEntitySubType.ICECHUNK_TABLE);
    assertThat(restored.getTableIdentifier()).isEqualTo(identifier);
    assertThat(restored.getBaseLocation()).isEqualTo(LOCATION);
    assertThat(restored.getPropertiesAsMap())
        .containsEntry(PolarisEntityConstants.ENTITY_BASE_LOCATION, LOCATION)
        .containsEntry("description", "Weather forecast");
    assertThat(PolarisEntityUtils.asLocationBasedEntity(persisted))
        .hasValueSatisfying(
            entity -> {
              assertThat(entity).isInstanceOf(IcechunkTableEntity.class);
              assertThat(entity.getBaseLocation()).isEqualTo(LOCATION);
            });
    assertThatThrownBy(() -> IcebergTableLikeEntity.of(persisted))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> GenericTableEntity.of(persisted))
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @EnumSource(
      value = PolarisEntitySubType.class,
      names = {"ICEBERG_TABLE", "ICEBERG_VIEW", "GENERIC_TABLE"})
  void testRejectsOtherTableSubtypes(PolarisEntitySubType subtype) {
    PolarisEntity entity =
        new PolarisEntity.Builder()
            .setType(PolarisEntityType.TABLE_LIKE)
            .setSubType(subtype)
            .setName("table")
            .build();
    assertThatThrownBy(() -> IcechunkTableEntity.of(entity))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Invalid entity sub type");
  }

  @ParameterizedTest
  @CsvSource({"ICEBERG_TABLE,2", "ICEBERG_VIEW,3", "GENERIC_TABLE,4", "ICECHUNK_TABLE,5"})
  void testStableSubtypeCodes(PolarisEntitySubType subtype, int code) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    assertThat(subtype.getCode()).isEqualTo(code);
    assertThat(subtype.getParentType()).isEqualTo(PolarisEntityType.TABLE_LIKE);
    assertThat(mapper.writeValueAsString(subtype)).isEqualTo(Integer.toString(code));
    assertThat(mapper.readValue(Integer.toString(code), PolarisEntitySubType.class))
        .isEqualTo(subtype);
  }

  @Test
  void testCopyBuilderAndRename() {
    IcechunkTableEntity original =
        new IcechunkTableEntity.Builder(TableIdentifier.of("weather", "forecast"))
            .setBaseLocation(LOCATION)
            .build();
    TableIdentifier renamed = TableIdentifier.of("renamed");
    IcechunkTableEntity copy =
        new IcechunkTableEntity.Builder(original).setTableIdentifier(renamed).build();
    assertThat(copy.getTableIdentifier()).isEqualTo(renamed);
    assertThat(copy.getBaseLocation()).isEqualTo(LOCATION);
    assertThat(original.getTableIdentifier()).isEqualTo(TableIdentifier.of("weather", "forecast"));
    assertThat(IcechunkTableEntity.of((PolarisBaseEntity) null)).isNull();
  }
}
