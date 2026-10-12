---
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
Title: Entities
type: docs
weight: 400
---

This page documents various entities that can be managed in Apache Polaris.

## Entity name constraints

The REST layer enforces the following rules for entity names (namespace levels, table names, view names, and generic table names). Names that violate these rules are rejected with HTTP 400.

A valid entity name:

- is not empty;
- is not `.` or `..`;
- does not contain ISO control characters (U+0000–U+001F or U+007F–U+009F);
- does not contain any of the following characters: <code>/\:*?"<>|#+`</code>;
- does not start or end with whitespace.

These constraints apply to **create**, **register**, and **rename** operations. Existing entities whose names pre-date this validation are not affected by read or update operations.

[Policy](#policy) names are subject to stricter constraints; see below.

## Catalog

A catalog is a top-level entity in Polaris that may contain other entities like [namespaces](#namespace) and [tables](#table). These map directly to [Apache Iceberg catalogs](https://iceberg.apache.org/terms/#catalog).

For information on managing catalogs with the REST API or for more information on what data can be associated with a catalog, see [the CreateCatalogRequest OpenAPI](https://github.com/apache/polaris/blob/main/spec/polaris-management-service.yml).

{{< alert warning >}}
Catalog properties are client-visible configuration values. Polaris returns them to authenticated
catalog clients through the Iceberg REST `/config` response. Use catalog properties only for
non-sensitive client configuration. Do not store passwords, tokens, access keys, or other secrets
in catalog properties.
{{< /alert >}}

### Storage Type

All catalogs in Polaris are associated with a _storage type_. Valid Storage Types are `S3`, `Azure`, and `GCS`. The `FILE` type is also additionally available for testing. Each of these types relates to a different storage provider where data within the catalog may reside. Depending on the storage type, various other configurations may be set for a catalog including credentials to be used when accessing data inside the catalog.

For details on how to use Storage Types in the REST API, see [the StorageConfigInfo OpenAPI](https://github.com/apache/polaris/blob/main/spec/polaris-management-service.yml).

For usage examples of storage types, see [docs]({{% ref "command-line-interface" %}}).

## Namespace

A namespace is a logical entity that resides within a [catalog](#catalog) and can contain other entities such as [tables](#table) or [views](#view). Some other systems may refer to namespaces as _schemas_ or _databases_.

In Polaris, namespaces can be nested. For example, `a.b.c.d.e.f.g` is a valid namespace. `b` is said to reside within `a`, and so on.

For information on managing namespaces with the REST API or for more information on what data can be associated with a namespace, see [the CreateNamespaceRequest OpenAPI](https://github.com/apache/polaris/blob/main/spec/polaris-management-service.yml).

## Table

Polaris tables are entities that map to [Apache Iceberg tables](https://iceberg.apache.org/docs/nightly/configuration/), [Delta tables](https://docs.databricks.com/aws/en/delta/table-properties), or [Hudi tables](https://hudi.apache.org/docs/next/configurations#TABLE_CONFIG).

For information on managing tables with the REST API or for more information on what data can be associated with a table, see [the CreateTableRequest OpenAPI](https://github.com/apache/polaris/blob/main/spec/polaris-management-service.yml).

{{< alert warning >}}
Table properties are readable metadata, not a secret store. Do not store passwords, tokens, access
keys, or other secrets in table properties.
{{< /alert >}}

### Icechunk Tables

The native `ICECHUNK_TABLE` subtype represents a table backed by one Icechunk repository. Its
`location` property identifies the repository root; Icechunk, rather than the Polaris entity
record, manages repository contents, snapshots, branches, and commits. Icechunk tables are created,
loaded, listed, and dropped through the Icechunk Table API, which can also vend storage credentials
for the repository; see [Icechunk Tables]({{% ref "icechunk-table" %}}).

Icechunk tables share the table-like name space of a namespace with Iceberg tables, Iceberg views,
and generic tables, so a name can belong to only one of them. The other APIs treat Icechunk tables
as follows:

- The Iceberg REST API and the generic table API do not list Icechunk tables. Loading, dropping, or
  otherwise addressing an existing table by an Icechunk table's name fails with `404 Not Found`.
- Creating, registering, or renaming an Iceberg table or view to the name of an Icechunk table, or
  creating a generic table with that name, fails with `409 Conflict`.
- A namespace that contains Icechunk tables cannot be dropped (`409 Conflict`) until they are
  dropped.

Icechunk repository locations take part in the location-overlap checks:

- Creating an Icechunk table checks its location against the locations of Iceberg tables, other
  Icechunk tables, and namespaces (and, without `OPTIMIZED_SIBLING_CHECK`, generic tables that have
  a location), unless `ALLOW_TABLE_LOCATION_OVERLAP` is enabled for the catalog.
- Creating an Iceberg table or view or changing its location is checked against the locations of
  Icechunk tables, unless `ALLOW_TABLE_LOCATION_OVERLAP` is enabled for the catalog. Creating a
  namespace or changing its location is checked against them, unless
  `ALLOW_NAMESPACE_LOCATION_OVERLAP` is enabled.
- Without `OPTIMIZED_SIBLING_CHECK`, these checks compare a location only with the entities directly
  under the same parent and with the locations of the parent namespaces, and they do not compare a
  namespace with a table-like entity of the same name under the same parent. With
  `OPTIMIZED_SIBLING_CHECK`, they use a location index that covers the whole catalog.

Privileges are not scoped by subtype. Table privileges granted on a catalog or namespace apply to
the Icechunk tables in it as well as to Iceberg tables, and a table grant can target a single
Icechunk table. Any table privilege can be granted on an Icechunk table, including privileges that
only Iceberg operations use. [Icechunk Tables]({{% ref "icechunk-table#privileges" %}}) lists the
privileges each Icechunk table operation requires.

Icechunk entities use persisted subtype code `5` and NoSQL object type `ic-t`. Upgrade all nodes
sharing a metastore before enabling the Icechunk Table API (`ENABLE_ICECHUNK_TABLES`). Older
binaries do not recognize these identifiers; do not roll back to an older binary against a
metastore containing Icechunk records.

## View

Polaris views are entities that map to [Apache Iceberg views](https://iceberg.apache.org/view-spec/).

For information on managing views with the REST API or for more information on what data can be associated with a view, see [the CreateViewRequest OpenAPI](https://github.com/apache/polaris/blob/main/spec/polaris-management-service.yml).

{{< alert warning >}}
View properties are readable metadata, not a secret store. Do not store passwords, tokens, access
keys, or other secrets in view properties.
{{< /alert >}}

## Principal

Polaris principals are unique identities that can be used to represent users or services. Each principal may have one or more [principal roles](#principal-role) assigned to it for the purpose of accessing catalogs and the entities within them.

For information on managing principals with the REST API or for more information on what data can be associated with a principal, see [the CreatePrincipalRequest OpenAPI](https://github.com/apache/polaris/blob/main/spec/polaris-management-service.yml).

## Principal Role

Polaris principal roles are labels that may be granted to [principals](#principal). Each principal may have one or more principal roles, and the same principal role may be granted to multiple principals. Principal roles may be assigned based on the persona or responsibilities of a given principal, or on how that principal will need to access different entities within Polaris.

For information on managing principal roles with the REST API or for more information on what data can be associated with a principal role, see [the CreatePrincipalRoleRequest OpenAPI](https://github.com/apache/polaris/blob/main/spec/polaris-management-service.yml)

## Catalog Role

Polaris catalog roles are labels that may be granted to [catalogs](#catalog). Each catalog may have one or more catalog roles, and the same catalog role may be granted to multiple catalogs. Catalog roles may be assigned based on the nature of data that will reside in a catalog, or by the groups of users and services that might need to access that data.

Each catalog role may have multiple [privileges](#privilege) granted to it, and each catalog role can be granted to one or more [principal roles](#principal-role). This is the mechanism by which principals are granted access to entities inside a catalog such as namespaces and tables.

## Policy

Polaris policy is a set of rules governing actions on specified resources under predefined conditions. Polaris supports policies for Iceberg table compaction, snapshot expiry, row-level access control, and custom policy definitions.

Policy names may only contain letters (`A–Z`, `a–z`), digits (`0–9`), hyphens (`-`), and underscores (`_`). Names that do not match this pattern are rejected with HTTP 400.

Policy can be applied at catalog level, namespace level, or table level. Policy inheritance can be achieved by attaching one to a higher-level scope, such as namespace or catalog. As a result, tables registered under those entities do not need to be declared individually for the same policy. If a table or a namespace requires a different policy, user can assign a different policy, hence overriding policy of the same type declared at the higher level entities.

## Privilege

Polaris privileges are granted to [catalog roles](#catalog-role) in order to grant principals with a given principal role some degree of access to catalogs with a given catalog role. When a privilege is granted to a catalog role, any principal roles granted that catalog role receive the privilege. In turn, any principals who are granted that principal role receive it.

A privilege can be scoped to any entity inside a catalog, including the catalog itself.

For a list of supported privileges for each privilege class, see [the OpenAPI](https://github.com/apache/polaris/blob/main/spec/polaris-management-service.yml) (TablePrivilege, ViewPrivilege, NamespacePrivilege, CatalogPrivilege).
