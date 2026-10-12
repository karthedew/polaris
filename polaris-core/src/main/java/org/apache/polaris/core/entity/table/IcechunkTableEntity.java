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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Preconditions;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.polaris.core.entity.NamespaceEntity;
import org.apache.polaris.core.entity.PolarisBaseEntity;
import org.apache.polaris.core.entity.PolarisEntity;
import org.apache.polaris.core.entity.PolarisEntityConstants;
import org.apache.polaris.core.entity.PolarisEntitySubType;
import org.apache.polaris.core.entity.PolarisEntityType;
import org.apache.polaris.core.entity.PolarisEntityUtils;
import org.jspecify.annotations.Nullable;

/** A catalog table backed by one Icechunk repository, whose contents are managed by Icechunk. */
public class IcechunkTableEntity extends TableLikeEntity {

  public IcechunkTableEntity(PolarisBaseEntity sourceEntity) {
    super(sourceEntity);
    Preconditions.checkState(
        getSubType() == PolarisEntitySubType.ICECHUNK_TABLE,
        "Invalid entity sub type: %s",
        getSubType());
  }

  public static @Nullable IcechunkTableEntity of(@Nullable PolarisBaseEntity sourceEntity) {
    return sourceEntity == null ? null : new IcechunkTableEntity(sourceEntity);
  }

  @Override
  @JsonIgnore
  public String getBaseLocation() {
    return getPropertiesAsMap().get(PolarisEntityConstants.ENTITY_BASE_LOCATION);
  }

  public static class Builder extends PolarisEntity.BaseBuilder<IcechunkTableEntity, Builder> {

    public Builder(TableIdentifier identifier) {
      setType(PolarisEntityType.TABLE_LIKE);
      setSubType(PolarisEntitySubType.ICECHUNK_TABLE);
      setTableIdentifier(identifier);
    }

    public Builder(IcechunkTableEntity original) {
      super(original);
    }

    public Builder setTableIdentifier(TableIdentifier identifier) {
      Namespace namespace = identifier.namespace();
      if (namespace.isEmpty()) {
        internalProperties.remove(NamespaceEntity.PARENT_NAMESPACE_KEY);
      } else {
        internalProperties.put(
            NamespaceEntity.PARENT_NAMESPACE_KEY, PolarisEntityUtils.encodeNamespace(namespace));
      }
      setName(identifier.name());
      return this;
    }

    public Builder setBaseLocation(String location) {
      properties.put(PolarisEntityConstants.ENTITY_BASE_LOCATION, location);
      return this;
    }

    @Override
    public IcechunkTableEntity build() {
      return new IcechunkTableEntity(buildBase());
    }
  }
}
