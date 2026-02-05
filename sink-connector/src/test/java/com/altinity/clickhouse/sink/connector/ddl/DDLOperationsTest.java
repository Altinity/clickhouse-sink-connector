package com.altinity.clickhouse.sink.connector.ddl;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseAlterTable;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseDropTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Comprehensive test suite for DDL operations: DROP COLUMN, RENAME COLUMN, 
 * MODIFY COLUMN, DROP TABLE, and RENAME TABLE.
 *
 * Tests Phase 3: Full DDL Support Implementation
 */
public class DDLOperationsTest {

    @Mock
    private Connection mockConnection;

    @Mock
    private Statement mockStatement;

    private ClickHouseAlterTable alterTable;
    private ClickHouseDropTable dropTable;
    private ClickHouseSinkConnectorConfig config;

    @BeforeEach
    public void setUp() throws SQLException {
        MockitoAnnotations.openMocks(this);
        alterTable = new ClickHouseAlterTable();
        dropTable = new ClickHouseDropTable();
        
        when(mockConnection.createStatement()).thenReturn(mockStatement);
    }

    private ClickHouseSinkConnectorConfig createConfig(Map<String, String> props) {
        Map<String, String> defaultProps = new HashMap<>();
        defaultProps.put("clickhouse.server.url", "http://localhost");
        defaultProps.put("clickhouse.server.user", "default");
        defaultProps.put("clickhouse.server.password", "");
        defaultProps.put("clickhouse.server.port", "8123");
        defaultProps.putAll(props);
        return new ClickHouseSinkConnectorConfig(defaultProps);
    }

    // ========== DROP COLUMN Tests ==========

    @Test
    public void testDropColumn_DropBehavior() throws SQLException {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.DROP_COLUMN_BEHAVIOR.toString(), "DROP");
        config = createConfig(props);

        alterTable.dropColumn("users", "middle_name", mockConnection, config);

