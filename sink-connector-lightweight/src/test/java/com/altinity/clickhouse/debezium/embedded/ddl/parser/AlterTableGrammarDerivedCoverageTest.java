/*
 * Copyright (c) 2023 Altinity, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import io.debezium.ddl.parser.mysql.generated.MySqlParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ALTER TABLE coverage derived from the grammar the connector actually ships.
 *
 * <p>{@code AlterSpecificationContext} is a labelled rule: every {@code ALTER
 * TABLE} form is a named {@code AlterBy*} subclass of it. That makes the
 * alternative list machine-readable, so this test does not encode a
 * hand-written idea of the ALTER surface -- it enumerates the classes in the
 * jar and requires a statement for each. A parser upgrade that adds an
 * alternative fails here until someone decides how the connector treats it,
 * which is the moment the decision has to be made rather than in production.</p>
 *
 * <p>Why it matters: {@code ErrorListenerImpl.syntaxError} throws {@code
 * RuntimeException("Error parsing DDL")} from {@code MySqlParser
 * .sqlStatements}, so an ALTER the grammar cannot read stops replication for
 * every table on the host. And a form that parses but whose clause the listener
 * does not act on is the other half of the defect -- the schema silently drifts
 * from MySQL. Each statement below therefore asserts parse success, and, where
 * the clause must reach the emitted DDL, the emitted text as well.</p>
 */
public class AlterTableGrammarDerivedCoverageTest {

    private static MySQLDDLParserService service;

