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
package org.apache.iceberg.spark.extensions;

import static org.apache.iceberg.TableProperties.WRITE_AUDIT_PUBLISH_ENABLED;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.apache.iceberg.ParameterizedTestExtension;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.Iterables;
import org.apache.spark.sql.AnalysisException;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ParameterizedTestExtension.class)
public class TestCherrypickSnapshotProcedure extends ExtensionsTestBase {

  @AfterEach
  public void removeTables() {
    sql("DROP TABLE IF EXISTS %s", tableName);
  }

  @TestTemplate
  public void testCherrypickSnapshotUsingPositionalArgs() {
    sql("CREATE TABLE %s (id bigint NOT NULL, data string) USING iceberg", tableName);
    sql("ALTER TABLE %s SET TBLPROPERTIES ('%s' 'true')", tableName, WRITE_AUDIT_PUBLISH_ENABLED);

    spark.conf().set("spark.wap.id", "1");

    sql("INSERT INTO TABLE %s VALUES (1, 'a')", tableName);

    assertEquals(
        "Should not see rows from staged snapshot",
        ImmutableList.of(),
        sql("SELECT * FROM %s", tableName));

    Table table = validationCatalog.loadTable(tableIdent);
    Snapshot wapSnapshot = Iterables.getOnlyElement(table.snapshots());

    List<Object[]> output =
        sql(
            "CALL %s.system.cherrypick_snapshot('%s', %dL)",
            catalogName, tableIdent, wapSnapshot.snapshotId());

    table.refresh();

    Snapshot currentSnapshot = table.currentSnapshot();

    assertEquals(
        "Procedure output must match",
        ImmutableList.of(row(wapSnapshot.snapshotId(), currentSnapshot.snapshotId())),
        output);

    assertEquals(
        "Cherrypick must be successful",
        ImmutableList.of(row(1L, "a")),
        sql("SELECT * FROM %s", tableName));
  }

  @TestTemplate
  public void testCherrypickSnapshotUsingNamedArgs() {
    sql("CREATE TABLE %s (id bigint NOT NULL, data string) USING iceberg", tableName);
    sql("ALTER TABLE %s SET TBLPROPERTIES ('%s' 'true')", tableName, WRITE_AUDIT_PUBLISH_ENABLED);

    spark.conf().set("spark.wap.id", "1");

    sql("INSERT INTO TABLE %s VALUES (1, 'a')", tableName);

    assertEquals(
        "Should not see rows from staged snapshot",
        ImmutableList.of(),
        sql("SELECT * FROM %s", tableName));

    Table table = validationCatalog.loadTable(tableIdent);
    Snapshot wapSnapshot = Iterables.getOnlyElement(table.snapshots());

    List<Object[]> output =
        sql(
            "CALL %s.system.cherrypick_snapshot(snapshot_id => %dL, table => '%s')",
            catalogName, wapSnapshot.snapshotId(), tableIdent);

    table.refresh();

    Snapshot currentSnapshot = table.currentSnapshot();

    assertEquals(
        "Procedure output must match",
        ImmutableList.of(row(wapSnapshot.snapshotId(), currentSnapshot.snapshotId())),
        output);

    assertEquals(
        "Cherrypick must be successful",
        ImmutableList.of(row(1L, "a")),
        sql("SELECT * FROM %s", tableName));
  }

  @TestTemplate
  public void testCherrypickSnapshotRefreshesRelationCache() {
    sql("CREATE TABLE %s (id bigint NOT NULL, data string) USING iceberg", tableName);
    sql("ALTER TABLE %s SET TBLPROPERTIES ('%s' 'true')", tableName, WRITE_AUDIT_PUBLISH_ENABLED);

    Dataset<Row> query = spark.sql("SELECT * FROM " + tableName + " WHERE id = 1");
    query.createOrReplaceTempView("tmp");

    spark.sql("CACHE TABLE tmp");

    assertEquals("View should not produce rows", ImmutableList.of(), sql("SELECT * FROM tmp"));

    spark.conf().set("spark.wap.id", "1");

    sql("INSERT INTO TABLE %s VALUES (1, 'a')", tableName);

    assertEquals(
        "Should not see rows from staged snapshot",
        ImmutableList.of(),
        sql("SELECT * FROM %s", tableName));

    Table table = validationCatalog.loadTable(tableIdent);
    Snapshot wapSnapshot = Iterables.getOnlyElement(table.snapshots());

    sql(
        "CALL %s.system.cherrypick_snapshot('%s', %dL)",
        catalogName, tableIdent, wapSnapshot.snapshotId());

    assertEquals(
        "Cherrypick snapshot should be visible",
        ImmutableList.of(row(1L, "a")),
        sql("SELECT * FROM tmp"));

    sql("UNCACHE TABLE tmp");
  }

  @TestTemplate
  public void testCherrypickInvalidSnapshot() {
    sql("CREATE TABLE %s (id bigint NOT NULL, data string) USING iceberg", tableName);

    assertThatThrownBy(
            () -> sql("CALL %s.system.cherrypick_snapshot('%s', -1L)", catalogName, tableIdent))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Cannot cherry-pick unknown snapshot ID: -1");
  }

  @TestTemplate
  public void testInvalidCherrypickSnapshotCases() {
    assertThatThrownBy(
            () -> sql("CALL %s.system.cherrypick_snapshot('n', table => 't', 1L)", catalogName))
        .isInstanceOf(AnalysisException.class)
        .hasMessage(
            "[UNEXPECTED_POSITIONAL_ARGUMENT] Cannot invoke routine `cherrypick_snapshot` because it contains positional argument(s) following the named argument assigned to `table`; please rearrange them so the positional arguments come first and then retry the query again. SQLSTATE: 4274K");

    assertThatThrownBy(() -> sql("CALL %s.custom.cherrypick_snapshot('n', 't', 1L)", catalogName))
        .isInstanceOf(AnalysisException.class)
        .hasMessage(
            "[FAILED_TO_LOAD_ROUTINE] Failed to load routine `%s`.`custom`.`cherrypick_snapshot`. SQLSTATE: 38000",
            catalogName);

    assertThatThrownBy(() -> sql("CALL %s.system.cherrypick_snapshot('t')", catalogName))
        .isInstanceOf(AnalysisException.class)
        .hasMessage(
            "[REQUIRED_PARAMETER_NOT_FOUND] Cannot invoke routine `cherrypick_snapshot` because the parameter named `snapshot_id` is required, but the routine call did not supply a value. Please update the routine call to supply an argument value (either positionally at index 0 or by name) and retry the query again. SQLSTATE: 4274K");

    assertThatThrownBy(() -> sql("CALL %s.system.cherrypick_snapshot('', 1L)", catalogName))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Cannot handle an empty identifier for argument table");

    assertThatThrownBy(() -> sql("CALL %s.system.cherrypick_snapshot('t', '2.2')", catalogName))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith(
            "[CAST_INVALID_INPUT] The value '2.2' of the type \"STRING\" cannot be cast to \"BIGINT\" because it is malformed.");
  }
}