        verify(mockStatement).execute(contains("DROP COLUMN"));
        verify(mockStatement).execute(contains("middle_name"));
    }

    @Test
    public void testDropColumn_RenameBehavior() throws SQLException {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.DROP_COLUMN_BEHAVIOR.toString(), "RENAME");
        config = createConfig(props);

        alterTable.dropColumn("users", "old_column", mockConnection, config);

        verify(mockStatement).execute(contains("RENAME COLUMN"));
        verify(mockStatement).execute(contains("old_column"));
        verify(mockStatement).execute(contains("_deleted_"));
    }

    @Test
    public void testDropColumn_IgnoreBehavior() throws SQLException {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.DROP_COLUMN_BEHAVIOR.toString(), "IGNORE");
        config = createConfig(props);

        alterTable.dropColumn("users", "ignored_column", mockConnection, config);

        verify(mockStatement, never()).execute(anyString());
    }

    @Test
    public void testDropColumn_FailBehavior() {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.DROP_COLUMN_BEHAVIOR.toString(), "FAIL");
        config = createConfig(props);

        assertThrows(RuntimeException.class, () -> {
            alterTable.dropColumn("users", "fail_column", mockConnection, config);
        });
    }

    @Test
    public void testDropColumn_DefaultBehavior() throws SQLException {
        Map<String, String> props = new HashMap<>();
        // No DROP_COLUMN_BEHAVIOR specified, should default to RENAME
        config = createConfig(props);

        alterTable.dropColumn("users", "default_column", mockConnection, config);

        verify(mockStatement).execute(contains("RENAME COLUMN"));
    }

    // ========== RENAME COLUMN Tests ==========

    @Test
    public void testRenameColumn_Success() throws SQLException {
        config = createConfig(new HashMap<>());

        alterTable.renameColumn("users", "first_name", "given_name", mockConnection, config);

        verify(mockStatement).execute(contains("RENAME COLUMN"));
        verify(mockStatement).execute(contains("first_name"));
        verify(mockStatement).execute(contains("given_name"));
    }

    @Test
    public void testRenameColumn_WithEscaping() throws SQLException {
        config = createConfig(new HashMap<>());

        alterTable.renameColumn("users", "user", "user_name", mockConnection, config);

        // Should use backticks for escaping reserved keywords
        verify(mockStatement).execute(contains("`user`"));
        verify(mockStatement).execute(contains("`user_name`"));
    }

    @Test
    public void testRenameColumn_Failure() throws SQLException {
        config = createConfig(new HashMap<>());
        when(mockStatement.execute(anyString())).thenThrow(new SQLException("Column not found"));

        assertThrows(SQLException.class, () -> {
            alterTable.renameColumn("users", "nonexistent", "new_name", mockConnection, config);
        });
    }

    // ========== MODIFY COLUMN Tests ==========

    @Test
    public void testModifyColumn_SafeTypeChange_Int32ToInt64() throws SQLException {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.TYPE_CHANGE_BEHAVIOR.toString(), "MODIFY");
        props.put(ClickHouseSinkConnectorConfigVariables.TYPE_CHANGE_SAFE_ONLY.toString(), "true");
        config = createConfig(props);

        alterTable.modifyColumn("users", "age", "Int32", "Int64", mockConnection, config);

        verify(mockStatement).execute(contains("MODIFY COLUMN"));
        verify(mockStatement).execute(contains("age"));
        verify(mockStatement).execute(contains("Int64"));
    }

    @Test
    public void testModifyColumn_SafeTypeChange_Float32ToFloat64() throws SQLException {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.TYPE_CHANGE_BEHAVIOR.toString(), "MODIFY");
        props.put(ClickHouseSinkConnectorConfigVariables.TYPE_CHANGE_SAFE_ONLY.toString(), "true");
        config = createConfig(props);

        alterTable.modifyColumn("stats", "value", "Float32", "Float64", mockConnection, config);

        verify(mockStatement).execute(contains("MODIFY COLUMN"));
    }

    @Test
    public void testModifyColumn_UnsafeTypeChange_Rejected() {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.TYPE_CHANGE_BEHAVIOR.toString(), "MODIFY");
        props.put(ClickHouseSinkConnectorConfigVariables.TYPE_CHANGE_SAFE_ONLY.toString(), "true");
        config = createConfig(props);

        // Int64 to Int32 is unsafe (narrowing)
        assertThrows(RuntimeException.class, () -> {
            alterTable.modifyColumn("users", "id", "Int64", "Int32", mockConnection, config);
        });
    }

    @Test
    public void testModifyColumn_IgnoreBehavior() throws SQLException {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.TYPE_CHANGE_BEHAVIOR.toString(), "IGNORE");
        config = createConfig(props);

        alterTable.modifyColumn("users", "age", "Int32", "Int64", mockConnection, config);

        verify(mockStatement, never()).execute(anyString());
    }

    @Test
    public void testModifyColumn_FailBehavior() {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.TYPE_CHANGE_BEHAVIOR.toString(), "FAIL");
        config = createConfig(props);

        assertThrows(RuntimeException.class, () -> {
            alterTable.modifyColumn("users", "age", "Int32", "Int64", mockConnection, config);
        });
    }

    @Test
    public void testModifyColumn_NullableTypeChange() throws SQLException {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.TYPE_CHANGE_BEHAVIOR.toString(), "MODIFY");
        props.put(ClickHouseSinkConnectorConfigVariables.TYPE_CHANGE_SAFE_ONLY.toString(), "true");
        config = createConfig(props);

        // Nullable(Int32) to Nullable(Int64) should be safe
        alterTable.modifyColumn("users", "age", "Nullable(Int32)", "Nullable(Int64)", 
            mockConnection, config);

        verify(mockStatement).execute(contains("MODIFY COLUMN"));
    }

    @Test
    public void testModifyColumn_DateToDateTime() throws SQLException {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.TYPE_CHANGE_BEHAVIOR.toString(), "MODIFY");
        props.put(ClickHouseSinkConnectorConfigVariables.TYPE_CHANGE_SAFE_ONLY.toString(), "true");
        config = createConfig(props);

        // Date to DateTime is safe (adds time component)
        alterTable.modifyColumn("events", "created_at", "Date", "DateTime", 
            mockConnection, config);

        verify(mockStatement).execute(contains("MODIFY COLUMN"));
    }

    // ========== DROP TABLE Tests ==========

    @Test
    public void testDropTable_DropBehavior() throws SQLException {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.DROP_TABLE_BEHAVIOR.toString(), "DROP");
        config = createConfig(props);

        dropTable.dropTable("old_users", "test_db", mockConnection, config);

        verify(mockStatement).execute(contains("DROP TABLE"));
        verify(mockStatement).execute(contains("old_users"));
    }

    @Test
    public void testDropTable_RenameBehavior() throws SQLException {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.DROP_TABLE_BEHAVIOR.toString(), "RENAME");
        config = createConfig(props);

        dropTable.dropTable("archive_table", "test_db", mockConnection, config);

        verify(mockStatement).execute(contains("RENAME TABLE"));
        verify(mockStatement).execute(contains("archive_table"));
        verify(mockStatement).execute(contains("_deleted_"));
    }

    @Test
    public void testDropTable_IgnoreBehavior() throws SQLException {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.DROP_TABLE_BEHAVIOR.toString(), "IGNORE");
        config = createConfig(props);

        dropTable.dropTable("ignored_table", "test_db", mockConnection, config);

        verify(mockStatement, never()).execute(anyString());
    }

    @Test
    public void testDropTable_FailBehavior() {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.DROP_TABLE_BEHAVIOR.toString(), "FAIL");
        config = createConfig(props);

        assertThrows(RuntimeException.class, () -> {
            dropTable.dropTable("protected_table", "test_db", mockConnection, config);
        });
    }

    // ========== RENAME TABLE Tests ==========

    @Test
    public void testRenameTable_Success() throws SQLException {
        config = createConfig(new HashMap<>());

        dropTable.renameTable("test_db.old_name", "new_name", "test_db", 
            mockConnection, config);

        verify(mockStatement).execute(contains("RENAME TABLE"));
        verify(mockStatement).execute(contains("old_name"));
        verify(mockStatement).execute(contains("new_name"));
    }

    @Test
    public void testRenameTable_WithDatabase() throws SQLException {
        config = createConfig(new HashMap<>());

        dropTable.renameTable("db1.table1", "table2", "db1", mockConnection, config);

        verify(mockStatement).execute(contains("RENAME TABLE"));
        verify(mockStatement).execute(contains("db1.table1"));
        verify(mockStatement).execute(contains("db1.table2"));
    }

    // ========== TRUNCATE TABLE Tests ==========

    @Test
    public void testTruncateTable_Success() throws SQLException {
        config = createConfig(new HashMap<>());

        dropTable.truncateTable("temp_table", "test_db", mockConnection, config);

        verify(mockStatement).execute(contains("TRUNCATE TABLE"));
        verify(mockStatement).execute(contains("temp_table"));
    }

    // ========== Integration Tests ==========

    @Test
    public void testMultipleDDLOperations_Sequence() throws SQLException {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.DROP_COLUMN_BEHAVIOR.toString(), "DROP");
        props.put(ClickHouseSinkConnectorConfigVariables.TYPE_CHANGE_BEHAVIOR.toString(), "MODIFY");
        props.put(ClickHouseSinkConnectorConfigVariables.TYPE_CHANGE_SAFE_ONLY.toString(), "true");
        config = createConfig(props);

        // Sequence: MODIFY, RENAME, DROP
        alterTable.modifyColumn("users", "age", "Int32", "Int64", mockConnection, config);
        alterTable.renameColumn("users", "email", "email_address", mockConnection, config);
        alterTable.dropColumn("users", "deprecated_col", mockConnection, config);

        verify(mockStatement, times(3)).execute(anyString());
    }

    @Test
    public void testConfigurationDefaults() throws SQLException {
        // Test that default behaviors work when not specified
        config = createConfig(new HashMap<>());

        alterTable.dropColumn("users", "test_col", mockConnection, config);
        // Should default to RENAME behavior
        verify(mockStatement).execute(contains("RENAME COLUMN"));
    }
}
