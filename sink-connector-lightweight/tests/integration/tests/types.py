from integration.tests.steps.mysql import *
from integration.tests.steps.datatypes import *
from integration.tests.steps.service_settings import *


@TestOutline
def check_datatype_replication(
    self,
    mysql_type,
    ch_type,
    values,
    ch_values,
    clickhouse_table_engine,
    nullable=False,
    hex_type=False,
):
    """Check replication of a given MySQL data type."""
    table_name = f"types_{getuid()}"

    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    mysql_columns = f"MyData {mysql_type}{' NOT NULL' if not nullable else ''}"
    clickhouse_columns = (
        f"{f'MyData Nullable({ch_type})' if nullable else f'MyData {ch_type}'}"
    )

    with Given(f"I create MySQL to CH replicated table", description=table_name):
        create_mysql_table(
            table_name=table_name,
            columns=mysql_columns,
        )

    with When(f"I insert data in MySQL table {table_name}"):
        for i, value in enumerate(values, 1):
            mysql.query(f"INSERT INTO {table_name} VALUES ({i}, {value})")
            with Then(f"I make check that ClickHouse table has same dataset"):
                retry(clickhouse.query, timeout=50, delay=1)(
                    f"SELECT id,{'unhex(MyData)' if hex_type else 'MyData'} FROM test.{table_name} FINAL FORMAT CSV",
                    message=f"{ch_values[i - 1]}",
                )


@TestStep(When)
def check_clickhouse_type(
    self,
    mysql_type,
    ch_type,
    sample_value,
    clickhouse_table_engine,
    nullable=False,
):
    """The ClickHouse column the connector creates carries the mapped type.

    Used where the VALUE has no stable textual rendering to compare -- a
    geometry is MySQL's own binary encoding, not text -- so the row asserts
    the type mapping instead, read back from ``system.columns`` rather than
    assumed from the connector source.
    """
    table_name = f"types_{getuid()}"
    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    mysql_columns = f"MyData {mysql_type}{' NOT NULL' if not nullable else ''}"
    with Given("I create MySQL to CH replicated table", description=table_name):
        create_mysql_table(table_name=table_name, columns=mysql_columns)

    with When("I insert one sample row in MySQL"):
        mysql.query(f"INSERT INTO {table_name} VALUES (1, {sample_value})")

    with Then("I check the ClickHouse column type"):
        for attempt in retries(timeout=120, delay=5):
            with attempt:
                actual = clickhouse.query(
                    "SELECT type FROM system.columns WHERE database='test' "
                    f"AND table='{table_name}' AND name='MyData' FORMAT TSVRaw"
                ).output.strip()
                assert actual == ch_type, (
                    f"{mysql_type} produced ClickHouse type {actual!r}, "
                    f"expected {ch_type!r}"
                )


@TestOutline(Feature)
@Examples(
    "mysql_type ch_type values ch_values nullable",
    [
        ("DECIMAL(2,1)", "DECIMAL(2,1)", ["2/3"], ["0.7"], False),
        ("DECIMAL(30, 10)", "DECIMAL(30, 10)", ["1.232323233"], ["1.232323233"], False),
        ("DECIMAL(9, 5)", "DECIMAL32(5)", ["1.23"], ["1.23"], False),
        ("DECIMAL(18, 5)", "DECIMAL64(5)", ["1.23"], ["1.23"], False),
        ("DECIMAL(38, 5)", "DECIMAL128(5)", ["1.23"], ["1.23"], False),
        ("DECIMAL(65, 5)", "DECIMAL256(5)", ["1.23"], ["1.23"], False),
        ("DECIMAL(2,1)", "DECIMAL(2,1)", ["NULL"], ["\\N"], True),
        ("DECIMAL(30, 10)", "DECIMAL(30, 10)", ["NULL"], ["\\N"], True),
        ("DECIMAL(9, 5)", "DECIMAL32(5)", ["NULL"], ["\\N"], True),
        ("DECIMAL(18, 5)", "DECIMAL64(5)", ["NULL"], ["\\N"], True),
        ("DECIMAL(38, 5)", "DECIMAL128(5)", ["NULL"], ["\\N"], True),
        ("DECIMAL(65, 5)", "DECIMAL256(5)", ["NULL"], ["\\N"], True),
    ],
)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Decimal("1.0")
)
def decimal(self, mysql_type, ch_type, values, ch_values, nullable):
    """Check replication of MySQL 'DECIMAL' data types."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            check_datatype_replication(
                mysql_type=mysql_type,
                ch_type=ch_type,
                values=values,
                ch_values=ch_values,
                nullable=nullable,
                clickhouse_table_engine=clickhouse_table_engine,
            )


@TestOutline(Feature)
@Examples(
    "mysql_type ch_type values ch_values  nullable",
    [
        ("DATE", "Date32", ["'2012-12-12'"], ['"2012-12-12"'], False),
        ("TIME", "String", ["'17:51:04.777'"], ['"17:51:05.000000"'], False),
        ("TIME(6)", "String", ["'17:51:04.777'"], ['"17:51:04.777000"'], False),
        ("DATE", "Date32", ["NULL"], ["\\N"], True),
        ("TIME", "String", ["NULL"], ["\\N"], True),
        ("TIME(6)", "String", ["NULL"], ["\\N"], True),
    ],
)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_DateTime("1.0")
)
def date_time(self, mysql_type, ch_type, values, ch_values, nullable):
    """Check replication of MySQL 'DATE' and 'TIME' data type."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            check_datatype_replication(
                mysql_type=mysql_type,
                ch_type=ch_type,
                values=values,
                ch_values=ch_values,
                nullable=nullable,
                clickhouse_table_engine=clickhouse_table_engine,
            )


