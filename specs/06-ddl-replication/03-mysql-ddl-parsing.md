# Spec 06.03: ANTLR4 MySQL DDL Parser Architecture

## 1. Executive Summary & Purpose
Specifies the lexical and syntactic analysis of raw MySQL DDL statements using ANTLR4 grammars, producing an intermediate AST for ClickHouse translation.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserService.java`
- **Listener Implementation**: `com.altinity.clickhouse.debezium.embedded.ddl.parser.MySqlDDLParserListenerImpl`
- **Grammars**: `MySqlLexer.g4`, `MySqlParser.g4`

---

## 3. Operational Specification

### 3.1 AST Parse Sequence
1. Raw DDL string from binlog event is fed into `MySqlLexer`.
2. Token stream feeds `MySqlParser` to construct the parse tree.
3. `ParseTreeWalker.DEFAULT.walk(listener, tree)` visits nodes:
   - `enterAlterTable()`
   - `enterAlterByAddColumn()`
   - `enterAlterByModifyColumn()`
   - `enterAlterByChangeColumn()`
   - `enterAlterByDropColumn()`
   - `enterRenameTable()`
4. The listener translates AST clauses into equivalent ClickHouse SQL statements.

---

## 4. Invariants Preserved
- **Grammar Conformity**: Supports all MySQL 8.0 DDL syntax variations, case insensitivity, backtick quoting, and multi-clause statements.

---

## 5. Verification Criteria
- `MySqlDDLParserListenerImplTest` (114 comprehensive unit test cases).
