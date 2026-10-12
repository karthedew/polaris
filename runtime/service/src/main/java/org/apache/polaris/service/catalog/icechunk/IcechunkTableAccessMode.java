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

import org.apache.iceberg.exceptions.BadRequestException;

/** The storage access requested for vended Icechunk table credentials (the access-mode value). */
public enum IcechunkTableAccessMode {
  READ("read"),
  READ_WRITE("read-write"),
  ;

  private final String queryValue;

  IcechunkTableAccessMode(String queryValue) {
    this.queryValue = queryValue;
  }

  public String queryValue() {
    return queryValue;
  }

  /** Parses an access-mode value. An absent or blank value means {@link #READ}. */
  public static IcechunkTableAccessMode fromQueryValue(String value) {
    if (value == null || value.isBlank()) {
      return READ;
    }
    for (IcechunkTableAccessMode mode : values()) {
      if (mode.queryValue.equals(value)) {
        return mode;
      }
    }
    throw new BadRequestException(
        "Unsupported access-mode '%s'; supported values are '%s' and '%s'",
        value, READ.queryValue, READ_WRITE.queryValue);
  }
}