@TestOutline(Feature)
# @Repeat(3)
@Examples(
    "mysql_type ch_type values ch_values nullable",
    [
        (
            "INT",
            "Int32",
            ["-2147483648", "0", "2147483647"],
            ["-2147483648", "0", "2147483647"],
            False,
        ),
        ("INT UNSIGNED", "UInt32", ["0", "4294967295"], ["0", "4294967295"], False),
        (
            "BIGINT",
            "Int64",
            ["-9223372036854775808", "0", "9223372036854775807"],
            ["-9223372036854775808", "0", "9223372036854775807"],
            False,
        ),
        ("TINYINT", "Int8", ["-128", "127"], ["-128", "127"], False),
        ("TINYINT UNSIGNED", "UInt8", ["0", "255"], ["0", "255"], False),
        (
            "SMALLINT",
            "Int16",
            ["-32768", "0", "32767"],
            ["-32768", "0", "32767"],
            False,
        ),
        ("SMALLINT UNSIGNED", "UInt16", ["0", "65535"], ["0", "65535"], False),
        (
            "MEDIUMINT",
            "Int32",
            ["-8388608", "0", "8388607"],
            ["-8388608", "0", "8388607"],
            False,
        ),
        ("MEDIUMINT UNSIGNED", "UInt32", ["0", "16777215"], ["0", "16777215"], False),
        ("INT", "Int32", ["NULL"], ["\\N"], True),
        ("INT UNSIGNED", "UInt32", ["NULL"], ["\\N"], True),
        ("BIGINT", "Int64", ["NULL"], ["\\N"], True),
        ("BIGINT UNSIGNED", "UInt64", ["NULL"], ["\\N"], True),
        ("TINYINT", "Int8", ["NULL"], ["\\N"], True),
        ("TINYINT UNSIGNED", "UInt8", ["NULL"], ["\\N"], True),
        ("SMALLINT", "Int16", ["NULL"], ["\\N"], True),
        ("SMALLINT UNSIGNED", "UInt16", ["NULL"], ["\\N"], True),
        ("MEDIUMINT", "Int32", ["NULL"], ["\\N"], True),
        ("MEDIUMINT UNSIGNED", "UInt32", ["NULL"], ["\\N"], True),
    ],
)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_IntegerTypes("1.0")
)
def integer_types(self, mysql_type, ch_type, values, ch_values, nullable):
    """Check replication of MySQL 'INT' data types."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            check_datatype_replication(
                mysql_type=mysql_type,
                ch_type=ch_type,
                values=values,
                ch_values=ch_values,
                nullable=nullable,
                clickhouse_table_engine=clickhouse_table_engine,
            )


@TestOutline(Feature)
@Examples(
    "mysql_type ch_type values ch_values nullable",
    [
        ("CHAR", "LowCardinality(String)", ["'x'"], ['"x"'], False),
        ("TEXT", "String", ["'some_text'"], ['"some_text"'], False),
        ("VARCHAR(4)", "String", ["'IVAN'"], ['"IVAN"'], False),
        ("CHAR", "String", ["NULL"], ["\\N"], True),
        ("TEXT", "String", ["NULL"], ["\\N"], True),
        ("VARCHAR(4)", "String", ["NULL"], ["\\N"], True),
    ],
)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_String("1.0")
)
def string(self, mysql_type, ch_type, values, ch_values, nullable):
    """Check replication of MySQL 'STRING' data types."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            check_datatype_replication(
                mysql_type=mysql_type,
                ch_type=ch_type,
                values=values,
                ch_values=ch_values,
                nullable=nullable,
                clickhouse_table_engine=clickhouse_table_engine,
            )


