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
title: Icechunk Table
type: docs
weight: 436
---

An Icechunk table registers one [Icechunk](https://icechunk.io/) repository in a Polaris namespace.
Polaris then authorizes access to the table with table privileges and can vend short-lived storage
credentials for the repository. With the Icechunk Table API, you can:
- Create an Icechunk table under a namespace
- Load an Icechunk table, optionally with storage credentials for its repository
- Load fresh storage credentials for an Icechunk table
- List all Icechunk tables under a namespace
- Drop an Icechunk table

{{< alert important >}}
The Icechunk Table API is in beta: its endpoints and response fields may change in future releases.
It is disabled by default; see [Enabling Icechunk Tables](#enabling-icechunk-tables).
{{< /alert >}}

## What is an Icechunk Table?

An Icechunk table is an entity that defines the following fields:

- **name** (required): The table name. It must be unique among all table-like entities in the
  namespace; see [Icechunk Table API Vs. Iceberg Table API](#icechunk-table-api-vs-iceberg-table-api).
- **location** (required): The root URI of the Icechunk repository. For example:
  `s3://<my-bucket>/path/to/repo/`
  - Polaris appends a trailing `/` if it is missing.
  - The location must follow the [location rules](#location-rules), and it cannot be changed after
    the table is created.
- **properties** (optional): String properties for the table, passed on creation.
  - They must not contain the key `location`; the repository root goes in the `location` field.
  - Property keys with the reserved `polaris.` prefix are rejected, as they are for Iceberg tables.

Polaris stores only these fields. It does not read, initialize, or modify the repository, and it
does not track the repository's snapshots, branches, or tags: Icechunk manages those inside the
repository. A client typically creates the table with vended credentials and then uses those
credentials to initialize the repository.

## Enabling Icechunk Tables

The Icechunk Table API is controlled by the realm-level feature flag `ENABLE_ICECHUNK_TABLES`,
which defaults to `false`. While the flag is off, every Icechunk table endpoint fails with
`406 Not Acceptable`, and `GET /v1/config` does not advertise the endpoints. To enable the API in
all realms, set:

```properties
polaris.features."ENABLE_ICECHUNK_TABLES"=true
```

To enable it in a single realm only, set
`polaris.features.realm-overrides."<realm>"."ENABLE_ICECHUNK_TABLES"=true` instead.

Turning the flag off again does not remove existing Icechunk tables. While it is off, they cannot be
loaded or dropped through any API, but they still reserve their names and locations, and their
namespaces cannot be dropped. To remove them, enable the flag again and drop them.

{{< alert warning >}}
Polaris persists Icechunk tables with entity subtype code `5` and, in the NoSQL metastore, object
type `ic-t`. Older Polaris versions do not recognize these identifiers. Enable the flag only after
every Polaris node that shares the metastore runs a version that supports Icechunk tables, and do
not roll back to a version without that support once Icechunk tables exist.
{{< /alert >}}

## Icechunk Table API Vs. Iceberg Table API

Icechunk tables share the table-like name space of a namespace with Iceberg tables, Iceberg views,
and generic tables, so a name can belong to only one of them. The following table shows how the
Iceberg Table API and the Icechunk Table API treat each other's tables:

| Operations   | **Iceberg Table API**                                                                                              | **Icechunk Table API**                                                                                                  |
|--------------|--------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| Create Table | Create an Iceberg table. If the name belongs to an Icechunk table, the request fails with `409 Conflict`.         | Create an Icechunk table. If the name belongs to any table-like entity, the request fails with `409 Conflict`.         |
| Load Table   | Load an Iceberg table. Loading an Icechunk table through the Iceberg Table API fails with `404 Not Found`.        | Load an Icechunk table. Loading an Iceberg table through the Icechunk Table API fails with `404 Not Found`.            |
| Drop Table   | Drop an Iceberg table. Dropping an Icechunk table through the Iceberg Table API fails with `404 Not Found`.       | Drop an Icechunk table. Dropping an Iceberg table through the Icechunk Table API fails with `404 Not Found`.           |
| List Table   | List all Iceberg tables                                                                                            | List all Icechunk tables                                                                                                |

The generic table API behaves the same way, and a namespace that contains Icechunk tables cannot be
dropped. See [Icechunk Tables]({{% ref "entities#icechunk-tables" %}}) on the Entities page for
details.

## Location Rules

Polaris validates the `location` of a new Icechunk table like the location of an Iceberg table
that is created with an explicit location:

1. The catalog must allow client-specified table locations (`ALLOW_CLIENT_SPECIFIED_TABLE_LOCATION`,
   catalog property `polaris.config.allow.client-specified.table.location`, default `true`).
   Otherwise, the request fails with `400 Bad Request`, because an Icechunk table always has a
   client-specified location.
2. The location must lie within the allowed locations of the catalog's storage configuration.
3. The location must lie under the namespace location, unless the catalog allows unstructured
   table locations (`ALLOW_UNSTRUCTURED_TABLE_LOCATION`, catalog property
   `polaris.config.allow.unstructured.table.location`, default `false`).
4. Unless the catalog allows overlapping table locations (`ALLOW_TABLE_LOCATION_OVERLAP`, catalog
   property `polaris.config.allow.overlapping.table.location`, default `false`), the location must
   not contain, equal, or lie inside the location of another Iceberg table, Icechunk table, or
   namespace (and, without `OPTIMIZED_SIBLING_CHECK`, a generic table that has a location). The
   table's own namespace and its parent namespaces are the exception: the location may lie inside
   their locations, but it may not equal any of them. A sibling that only shares a string prefix,
   such as `.../repo-other/` next to `.../repo/`, does not overlap. Polaris does not store the
   location of an Iceberg view, so views are not compared.

A location that breaks rule 2, 3, or 4 is rejected with `403 Forbidden`.

Without `OPTIMIZED_SIBLING_CHECK`, the overlap check of rule 4 compares the location only with the
entities directly under the same namespace and with the locations of the parent namespaces. It also
skips a namespace that has the same name as the new table. The JDBC and in-memory metastores allow a
namespace and a table-like entity with the same name under one parent, so without
`OPTIMIZED_SIBLING_CHECK` an Icechunk repository root can equal or contain the location of such a
namespace, or the reverse; the NoSQL metastore rejects such a pair. With `OPTIMIZED_SIBLING_CHECK`,
Polaris checks against a location index that covers these entities in the whole catalog. On the
JDBC metastore, a location with more than 40 `/` characters is checked without the index, as if
`OPTIMIZED_SIBLING_CHECK` were disabled.

Unstructured table locations and custom namespace locations both let a location lie outside its
parent namespace, where only the location index of `OPTIMIZED_SIBLING_CHECK` finds overlaps.
Creating an Icechunk table therefore fails with `500 Internal Server Error` when
`ALLOW_UNSTRUCTURED_TABLE_LOCATION` or `ALLOW_NAMESPACE_CUSTOM_LOCATION` is enabled for the catalog
while both `ALLOW_TABLE_LOCATION_OVERLAP` and `OPTIMIZED_SIBLING_CHECK` are disabled.

The overlap checks of other entities cover Icechunk tables in the same way: creating an Iceberg
table or view or changing its location is checked against the locations of existing Icechunk tables
unless `ALLOW_TABLE_LOCATION_OVERLAP` is enabled, and so is creating a namespace or changing its
location, unless `ALLOW_NAMESPACE_LOCATION_OVERLAP` is enabled. Without `OPTIMIZED_SIBLING_CHECK`,
these checks are limited in the same way.

## Working with Icechunk Tables

The Icechunk Table API has the following endpoints, relative to the catalog API base path
`/api/catalog`:

| Operation        | Endpoint                                                                                     |
|------------------|----------------------------------------------------------------------------------------------|
| List             | `GET /polaris/v1/{prefix}/namespaces/{namespace}/icechunk-tables`                           |
| Create           | `POST /polaris/v1/{prefix}/namespaces/{namespace}/icechunk-tables`                          |
| Load             | `GET /polaris/v1/{prefix}/namespaces/{namespace}/icechunk-tables/{icechunk-table}`          |
| Load credentials | `GET /polaris/v1/{prefix}/namespaces/{namespace}/icechunk-tables/{icechunk-table}/credentials` |
| Drop             | `DELETE /polaris/v1/{prefix}/namespaces/{namespace}/icechunk-tables/{icechunk-table}`       |

`{prefix}` is the catalog name. The levels of a nested namespace are separated by `%1F`, for
example `climate%1Fera5` for the namespace `climate.era5`. When the feature is enabled, the
`endpoints` list of `GET /v1/config?warehouse=<catalog>` contains these five endpoints, written
without the leading slash, for example `GET polaris/v1/{prefix}/namespaces/{namespace}/icechunk-tables`.

The examples below use the catalog `my_catalog` and the namespace `climate.era5`, and pass a
Polaris access token in `$TOKEN`.

### Create an Icechunk Table

The REST API for creating an Icechunk table is
`POST /polaris/v1/{prefix}/namespaces/{namespace}/icechunk-tables`, and the request body looks like
the following:

```json
{
  "name": "<table_name>",
  "location": "<repository_root>",
  "properties": {
    "<property-key>": "<property-value>"
  }
}
```

Here is an example that creates the Icechunk table `temperature` and asks for vended credentials,
so that the client can initialize the repository:

```shell
curl -X POST http://localhost:8181/api/catalog/polaris/v1/my_catalog/namespaces/climate%1Fera5/icechunk-tables \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Polaris-Icechunk-Table-Access-Delegation: vended-credentials" \
  -d '{
    "name": "temperature",
    "location": "s3://my-bucket/repos/climate/era5/temperature",
    "properties": {
      "owner": "climate-team"
    }
  }'
```

The response contains the created table, with the normalized location, and read-write credentials
for the repository root:

```json
{
  "table": {
    "name": "temperature",
    "location": "s3://my-bucket/repos/climate/era5/temperature/",
    "properties": {
      "owner": "climate-team"
    }
  },
  "storage-access-configs": [
    {
      "prefix": "s3://my-bucket/repos/climate/era5/temperature/",
      "config": {
        "s3.access-key-id": "<access-key-id>",
        "s3.secret-access-key": "<secret-access-key>",
        "s3.session-token": "<session-token>",
        "s3.session-token-expires-at-ms": "<expiration-time-in-epoch-milliseconds>",
        "client.region": "us-east-1",
        "client.refresh-credentials-endpoint": "polaris/v1/my_catalog/namespaces/climate%1Fera5/icechunk-tables/temperature/credentials?access-mode=read-write"
      }
    }
  ]
}
```

Without the `Polaris-Icechunk-Table-Access-Delegation` header, Polaris only registers the table, and
`storage-access-configs` is empty. If the name is already taken by any table-like entity, the
request fails with `409 Conflict`.

Create does not replay requests that carry an `Idempotency-Key`: retrying a create that already
succeeded fails with `409 Conflict`. If an earlier attempt may have succeeded, load the table and
compare its location before you continue.

### Load an Icechunk Table

The REST endpoint for loading an Icechunk table is
`GET /polaris/v1/{prefix}/namespaces/{namespace}/icechunk-tables/{icechunk-table}`.

Here is an example to load the table `temperature` together with read-only credentials:

```shell
curl "http://localhost:8181/api/catalog/polaris/v1/my_catalog/namespaces/climate%1Fera5/icechunk-tables/temperature?access-mode=read" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Polaris-Icechunk-Table-Access-Delegation: vended-credentials"
```

The response has the same shape as the create response. Without the
`Polaris-Icechunk-Table-Access-Delegation` header, the response contains only the table, and
`storage-access-configs` is empty:

```json
{
  "table": {
    "name": "temperature",
    "location": "s3://my-bucket/repos/climate/era5/temperature/",
    "properties": {
      "owner": "climate-team"
    }
  },
  "storage-access-configs": []
}
```

### Load Credentials for an Icechunk Table

The REST endpoint for loading only the storage credentials of an Icechunk table is
`GET /polaris/v1/{prefix}/namespaces/{namespace}/icechunk-tables/{icechunk-table}/credentials`.
It takes the same `access-mode` query parameter as load, and clients also call it to refresh
credentials before they expire.

Here is an example that loads read-write credentials for the table `temperature`:

```shell
curl "http://localhost:8181/api/catalog/polaris/v1/my_catalog/namespaces/climate%1Fera5/icechunk-tables/temperature/credentials?access-mode=read-write" \
  -H "Authorization: Bearer $TOKEN"
```

The response contains only the credentials:

```json
{
  "storage-access-configs": [
    {
      "prefix": "s3://my-bucket/repos/climate/era5/temperature/",
      "config": {
        "s3.access-key-id": "<access-key-id>",
        "s3.secret-access-key": "<secret-access-key>",
        "s3.session-token": "<session-token>",
        "s3.session-token-expires-at-ms": "<expiration-time-in-epoch-milliseconds>",
        "client.region": "us-east-1",
        "client.refresh-credentials-endpoint": "polaris/v1/my_catalog/namespaces/climate%1Fera5/icechunk-tables/temperature/credentials?access-mode=read-write"
      }
    }
  ]
}
```

### List Icechunk Tables

The REST endpoint for listing the Icechunk tables under a given namespace is
`GET /polaris/v1/{prefix}/namespaces/{namespace}/icechunk-tables`. It supports the `pageToken` and
`pageSize` query parameters, like the Iceberg list endpoints, and lists only Icechunk tables.

The following curl command lists all Icechunk tables under the namespace `climate.era5`:

```shell
curl http://localhost:8181/api/catalog/polaris/v1/my_catalog/namespaces/climate%1Fera5/icechunk-tables \
  -H "Authorization: Bearer $TOKEN"
```

Example Response:

```json
{
  "identifiers": [
    {
      "namespace": ["climate", "era5"],
      "name": "temperature"
    }
  ],
  "next-page-token": null
}
```

### Drop an Icechunk Table

The drop Icechunk table REST endpoint is
`DELETE /polaris/v1/{prefix}/namespaces/{namespace}/icechunk-tables/{icechunk-table}`. It returns
`204 No Content`.

The following curl call drops the table `temperature`:

```shell
curl -X DELETE http://localhost:8181/api/catalog/polaris/v1/my_catalog/namespaces/climate%1Fera5/icechunk-tables/temperature \
  -H "Authorization: Bearer $TOKEN"
```

Drop removes the table from the catalog only. Polaris does not delete the repository or any of its
objects, and it does not schedule a cleanup task; there is no purge option. After the drop, the
location is free, and a new Icechunk table can be created at it.

### API Reference

For the complete API specification, see the
[Catalog API Spec]({{% ref "polaris-api-specs/polaris-catalog-api" %}}).

## Credential Vending

Polaris vends short-lived storage credentials for the repository root of an Icechunk table. On S3,
S3-compatible storage, GCS, and ADLS with a hierarchical namespace (`hierarchical: true`), the
credentials are scoped to exactly the repository root. Azure Blob endpoints and ADLS without a
hierarchical namespace receive SAS tokens that cover the whole container, so repositories in one
container are not isolated from each other: use ADLS with a hierarchical namespace, or one container
per trust boundary (see [SAS token scoping and HNS ACLs]({{% relref "./configuration/configuring-polaris-for-production/configuring-azure-blob-cloud-storage-specific#sas-token-scoping-and-hns-acls" %}})).
The scope never depends on the table properties. Clients get credentials in two ways:

- Send the header `Polaris-Icechunk-Table-Access-Delegation: vended-credentials` with a create or
  load request. The response then carries the credentials in `storage-access-configs`. Without the
  header, Polaris does not vend credentials, and `storage-access-configs` is empty.
- Call the [credentials endpoint](#load-credentials-for-an-icechunk-table), which returns only the
  credentials.

The header supports only `vended-credentials`. A non-empty value that does not ask for vended
credentials, such as `remote-signing`, is rejected with `400 Bad Request`.

### Access Modes

The `access-mode` query parameter of the load and credentials endpoints selects the storage access
that the credentials grant on the repository root:

| `access-mode`    | Storage access                | Required privilege |
|------------------|-------------------------------|--------------------|
| `read` (default) | Read and list                 | `TABLE_READ_DATA`  |
| `read-write`     | Read, list, write, and delete | `TABLE_WRITE_DATA` |

- Omitting `access-mode`, or sending an empty value, selects `read`. Any other value, including a
  different case such as `READ`, is rejected with `400 Bad Request`.
- Write access is granted only when `read-write` is requested explicitly. Polaris does not upgrade
  `read` to write access when the caller holds `TABLE_WRITE_DATA`, and it does not fall back to
  `read` when a caller without `TABLE_WRITE_DATA` requests `read-write`; that request fails with
  `403 Forbidden`.
- Create with the header always vends `read-write` credentials, so that the client can initialize
  the repository. It requires `TABLE_WRITE_DATA` in addition to `TABLE_CREATE`.
- On a load without the header, `access-mode` grants nothing: the load needs only
  `TABLE_READ_PROPERTIES` and vends no credentials.

On S3 and S3-compatible storage, the session policy of `read` credentials allows `s3:GetObject` and
`s3:GetObjectVersion` on the objects under the repository root, `s3:ListBucket` restricted to the
repository prefix, and `s3:GetBucketLocation` on the bucket. `read-write` credentials also allow
`s3:PutObject` and `s3:DeleteObject` on the objects under the repository root, which includes the
repository's snapshots, refs, and history. Depending on the KMS settings of the storage
configuration, the policy can also allow KMS actions; see
[Recommended Configuration for S3-Compatible Storage](#recommended-configuration-for-s3-compatible-storage).

Before every vend, Polaris validates the repository root again against the catalog's current
allowed locations and the namespace location. If the root is no longer allowed, for example after
the catalog's `allowedLocations` were narrowed, the request fails with `403 Forbidden`; loading the
table without the header still works.

### Vended Configuration Keys

Each entry of `storage-access-configs` has a `prefix`, which is the repository root, and a `config`
map. For S3 and S3-compatible storage, `config` contains the following keys:

| Key                                                              | Value                                                                                                   |
|------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| `s3.access-key-id`, `s3.secret-access-key`, `s3.session-token`   | The temporary credentials.                                                                              |
| `s3.session-token-expires-at-ms`                                 | The time the credentials expire, in milliseconds since the Unix epoch.                                 |
| `s3.endpoint`                                                    | The `endpoint` of the catalog's storage configuration, if one is set. `endpointInternal` is never returned. |
| `s3.path-style-access`                                           | `true`, if the storage configuration enables `pathStyleAccess`.                                         |
| `client.region`                                                  | The `region` of the storage configuration, if one is set.                                               |
| `client.refresh-credentials-endpoint`                            | The credentials endpoint of the table, see [Refreshing Credentials](#refreshing-credentials).           |

For GCS, the keys are `gcs.oauth2.token`, `gcs.oauth2.token-expires-at`, and
`gcs.oauth2.refresh-credentials-endpoint`. For Azure, they are the SAS token keys
(`adls.sas-token.<account-host>` and its other forms), `adls.sas-token-expires-at-ms.<account-host>`,
and `adls.refresh-credentials-endpoint`. The
[Vended Credentials Reference]({{% ref "vended-credentials" %}}) describes every key.

### Refreshing Credentials

The refresh endpoint key of a vended configuration, such as `client.refresh-credentials-endpoint`
for S3, holds the path of the table's credentials endpoint relative to the catalog API base URI
(for example `http://localhost:8181/api/catalog/`), including the granted access mode. For example:
`polaris/v1/my_catalog/namespaces/climate%1Fera5/icechunk-tables/temperature/credentials?access-mode=read`.
Credentials vended on create carry `access-mode=read-write`.

Polaris authorizes every call to the credentials endpoint again. After a privilege is revoked,
further refreshes fail, but credentials that were already vended stay valid until they expire.

Vended credentials are valid for `STORAGE_CREDENTIAL_DURATION_SECONDS` (default `3600`; GCS does
not respect this value). Polaris caches vended credentials for at most
`STORAGE_CREDENTIAL_CACHE_DURATION_SECONDS` (default `1800`, which must be less than
`STORAGE_CREDENTIAL_DURATION_SECONDS`) and for at most half of their remaining lifetime, so a
response can carry credentials that expire sooner than a full duration from now. Schedule refreshes
from the expiration key, such as `s3.session-token-expires-at-ms`, rather than from the time of the
request.

### Using Vended Credentials with icechunk-python

For S3 and S3-compatible storage, icechunk-python takes a vended configuration through the
arguments of `icechunk.s3_storage`:

| `icechunk.s3_storage` argument | Value                                                                                         |
|--------------------------------|-----------------------------------------------------------------------------------------------|
| `bucket`, `prefix`             | The bucket and the path of the configuration's `prefix`, with the path's leading `/` removed. |
| `endpoint_url`                 | `s3.endpoint`. If it starts with `http://`, also set `allow_http=True`.                       |
| `force_path_style`             | `True` if `s3.path-style-access` is `true`.                                                   |
| `region`                       | `client.region`.                                                                              |
| `get_credentials`              | A callable that calls the refresh endpoint and returns `icechunk.S3StaticCredentials`.        |

- Keep the leading `/` out of `prefix`. With it, Icechunk writes object keys that start with `/`,
  and the vended credentials do not cover them.
- Set `expires_after` of the returned `icechunk.S3StaticCredentials` from
  `s3.session-token-expires-at-ms`, as a `datetime` in `timezone.utc`. Icechunk calls
  `get_credentials` again shortly before that time.
- `get_credentials` must be picklable: define it at module level, or as an instance of a
  module-level class, and keep no state between calls.
- Obtain a valid Polaris access token in every call of `get_credentials`, rather than one stored
  when the repository was opened. With internal authentication, tokens expire after one hour by
  default, and a refresh with an expired token fails with `401 Unauthorized`.
- Use the configuration whose `prefix` equals the repository root that you opened, and fail if there
  is none. The refresh endpoint addresses the table by name, so after the table is dropped and
  created again, it vends credentials for the new root.
- Resolve `client.refresh-credentials-endpoint` against the catalog API base URI, including its
  trailing `/`.
- An empty `storage-access-configs`, for example for `FILE` storage, means that Polaris vended
  nothing. Open the repository with your own storage settings.

### When Credentials Cannot Be Vended

- For storage that does not support credential vending, such as `FILE`, and in realms with
  `SKIP_CREDENTIAL_SUBSCOPING_INDIRECTION` enabled, a request with the header succeeds, and
  `storage-access-configs` is empty.
- If the catalog's storage supports credential vending but returns no credentials, for example S3
  storage configured with `stsUnavailable: true`, a request with the header fails with
  `400 Bad Request` (`Credential vending was requested for table <namespace.table>, but no
  credentials are available`). See
  [When delegation cannot be satisfied]({{% ref "vended-credentials#when-delegation-cannot-be-satisfied" %}}).
- Create registers the table before it vends credentials. If vending fails on create, the table
  still exists: fix the cause and call the credentials endpoint, or drop the table.

## Privileges

Icechunk tables use the [table privileges]({{% ref "managing-security/access-control#table-privileges" %}}),
like Iceberg tables. Each operation requires the following privilege:

| Operation                                                     | Required privilege                    |
|---------------------------------------------------------------|---------------------------------------|
| List                                                          | `TABLE_LIST`                          |
| Create                                                        | `TABLE_CREATE`                        |
| Create with the delegation header                             | `TABLE_CREATE` and `TABLE_WRITE_DATA` |
| Load                                                          | `TABLE_READ_PROPERTIES`               |
| Load with the delegation header, or credentials, `read`       | `TABLE_READ_DATA`                     |
| Load with the delegation header, or credentials, `read-write` | `TABLE_WRITE_DATA`                    |
| Drop                                                          | `TABLE_DROP`                          |

List and create are authorized on the namespace, the other operations on the table. As for all
securables, a privilege granted on a parent namespace or on the catalog also applies. Broader
privileges satisfy these requirements as they do for Iceberg tables: for example, `TABLE_WRITE_DATA`
also allows `read` access, `TABLE_FULL_METADATA` and `CATALOG_MANAGE_METADATA` allow list, create,
load, and drop but no credential vending, and `CATALOG_MANAGE_CONTENT` allows every operation. The
default `catalog_admin` role holds `CATALOG_MANAGE_ACCESS` and `CATALOG_MANAGE_METADATA` but no data
privilege, so it cannot obtain credentials directly. `CATALOG_MANAGE_ACCESS` lets it grant
`TABLE_READ_DATA` or `TABLE_WRITE_DATA` to any catalog role, including its own, so treat
`catalog_admin` as able to reach every repository in the catalog.

- `TABLE_WRITE_DATA` on an Icechunk table allows writing and deleting every object under the
  repository root, including the repository's snapshots, refs, and history. Grant it only to
  principals that must write, preferably on individual tables.
- `read` credentials allow reading every object of the repository. Polaris cannot limit access to
  a branch, tag, or snapshot.
- On Azure Blob endpoints and on ADLS without a hierarchical namespace, `read` and `read-write`
  credentials cover the whole container, not only the repository; see
  [Credential Vending](#credential-vending).
- Privileges are not scoped by subtype. Table privileges granted on a catalog or namespace apply to
  the Icechunk tables in it as well as to the Iceberg tables. To grant a privilege on a single
  Icechunk table, use a table grant (`"type": "table"`) with the namespace and the table name.
- External policy decision points, such as OPA, receive the same operations as for Iceberg tables,
  and the request does not indicate that the table is an Icechunk table.

## Recommended Configuration for S3-Compatible Storage

Credential vending requires a store that implements STS `AssumeRole` with inline session policies,
such as Ceph RGW with STS enabled. For such a store, a catalog storage configuration looks like the
following; see [Configuring S3 Storage]({{% relref "./configuration/configuring-polaris-for-production/configuring-aws-s3-cloud-storage-specific#s3-compatible-endpoints" %}})
for all fields:

```json
"storageConfigInfo": {
  "storageType": "S3",
  "allowedLocations": ["s3://my-bucket/repos/"],
  "roleArn": "arn:aws:iam:::role/polaris-icechunk",
  "region": "us-east-1",
  "endpoint": "https://rgw.example.com",
  "stsEndpoint": "https://rgw.example.com",
  "pathStyleAccess": true,
  "kmsUnavailable": true
}
```

- `endpoint` must be reachable by clients, because Polaris returns it to them as `s3.endpoint`. If
  Polaris must use a different address, such as an in-cluster one, set it in `endpointInternal`,
  which Polaris uses itself and never returns to clients.
- `stsEndpoint` is where Polaris calls `AssumeRole`. If it is not set, Polaris uses
  `endpointInternal`, then `endpoint`.
- Set `pathStyleAccess` to `true` for stores that do not support virtual-host-style addressing.
- Set an explicit `region`. Clients receive it as `client.region`.
- `roleArn` is the role that Polaris assumes for every vend. A role ARN without an account ID, such
  as `arn:aws:iam:::role/polaris-icechunk`, is accepted.
- Set `kmsUnavailable` to `true` unless the store uses KMS. Otherwise, when a `region` is set and no
  KMS keys are configured, Polaris adds a statement that allows `kms:Decrypt` and `kms:DescribeKey`
  on all keys to the session policy of `read` credentials.
- Do not set `stsUnavailable` on a catalog whose Icechunk tables need vended credentials: requests
  with the delegation header then fail with `400 Bad Request`.

The following settings also help:

- Enable `OPTIMIZED_SIBLING_CHECK`, so that overlap checks use the metastore's location index for
  the whole catalog. The JDBC and NoSQL metastores support it. Enabling it also requires
  `ALLOW_OPTIMIZED_SIBLING_CHECK`, which acknowledges that the realm's index data is complete;
  otherwise startup fails with a severe production readiness issue. Enable it only in new realms.
- If repositories must follow an existing storage layout, prefer a namespace tree whose default
  locations match that layout. Otherwise, namespaces can be created at the layout's prefixes with
  custom namespace locations (`ALLOW_NAMESPACE_CUSTOM_LOCATION`, catalog property
  `polaris.config.namespace-custom-location.enabled`), a behavior-change flag that may be removed
  in a future release. Read the warning below first.

{{< alert warning >}}
Enable custom namespace locations only together with `OPTIMIZED_SIBLING_CHECK`, and keep
`ALLOW_NAMESPACE_LOCATION_OVERLAP` and `ALLOW_TABLE_LOCATION_OVERLAP` disabled. With custom
namespace locations, any principal with `NAMESPACE_CREATE` or `NAMESPACE_WRITE_PROPERTIES` can place
a namespace at any location. Without `OPTIMIZED_SIBLING_CHECK`, that location is compared only with
the namespace's siblings and its parent namespaces, so it can lie inside the location of another
namespace or table. The principal can then create an Iceberg table there and receive read-write
credentials for data that it holds no privileges on. Polaris refuses to create Icechunk tables in
this configuration: while custom namespace locations are enabled and both
`ALLOW_TABLE_LOCATION_OVERLAP` and `OPTIMIZED_SIBLING_CHECK` are disabled, creating an Icechunk
table fails with `500 Internal Server Error`; see [Location Rules](#location-rules).
{{< /alert >}}

## Current Limitations

There are some known limitations for the Icechunk table support:
1. There is no update, rename, or commit endpoint. The location and the properties are set on
   creation; to change them, drop the table and create it again.
2. Drop removes the table from the catalog only; there is no purge.
3. Polaris does not coordinate commits or track snapshots, branches, or tags. Icechunk coordinates
   them inside the repository.
4. Icechunk tables are supported in internal catalogs only. An authorized Icechunk table operation
   on an external catalog, including a federated one, fails with `400 Bad Request`. The endpoints
   are still advertised in `GET /v1/config` for external catalogs.
5. Policies cannot be attached to Icechunk tables. The policy API does not find them and returns
   `404 Not Found`.