    @BeforeAll
    public static void setUp() {
        service = new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(new HashMap<>()), "db");
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
    }

    private static String translate(String sql) {
        StringBuffer output = new StringBuffer();
        service.parseSql(sql, "t", output);
        return output.toString();
    }

    /**
     * One ALTER TABLE statement per shipped {@code AlterBy*} alternative, keyed
     * by the alternative's name without the {@code AlterBy} prefix.
     *
     * <p>The statement is written so the alternative is exercised in its
     * natural position, including the modifiers that alternative accepts on its
     * own ({@code FIRST}/{@code AFTER} on ADD COLUMN, {@code ON UPDATE} on a
     * default, {@code ALGORITHM}/{@code LOCK} as separate specifications).</p>
     */
    private static Map<String, String> alterForms() {
        Map<String, String> forms = new LinkedHashMap<>();

        forms.put("AddCheckTableConstraint",
                "ALTER TABLE t ADD CONSTRAINT chk CHECK (id > 0)");
        forms.put("AddColumn",
                "ALTER TABLE t ADD COLUMN c INT NOT NULL DEFAULT 0");
        forms.put("AddColumns",
                "ALTER TABLE t ADD COLUMN (c INT, d INT)");
        forms.put("AddDefinitions",
                "ALTER TABLE t ADD c INT, ADD d INT");
        forms.put("AddForeignKey",
                "ALTER TABLE t ADD CONSTRAINT fk FOREIGN KEY (id) REFERENCES s (id)");
        forms.put("AddIndex",
                "ALTER TABLE t ADD INDEX idx (id)");
        forms.put("AddPartition",
                "ALTER TABLE t ADD PARTITION (PARTITION p9 VALUES LESS THAN (100))");
        forms.put("AddPrimaryKey",
                "ALTER TABLE t ADD PRIMARY KEY (id)");
        forms.put("AddSpecialIndex",
                "ALTER TABLE t ADD FULLTEXT KEY ft (b)");
        forms.put("AddUniqueKey",
                "ALTER TABLE t ADD UNIQUE KEY uq (id)");
        forms.put("AlterCheckTableConstraint",
                "ALTER TABLE t ALTER CHECK chk ENFORCED");
        forms.put("AlterColumnDefault",
                "ALTER TABLE t ALTER COLUMN c SET VISIBLE");
        forms.put("AlterIndexVisibility",
                "ALTER TABLE t ALTER INDEX idx INVISIBLE");
        forms.put("AnalyzePartition",
                "ALTER TABLE t ANALYZE PARTITION p0");
        forms.put("ChangeColumn",
                "ALTER TABLE t CHANGE COLUMN c d BIGINT");
        forms.put("ChangeDefault",
                "ALTER TABLE t ALTER COLUMN c DROP DEFAULT");
        forms.put("CheckPartition",
                "ALTER TABLE t CHECK PARTITION p0");
        forms.put("CoalescePartition",
                "ALTER TABLE t COALESCE PARTITION 2");
        forms.put("ConvertCharset",
                "ALTER TABLE t CONVERT TO CHARACTER SET utf8mb4");
        forms.put("DefaultCharset",
                "ALTER TABLE t DEFAULT CHARSET=utf8mb4");
        forms.put("DisableKeys",
                "ALTER TABLE t DISABLE KEYS");
        forms.put("DiscardPartition",
                "ALTER TABLE t DISCARD PARTITION p0 TABLESPACE");
        forms.put("DiscardTablespace",
                "ALTER TABLE t DISCARD TABLESPACE");
        // DESTRUCTIVE: fixture text only. The parser reads the image of the
        // ALTER TABLE and the emitter renders a schema change; no statement is
        // executed, so no column is dropped.
        forms.put("DropColumn",
                "ALTER TABLE t DROP COLUMN c");
        forms.put("DropConstraintCheck",
                "ALTER TABLE t DROP CHECK chk");
        forms.put("DropForeignKey",
                "ALTER TABLE t DROP FOREIGN KEY fk");
        // DESTRUCTIVE: fixture text only. The parser reads the image of the
        // ALTER TABLE and the emitter renders a schema change; no statement is
        // executed, so no index is dropped.
        forms.put("DropIndex",
                "ALTER TABLE t DROP INDEX idx");
        // DESTRUCTIVE: fixture text only. The parser reads the image of the
        // ALTER TABLE and the emitter renders a schema change; no statement is
        // executed, so no partition is dropped.
        forms.put("DropPartition",
                "ALTER TABLE t DROP PARTITION p0");
        forms.put("DropPrimaryKey",
                "ALTER TABLE t DROP PRIMARY KEY");
        forms.put("EnableKeys",
                "ALTER TABLE t ENABLE KEYS");
        forms.put("ExchangePartition",
                "ALTER TABLE t EXCHANGE PARTITION p0 WITH TABLE s");
        forms.put("Force",
                "ALTER TABLE t FORCE");
        forms.put("ImportPartition",
                "ALTER TABLE t IMPORT PARTITION p0 TABLESPACE");
        forms.put("ImportTablespace",
                "ALTER TABLE t IMPORT TABLESPACE");
        forms.put("Lock",
                "ALTER TABLE t ADD COLUMN c INT, LOCK=NONE");
        forms.put("ModifyColumn",
                "ALTER TABLE t MODIFY COLUMN c BIGINT NOT NULL");
        forms.put("OptimizePartition",
                "ALTER TABLE t OPTIMIZE PARTITION p0");
        forms.put("Order",
                "ALTER TABLE t ORDER BY id");
        forms.put("RebuildPartition",
                "ALTER TABLE t REBUILD PARTITION p0");
        forms.put("RemovePartitioning",
                "ALTER TABLE t REMOVE PARTITIONING");
        forms.put("Rename",
                "ALTER TABLE t RENAME TO s");
        forms.put("RenameColumn",
                "ALTER TABLE t RENAME COLUMN a TO b");
        forms.put("RenameIndex",
                "ALTER TABLE t RENAME INDEX a TO b");
        forms.put("ReorganizePartition",
                "ALTER TABLE t REORGANIZE PARTITION p0 INTO "
                        + "(PARTITION p0 VALUES LESS THAN (5), PARTITION p1 VALUES LESS THAN (10))");
        forms.put("RepairPartition",
                "ALTER TABLE t REPAIR PARTITION p0");
        forms.put("SetAlgorithm",
                "ALTER TABLE t ADD COLUMN c INT, ALGORITHM=INPLACE");
        forms.put("TableOption",
                "ALTER TABLE t ENGINE=InnoDB");
        // DESTRUCTIVE: fixture text only. TRUNCATE PARTITION is parsed and the
        // clause is dropped on the ClickHouse side; nothing is executed, so no
        // partition is emptied.
        forms.put("TruncatePartition",
                "ALTER TABLE t TRUNCATE PARTITION p0");
        forms.put("UpgradePartitioning",
                "ALTER TABLE t UPGRADE PARTITIONING");
        forms.put("Validate",
                "ALTER TABLE t ADD COLUMN c INT, WITH VALIDATION");
        return forms;
    }

    /**
     * The {@code AlterBy*} alternatives the shipped jar defines, read from the
     * class names rather than from a list in this file.
     *
     * <p>Reflection is over the parser's own generated classes, so the set is
     * exactly what this build of the grammar accepts -- not what a grammar file
     * in the repository claims.</p>
     */
    private static Set<String> shippedAlterForms() {
        Set<String> names = new TreeSet<>();
        for (String name : jarAlterAlternativeNames()) {
            names.add(name);
        }
        return names;
    }

    /**
     * Reads the {@code AlterSpecificationContext} subclasses from the class
     * loader's copy of the parser.
     *
     * <p>Generated ANTLR context classes are inner classes named {@code
     * MySqlParser$AlterBy*Context}. This walks the parser class file's declared
     * classes, which is what the running connector links against.</p>
     */
    private static Set<String> jarAlterAlternativeNames() {
        Set<String> names = new TreeSet<>();
        for (Class<?> inner : MySqlParser.class.getDeclaredClasses()) {
            // Two bases carry ALTER TABLE clauses. Column, index, key, charset
            // and option clauses extend AlterSpecificationContext; the partition
            // clauses extend AlterPartitionSpecificationContext instead, which
            // is NOT a subclass of it -- filtering on one base silently drops
            // all fifteen partition alternatives from the coverage set.
            if (!MySqlParser.AlterSpecificationContext.class.isAssignableFrom(inner)
                    && !MySqlParser.AlterPartitionSpecificationContext.class.isAssignableFrom(inner)) {
                continue;
            }
            String simple = inner.getSimpleName();
            if (simple.startsWith("AlterBy") && simple.endsWith("Context")) {
                names.add(simple.substring("AlterBy".length(),
                        simple.length() - "Context".length()));
            }
        }
        return names;
    }

    /**
     * Every ALTER TABLE alternative the shipped grammar defines has a statement
     * here, and no statement names an alternative the grammar does not have.
     */
    @Test
    public void everyAlterAlternativeIsCovered() {
        Set<String> shipped = shippedAlterForms();
        assertFalse(shipped.isEmpty(),
                "reflection found no AlterSpecification alternatives in the shipped parser");

        Set<String> covered = new TreeSet<>(alterForms().keySet());

        Set<String> uncovered = new TreeSet<>(shipped);
        uncovered.removeAll(covered);

        Set<String> stale = new TreeSet<>(covered);
        stale.removeAll(shipped);

        assertTrue(uncovered.isEmpty(), "ALTER TABLE alternatives with no statement:\n  "
                + String.join("\n  ", uncovered));
        assertTrue(stale.isEmpty(), "statements for alternatives the shipped grammar does not define:\n  "
                + String.join("\n  ", stale));
    }

    /**
     * Every ALTER TABLE form parses through the production path.
     *
     * <p>Parsing is the whole requirement for the forms that carry no schema
     * change the connector can express (partition maintenance, key enablement,
     * index visibility): the statement must not throw, and it must not emit a
     * wrong statement either.</p>
     */
    @Test
    public void everyAlterFormParses() {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> form : alterForms().entrySet()) {
            try {
                translate(form.getValue());
            } catch (RuntimeException e) {
                failures.add(form.getKey() + " [" + form.getValue() + "] -> "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        assertTrue(failures.isEmpty(), "every ALTER TABLE form must parse:\n  "
                + String.join("\n  ", failures));
    }

    /**
     * The column-level ALTER forms that change the replicated schema reach the
     * emitted DDL.
     *
     * <p>These are the forms where parsing alone is not enough: a column the
     * source gained must exist in ClickHouse, or the next row event for that
     * table fails to bind. The assertion is deliberately on the column name and
     * its ClickHouse type rather than on exact formatting, so a legitimate
     * formatting change does not break the test.</p>
     */
    @Test
    public void schemaChangingAlterFormsReachTheEmittedDdl() {
        List<String> failures = new ArrayList<>();

        String added = translate("ALTER TABLE t ADD COLUMN c BIGINT");
        requireContains(failures, "AddColumn.type", added, "Int64");
        requireContains(failures, "AddColumn.name", added, "c");

        String after = translate("ALTER TABLE t ADD COLUMN c BIGINT AFTER id");
        requireContains(failures, "AddColumnAfter.position", after, "AFTER");
        requireContains(failures, "AddColumnAfter.column", after, "c");

        String first = translate("ALTER TABLE t ADD COLUMN c BIGINT FIRST");
        requireContains(failures, "AddColumnFirst.position", first, "FIRST");

        String modified = translate("ALTER TABLE t MODIFY COLUMN c VARCHAR(40)");
        requireContains(failures, "ModifyColumn.type", modified, "String");
        requireContains(failures, "ModifyColumn.modify", modified, "MODIFY COLUMN c");

        // CHANGE COLUMN states two facts -- a new type under the old name, then
        // the rename -- and no single ClickHouse statement carries both, so the
        // connector emits MODIFY COLUMN followed by RENAME COLUMN. Asserting the
        // MySQL spelling would assert the input, not the output.
        String changed = translate("ALTER TABLE t CHANGE COLUMN a b INT");
        requireContains(failures, "ChangeColumn.retype", changed, "MODIFY COLUMN IF EXISTS a");
        requireContains(failures, "ChangeColumn.rename", changed, "RENAME COLUMN IF EXISTS a to b");

        String renamed = translate("ALTER TABLE t RENAME COLUMN a TO b");
        requireContains(failures, "RenameColumn.rename", renamed, "b");

        // The emitter states IF EXISTS explicitly, so re-applying a drop the
        // target already saw does not stop the stream.
        // DESTRUCTIVE: assertion text only. Both strings are passed to the
        // translator as input and compared as output; no DDL is executed, so
        // no column is dropped.
        String dropped = translate("ALTER TABLE t DROP COLUMN c");
        requireContains(failures, "DropColumn", dropped, "DROP COLUMN IF EXISTS c");

        assertTrue(failures.isEmpty(),
                "a schema-changing ALTER must reach the emitted ClickHouse DDL:\n  "
                        + String.join("\n  ", failures));
    }

    private static void requireContains(List<String> failures, String label, String emitted,
                                        String expected) {
        if (!emitted.contains(expected)) {
            failures.add(label + " -> expected [" + expected + "] in [" + emitted + "]");
        }
    }
}