@TestOutline(Feature)
@Examples(
    "mysql_type ch_type values ch_values nullable",
    [
        ("BLOB", "String", ["'some_blob'"], ['"some_blob"'], False),
        ("MEDIUMBLOB", "String", ["'x_Mediumblob'"], ['"x_Mediumblob"'], False),
        (
            "LONGBLOB",
            "String",
            ["'some_Longblobblobblob'"],
            ['"some_Longblobblobblob"'],
            False,
        ),
        ("BLOB", "String", ["NULL"], ["\\N"], True),
        ("MEDIUMBLOB", "String", ["NULL"], ["\\N"], True),
        ("LONGBLOB", "String", ["NULL"], ["\\N"], True),
    ],
)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_BlobTypes("1.0")
)
def blob(self, mysql_type, ch_type, values, ch_values, nullable):
    """Check replication of MySQL 'BLOB' data types."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            check_datatype_replication(
                mysql_type=mysql_type,
                ch_type=ch_type,
                values=values,
                ch_values=ch_values,
                nullable=nullable,
                clickhouse_table_engine=clickhouse_table_engine,
                hex_type=True,
            )


@TestOutline(Feature)
@Examples(
    "mysql_type ch_type values ch_values nullable",
    [
        ("BINARY", "String", ["'a'"], ['"a"'], False),
        ("VARBINARY(4)", "String", ["'IVAN'"], ['"IVAN"'], False),
        ("BINARY", "String", ["NULL"], ["\\N"], True),
        ("VARBINARY(4)", "String", ["NULL"], ["\\N"], True),
    ],
)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Binary("1.0")
)
def binary(self, mysql_type, ch_type, values, ch_values, nullable):
    """Check replication of MySQL 'BINARY' data types."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            check_datatype_replication(
                mysql_type=mysql_type,
                ch_type=ch_type,
                values=values,
                ch_values=ch_values,
                nullable=nullable,
                clickhouse_table_engine=clickhouse_table_engine,
                hex_type=True,
            )


@TestOutline(Feature)
@Examples(
    "mysql_type ch_type values ch_values nullable",
    [
        ("ENUM('hello','world')", "String", ["'hello'"], ['"hello"'], False),
        ("ENUM('hello','world')", "String", ["NULL"], ["\\N"], True),
    ],
)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_EnumToString("1.0")
)
def enum(self, mysql_type, ch_type, values, ch_values, nullable):
    """Check replication of MySQL 'ENUM' data type."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            check_datatype_replication(
                mysql_type=mysql_type,
                ch_type=ch_type,
                values=values,
                ch_values=ch_values,
                nullable=nullable,
                clickhouse_table_engine=clickhouse_table_engine,
            )


@TestOutline(Feature)
@Examples(
    "mysql_type ch_type values ch_values nullable",
    [
        # BIT is a bit-string, not an integer: BIT(1) is the canonical
        # single-bit column and maps to Bool, while a wider BIT(n) holds a
        # bit string and maps to String. TINYINT(1) is NOT BOOL in the MySQL
        # grammar -- it is a one-byte integer with a display width -- so it
        # stays Int8 and appears in the integer matrix, not here.
        ("BIT(1)", "Bool", ["b'1'"], ["1"], False),
        ("BIT", "Bool", ["b'0'"], ["0"], False),
        ("BIT(1)", "Bool", ["NULL"], ["\\N"], True),
        # BOOL / BOOLEAN are both declared as TINYINT(1) by MySQL and mapped
        # to Bool by the connector, unlike TINYINT(1) itself.
        ("BOOL", "Bool", ["TRUE"], ["1"], False),
        ("BOOLEAN", "Bool", ["FALSE"], ["0"], False),
        ("BOOL", "Bool", ["NULL"], ["\\N"], True),
    ],
)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Bytes("1.0")
)
def bit_and_boolean(self, mysql_type, ch_type, values, ch_values, nullable):
    """Check replication of MySQL 'BIT' and 'BOOL' data types."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            check_datatype_replication(
                mysql_type=mysql_type,
                ch_type=ch_type,
                values=values,
                ch_values=ch_values,
                nullable=nullable,
                clickhouse_table_engine=clickhouse_table_engine,
            )


@TestOutline(Feature)
@Examples(
    "mysql_type ch_type sample_value nullable",
    [
        # A BIT(m) column for m > 1 is a bit STRING, not a number: the
        # requirement (12.14.1) maps BIT(m), m = 2..64, to String. Its
        # on-the-wire form is a raw byte string, so the row asserts the
        # declared type rather than comparing a rendering.
        ("BIT(8)", "Nullable(String)", "b'01000001'", False),
        ("BIT(64)", "Nullable(String)", "b'1111111111111111111111111111111111111111111111111111111111111111'", False),
        ("BIT(2)", "Nullable(String)", "b'01'", False),
    ],
)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Bytes("1.0")
)
def bit_string_types(self, mysql_type, ch_type, sample_value, nullable):
    """Check the ClickHouse type MySQL 'BIT(m)' for m > 1 replicates into."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            check_clickhouse_type(
                mysql_type=mysql_type,
                ch_type=ch_type,
                sample_value=sample_value,
                clickhouse_table_engine=clickhouse_table_engine,
                nullable=nullable,
            )


@TestOutline(Feature)
@Examples(
    "mysql_type ch_type values ch_values nullable",
    [
        # YEAR is a year, not a date: MySQL stores it as a one-byte offset
        # from 1900 and the connector widens it to Int32 so that 0 and the
        # 1901..2155 range both survive.
        ("YEAR", "Int32", ["2024"], ["2024"], False),
        ("YEAR(4)", "Int32", ["1901"], ["1901"], False),
        ("YEAR", "Int32", ["NULL"], ["\\N"], True),
    ],
)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Year("1.0")
)
def year(self, mysql_type, ch_type, values, ch_values, nullable):
    """Check replication of MySQL 'YEAR' data type."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            check_datatype_replication(
                mysql_type=mysql_type,
                ch_type=ch_type,
                values=values,
                ch_values=ch_values,
                nullable=nullable,
                clickhouse_table_engine=clickhouse_table_engine,
            )


@TestOutline(Feature)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_IntegerTypes("1.0")
)
def serial(self):
    """Check replication of the MySQL 'SERIAL' pseudo-type.

    ``SERIAL`` expands to ``BIGINT UNSIGNED NOT NULL AUTO_INCREMENT UNIQUE``,
    so the column is its own key and MySQL rejects the table shape the other
    outlines use: a second AUTO_INCREMENT column beside an unrelated ``id``
    key is error 1075 ("there can be only one auto column and it must be
    defined as a key"). The table is therefore keyed on the SERIAL column
    itself, which is exactly the shape MySQL permits.

    No value is written: an AUTO_INCREMENT column refuses an explicit value
    in this configuration, and the property under test is the ClickHouse TYPE
    the connector derives from the declaration, read back from
    ``system.columns``. The mapped type is UInt64 -- the signed-looking
    ``Types.BIGINT`` registration is normalised through the same unsigned
    path the record side uses.
    """
    table_name = f"serial_{getuid()}"
    clickhouse = self.context.cluster.node("clickhouse")

    mysql_columns = "MyData SERIAL"
    with Given("I create MySQL to CH replicated table", description=table_name):
        create_mysql_table(
            table_name=table_name,
            columns=mysql_columns,
            primary_key="MyData",
        )

    with Then("I check the ClickHouse column type"):
        for attempt in retries(timeout=120, delay=5):
            with attempt:
                actual = clickhouse.query(
                    "SELECT type FROM system.columns WHERE database='test' "
                    f"AND table='{table_name}' AND name='MyData' FORMAT TSVRaw"
                ).output.strip()
                assert actual == "UInt64", (
                    f"SERIAL produced ClickHouse type {actual!r}, expected 'UInt64'"
                )


@TestOutline(Feature)
@Examples(
    "mysql_type ch_type values ch_values nullable",
    [
        # JSON and SET carry a MySQL-specific textual representation with no
        # ClickHouse counterpart, so both replicate verbatim as String. SET is
        # a comma-joined list of its declared members.
        ("JSON", "String", ["""'{"a": 1}'"""], ['"{""a"": 1}"'], False),
        ("SET('a','b')", "String", ["'a,b'"], ['"a,b"'], False),
        ("JSON", "String", ["NULL"], ["\\N"], True),
        ("SET('a','b')", "String", ["NULL"], ["\\N"], True),
    ],
)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_JSON("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_SET("1.0"),
)
def json_and_set(self, mysql_type, ch_type, values, ch_values, nullable):
    """Check replication of MySQL 'JSON' and 'SET' data types."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            check_datatype_replication(
                mysql_type=mysql_type,
                ch_type=ch_type,
                values=values,
                ch_values=ch_values,
                nullable=nullable,
                clickhouse_table_engine=clickhouse_table_engine,
            )


@TestOutline(Feature)
@Examples(
    "mysql_type ch_type sample_value nullable",
    [
        # A geometry is stored in MySQL's own binary encoding, not text, so
        # there is no portable textual form to compare a value against. The
        # row therefore asserts the ClickHouse TYPE the connector derives --
        # String, since ClickHouse has no geometry type -- and that the
        # column exists with that type after a live insert.
        ("POINT", "String", "ST_GeomFromText('POINT(1 2)')", False),
        ("GEOMETRY", "String", "ST_GeomFromText('POINT(1 2)')", False),
        ("LINESTRING", "String", "ST_GeomFromText('LINESTRING(0 0,1 1)')", False),
        ("POLYGON", "String", "ST_GeomFromText('POLYGON((0 0,1 0,1 1,0 0))')", False),
        ("POINT", "Nullable(String)", "NULL", True),
    ],
)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes("1.0")
)
def spatial_types(self, mysql_type, ch_type, sample_value, nullable):
    """Check replication of MySQL spatial data types.

    VECTOR is deliberately absent: it is a MySQL 9.x type and the end-to-end
    MySQL server is 8.0, so the statement would be rejected by the source
    before the connector ever saw it.
    """
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            check_clickhouse_type(
                mysql_type=mysql_type,
                ch_type=ch_type,
                sample_value=sample_value,
                clickhouse_table_engine=clickhouse_table_engine,
                nullable=nullable,
            )


@TestOutline(Feature)
@Examples(
    "mysql_type ch_type values ch_values nullable",
    [
        # The NATIONAL / N* spellings are the SQL-standard synonyms of CHAR
        # and VARCHAR with a utf8 character set; the connector resolves them
        # to the same String type as the bare spelling.
        ("NATIONAL VARCHAR(10)", "String", ["'nat'"], ['"nat"'], False),
        ("NATIONAL CHAR(10)", "String", ["'nch'"], ['"nch"'], False),
        ("NVARCHAR(10)", "String", ["'nv'"], ['"nv"'], False),
        ("NCHAR(10)", "String", ["'nc'"], ['"nc"'], False),
        ("NATIONAL VARCHAR(10)", "String", ["NULL"], ["\\N"], True),
        # LONG VARCHAR and LONG VARBINARY are the MEDIUMTEXT / MEDIUMBLOB
        # synonyms and replicate as String.
        ("LONG VARCHAR", "String", ["'long'"], ['"long"'], False),
        ("LONG VARBINARY", "String", ["'longb'"], ['"longb"'], False),
        ("LONG VARCHAR", "String", ["NULL"], ["\\N"], True),
    ],
)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_String("1.0")
)
def national_and_long(self, mysql_type, ch_type, values, ch_values, nullable):
    """Check replication of MySQL 'NATIONAL' and 'LONG' type spellings."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            check_datatype_replication(
                mysql_type=mysql_type,
                ch_type=ch_type,
                values=values,
                ch_values=ch_values,
                nullable=nullable,
                clickhouse_table_engine=clickhouse_table_engine,
            )


@TestModule
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Nullable("1.0")
)
@Name("types")
def module(self):
    """Verify correct replication of all supported MySQL data types."""

    for feature in loads(current_module(), Feature):
        Feature(test=feature)()
