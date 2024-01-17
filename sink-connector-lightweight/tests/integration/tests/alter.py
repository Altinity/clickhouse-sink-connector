from integration.tests.steps.sql import *
from integration.tests.steps.statements import (
    all_mysql_datatypes_dict,
    all_ch_datatypes_dict,
)
from integration.tests.steps.service_settings_steps import *
from testflows.asserts import snapshot, values

@TestOutline
def create_replicated_tables(
    self,
    name,
    clickhouse_table_engine,
    mysql_node=None,
    clickhouse_node=None,
):
    """Outline to create MySQL to CLickHouse replicated table."""

    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    name = name

    with Given(
        f"I create different MySQL to ClickHouse replicated tables with "
        f"ClickHouse table creation method {self.context.env} "
        f"and ClickHouse table engine {clickhouse_table_engine}"
    ):
        tables_names = define(
            "List of replicated tables for test",
            create_tables(
                table_name=name, clickhouse_table_engine=clickhouse_table_engine
            ),
        )

        for table_name in tables_names:
            with When(f"I insert some data into MySQL {table_name}"):
                mysql_node.query(f"INSERT INTO {table_name} values (1,1);")

            with And(
                f"I check that ClickHouse replicated table test.{table_name} was created"
            ):
                for retry in retries(timeout=100, delay=5):
                    with retry:
                        clickhouse_node.query(
                            "SHOW TABLES FROM test", message=f"{table_name}"
                        )

    return tables_names


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add("1.0")
)
def add_column(self, node=None):
    """Check that after `ALTER TABLE ADD COLUMN` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"t{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_names = define(
                "List of different replicated tables with inserted data",
                create_replicated_tables(
                    name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                with When(
                    f"I perform `ALTER TABLE ADD COLUMN` on replicated table {table_name}"
                ):
                    node.query(
                        f"ALTER TABLE {table_name} ADD COLUMN new_col varchar(255);"
                    )

                with Then(
                    f"I check that Clickhouse replicated table {table_name} has the new column"
                ):
                    retry(
                        self.context.cluster.node("clickhouse").query,
                        timeout=100,
                        delay=5,
                    )(
                        f"DESC test.{table_name} FORMAT CSV",
                        message='"new_col","Nullable(String)"',
                    )


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add_NullNotNull(
        "1.0"
    )
)
def add_column_not_null(self, node=None):
    """Check that after `ALTER TABLE ADD COLUMN NOT NULL` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"t{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_names = define(
                "List of different replicated tables with inserted data",
                create_replicated_tables(
                    name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )
        for table_name in tables_names:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                with When(
                    f"I perform `ALTER TABLE ADD COLUMN NOT NULL` on replicated table {table_name}"
                ):
                    node.query(
                        f"ALTER TABLE {table_name} ADD COLUMN new_col varchar(255) NOT NULL;"
                    )

                with Then(
                    f"I check that Clickhouse replicated table {table_name} has the new column"
                ):
                    retry(
                        self.context.cluster.node("clickhouse").query,
                        timeout=100,
                        delay=5,
                    )(
                        f"DESC test.{table_name} FORMAT CSV",
                        message='"new_col","String"',
                    )


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add_Default("1.0")
)
def add_column_default(self, node=None):
    """Check that after `ALTER TABLE ADD COLUMN DEFAULT` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"t{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_names = define(
                "List of different replicated tables with inserted data",
                create_replicated_tables(
                    name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                with When(
                    f"I perform `ALTER TABLE ADD COLUMN DEFAULT` on replicated table {table_name}"
                ):
                    node.query(
                        f"ALTER TABLE {table_name} ADD COLUMN new_col varchar(255) DEFAULT 'some_default';"
                    )

                with Then(
                    f"I check that Clickhouse replicated table {table_name} has the new column"
                ):
                    retry(
                        self.context.cluster.node("clickhouse").query,
                        timeout=100,
                        delay=5,
                    )(
                        f"SELECT * FROM test.{table_name} FORMAT CSV",
                        message='"some_default"',
                    )


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add_FirstAfter(
        "1.0"
    )
)
def add_column_first_after(self, node=None):
    """Check that after `ADD COLUMN FIRST, AFTER` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"t{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_names = define(
                "List of different replicated tables with inserted data",
                create_replicated_tables(
                    name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                with When(
                    "I perform `ALTER TABLE ADD COLUMN FIRST` and `ALTER TABLE ADD COLUMN AFTER`"
                    f"  on replicated table {table_name}"
                ):
                    node.query(
                        f"ALTER TABLE {table_name} ADD COLUMN new_col varchar(255) FIRST;"
                    )

                    retry(node.query, timeout=100, delay=5)(
                        f"ALTER TABLE {table_name} ADD COLUMN second_col varchar(255) AFTER new_col;"
                    )

                with Then(
                    f"I check that Clickhouse replicated table {table_name} has the `new_column` "
                    f"and `second_col` and `id` columns are after it"
                ):
                    retry(
                        self.context.cluster.node("clickhouse").query,
                        timeout=100,
                        delay=5,
                    )(
                        f"DESC test.{table_name} FORMAT CSV",
                        message='"new_col","Nullable(String)","","","","",""\n"second_col","Nullable(String)","","","","",""\n"id","Int32","","","","",""',
                    )


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Drop("1.0")
)
def drop_column(self, node=None):
    """Check that after `ALTER TABLE DROP COLUMN` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"t{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_names = define(
                "List of different replicated tables with inserted data",
                create_replicated_tables(
                    name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                with When(
                    f"I perform `ALTER TABLE ADD COLUMN DEFAULT` on replicated table {table_name}"
                ):
                    node.query(
                        f"ALTER TABLE {table_name} ADD COLUMN new_col varchar(255) DEFAULT 'some_default';"
                    )

                with And(
                    f"I check that Clickhouse replicated table {table_name} has the new column"
                ):
                    retry(
                        self.context.cluster.node("clickhouse").query,
                        timeout=100,
                        delay=5,
                    )(
                        f"SELECT new_col FROM test.{table_name} FORMAT CSV",
                        message='"some_default"',
                    )

                with And(
                    f"I perform `ALTER TABLE DROP COLUMN` on replicated table {table_name}"
                ):
                    node.query(f"ALTER TABLE {table_name} DROP COLUMN new_col;")

                with Then(
                    f"I check that new column in Clickhouse replicated table {table_name} has been deleted"
                ):
                    retry(
                        self.context.cluster.node("clickhouse").query,
                        timeout=20,
                        delay=6,
                    )(
                        f"SELECT new_col FROM test.{table_name} FORMAT CSV",
                        exitcode=47,
                        message="DB::Exception: Missing columns",
                    )


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify("1.0")
)
def modify_column(self, node=None):
    """Check that after `MODIFY COLUMN data_type` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"t{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_names = define(
                "List of different replicated tables for test",
                create_replicated_tables(
                    name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                with When(
                    f"I perform `MODIFY COLUMN data_type` on replicated table {table_name}"
                ):
                    node.query(
                        f"ALTER TABLE {table_name} MODIFY COLUMN x varchar(100);"
                    )

                with Then(
                    f"I check that Clickhouse replicated table {table_name} has the new column"
                ):
                    retry(
                        self.context.cluster.node("clickhouse").query,
                        timeout=100,
                        delay=5,
                    )(f"DESC test.{table_name} FORMAT CSV", message='"x","String"')


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify_NullNotNull(
        "1.0"
    )
)
def modify_column_null(self, node=None):
    """Check that after `MODIFY COLUMN data_type NULL` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"t{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_names = define(
                "List of different replicated tables for test",
                create_replicated_tables(
                    name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                with When(
                    f"I perform `MODIFY COLUMN data_type NULL` on replicated table {table_name}"
                ):
                    node.query(
                        f"ALTER TABLE {table_name} MODIFY COLUMN x varchar(100) NULL;"
                    )

                with Then(
                    f"I check that Clickhouse replicated table {table_name} has the new column"
                ):
                    retry(
                        self.context.cluster.node("clickhouse").query,
                        timeout=100,
                        delay=5,
                    )(
                        f"DESC test.{table_name} FORMAT CSV",
                        message='"x","Nullable(String)"',
                    )


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify_NullNotNull(
        "1.0"
    )
)
def modify_column_not_null(self, node=None):
    """Check that after `MODIFY COLUMN data_type NOT NULL` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"t{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_names = define(
                "List of different replicated tables for test",
                create_replicated_tables(
                    name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                with When(
                    f"I perform `MODIFY COLUMN data_type NOT NULL` on replicated table {table_name}"
                ):
                    node.query(
                        f"ALTER TABLE {table_name} MODIFY COLUMN x varchar(100) NOT NULL;"
                    )

                with Then(
                    f"I check that Clickhouse replicated table {table_name} has the new column"
                ):
                    retry(
                        self.context.cluster.node("clickhouse").query,
                        timeout=100,
                        delay=5,
                    )(f"DESC test.{table_name} FORMAT CSV", message='"x","String"')


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify_Default(
        "1.0"
    )
)
def modify_column_default(self, node=None):
    """Check that after `ALTER TABLE MODIFY COLUMN DEFAULT` query MySQL and Clickhouse has the same columns."""
    xfail("doesn't work")
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"t{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_names = define(
                "List of different replicated tables for test",
                create_replicated_tables(
                    name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                with When(
                    f"I perform `ALTER TABLE ADD COLUMN` on replicated table {table_name}"
                ):
                    node.query(
                        f"ALTER TABLE {table_name} ADD COLUMN new_col varchar(255);"
                    )

                    retry(
                        self.context.cluster.node("clickhouse").query,
                        timeout=100,
                        delay=5,
                    )(
                        f"DESC test.{table_name} FORMAT CSV",
                        message='"new_col","String"',
                    )

                with And(
                    f"I perform `ALTER TABLE MODIFY COLUMN DEFAULT` on replicated table {table_name}"
                ):
                    node.query(
                        f"ALTER TABLE {table_name} MODIFY COLUMN new_col varchar(255) DEFAULT 'some_default';"
                    )

                with Then(
                    f"I check that Clickhouse replicated table {table_name} has the DEFAULT value"
                ):
                    retry(
                        self.context.cluster.node("clickhouse").query,
                        timeout=100,
                        delay=5,
                    )(
                        f"SELECT * FROM test.{table_name} FORMAT CSV",
                        message='"some_default"',
                    )


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify_FirstAfter(
        "1.0"
    )
)
def modify_column_first_after(self, node=None):
    """Check that after `MODIFY COLUMN FIRST, AFTER` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"t{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_names = define(
                "List of different replicated tables for test",
                create_replicated_tables(
                    name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                with When(
                    f"I perform `ALTER TABLE MODIFY COLUMN FIRST` on replicated table {table_name}"
                ):
                    node.query(f"ALTER TABLE {table_name} MODIFY COLUMN x int FIRST;")

                with Then(
                    f"I check that Clickhouse replicated table {table_name} has the `x` column first"
                ):
                    retry(
                        self.context.cluster.node("clickhouse").query,
                        timeout=100,
                        delay=5,
                    )(
                        f"DESC test.{table_name} FORMAT CSV",
                        message='"x","Int32","","","","",""\n"id","Int32","","","","",""',
                    )

                with When(
                    f"I perform `ALTER TABLE MODIFY COLUMN AFTER` on replicated table {table_name}"
                ):
                    retry(node.query, timeout=100, delay=5)(
                        f"ALTER TABLE {table_name} MODIFY COLUMN x int AFTER id;"
                    )

                with Then(
                    f"I check that Clickhouse replicated table {table_name} "
                    f"has the `x` column after 'id' column"
                ):
                    retry(
                        self.context.cluster.node("clickhouse").query,
                        timeout=100,
                        delay=5,
                    )(
                        f"DESC test.{table_name} FORMAT CSV",
                        message='"id","Int32","","","","",""\n"x","Int32","","","","",""',
                    )


@TestCheck
def check_modify_column_for_all_datatypes(self, old_datatype, new_datatype, mysql_node=None, clickhouse_node=None):
    """Check that after `MODIFY COLUMN column_name new_data_type` query MySQL and Clickhouse have the same column types."""

    table_name = f"t{getuid()}"

    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.clickhouse_node


    with Given(
        f"I create and insert data in different MySQL to ClickHouse replicated tables with "
        f"ClickHouse table creation method {self.context.env} "
        f"and ClickHouse table engine ReplacingMergeTree"
    ):
        create_mysql_to_clickhouse_replicated_table(
            name=table_name,
            mysql_columns=f"{old_datatype}  {all_mysql_datatypes_dict[old_datatype]}",
            clickhouse_columns=all_ch_datatypes_dict[old_datatype],
            clickhouse_table_engine="ReplacingMergeTree",
        )

    with When(f"I perform `MODIFY COLUMN data_type` on replicated table {table_name}"):
        mysql_node.query(
            f"ALTER TABLE {table_name} MODIFY COLUMN {old_datatype} {all_mysql_datatypes_dict[new_datatype]};"
        )

    with Then(
        f"I check that Clickhouse replicated table {table_name} has the new column"
    ):
        for retry in retries(timeout=100, delay=5):
            with retry:
                clickhouse_node.query(f"DESCRIBE TABLE test.{table_name}")
        pause()
        # with values() as that:
        #     assert that(
        #         snapshot(
        #
        #         )
        #     )

@TestSketch(Feature)
@Flags(TE)
def modify_column_with_all_datatypes(self):
    check_modify_column_for_all_datatypes(
        old_datatype=either(*all_mysql_datatypes_dict),
        new_datatype=either(*all_mysql_datatypes_dict),
    )


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Change_NullNotNullOldNew(
        "1.0"
    )
)
def change_column_name_to_new_name_null(self, node=None):
    """Check that after `CHANGE COLUMN old_name new_name data_type NULL` query MySQL and ClickHouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"t{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_names = define(
                "List of different replicated tables for test",
                create_replicated_tables(
                    name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                with When(
                    f"I perform `CHANGE COLUMN old_name new_name data_type NULL` on replicated table {table_name}"
                ):
                    node.query(
                        f"ALTER TABLE {table_name} CHANGE COLUMN x x2 varchar(255) NULL;"
                    )

                with Then(
                    f"I check that Clickhouse replicated table {table_name} has column with new name and new data"
                ):
                    retry(
                        self.context.cluster.node("clickhouse").query,
                        timeout=100,
                        delay=5,
                    )(
                        f"DESC test.{table_name} FORMAT CSV",
                        message='"x2","Nullable(String)"',
                    )


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Change_NullNotNullOldNew(
        "1.0"
    )
)
def change_column_name_to_new_name_not_null(self, node=None):
    """Check that after `CHANGE COLUMN old_name new_name data_type NOT NULL` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"t{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_names = define(
                "List of different replicated tables for test",
                create_replicated_tables(
                    name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                with When(
                    f"I perform `CHANGE COLUMN old_name new_name data_type NOT NULL` on replicated table {table_name}"
                ):
                    node.query(
                        f"ALTER TABLE {table_name} CHANGE COLUMN x x2 varchar(255) NOT NULL;"
                    )

                with Then(
                    f"I check that Clickhouse replicated table {table_name} has column with new name and "
                    f"new data"
                ):
                    retry(
                        self.context.cluster.node("clickhouse").query,
                        timeout=100,
                        delay=5,
                    )(f"DESC test.{table_name} FORMAT CSV", message='"x2","String"')


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Change_FirstAfter(
        "1.0"
    )
)
def change_column_first_after(self, node=None):
    """Check that after `CHANGE COLUMN FIRST, AFTER` query MySQL and Clickhouse has the same columns."""

    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"t{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_names = define(
                "List of different replicated tables for test",
                create_replicated_tables(
                    name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                with When(
                    f"I perform `ALTER TABLE CHANGE COLUMN FIRST` on replicated table {table_name}"
                ):
                    node.query(
                        f"ALTER TABLE {table_name} CHANGE COLUMN x x2 int FIRST;"
                    )

                with Then(
                    f"I check that Clickhouse replicated table {table_name} has the `x2` column first"
                ):
                    retry(
                        self.context.cluster.node("clickhouse").query,
                        timeout=100,
                        delay=5,
                    )(
                        f"DESC test.{table_name} FORMAT CSV",
                        message='"x2","Int32","","","","",""\n"id","Int32","","","","",""',
                    )

                with When(
                    f"I perform `ALTER TABLE CHANGE COLUMN AFTER` on replicated table {table_name}"
                ):
                    retry(node.query, timeout=100, delay=5)(
                        f"ALTER TABLE {table_name} CHANGE COLUMN x2 x int AFTER id;"
                    )

                with Then(
                    f"I check that Clickhouse replicated table {table_name} has the `x` column after "
                    f"'id' column"
                ):
                    retry(
                        self.context.cluster.node("clickhouse").query,
                        timeout=100,
                        delay=5,
                    )(
                        f"DESC test.{table_name} FORMAT CSV",
                        message='"id","Int32","","","","",""\n"x","Int32","","","","",""',
                    )


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Rename("1.0")
)
def rename_column(self, node=None):
    """Check that after `RENAME COLUMN old_name TO new_name` query MySQL and Clickhouse has the same columns."""
    xfail("doesn't change column")
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"t{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_names = define(
                "List of different replicated tables for test",
                create_replicated_tables(
                    name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                with When(
                    f"I perform `RENAME COLUMN old_name TO new_name` on replicated table {table_name}"
                ):
                    node.query(f"ALTER TABLE {table_name} RENAME COLUMN x TO x2;")

                with Then(
                    f"I check that Clickhouse replicated table {table_name} has column with the new name"
                ):
                    retry(
                        self.context.cluster.node("clickhouse").query,
                        timeout=100,
                        delay=5,
                    )(
                        f"DESC test.{table_name} FORMAT CSV",
                        message='"x2","Nullable(Int32)"',
                    )


@TestFeature
def alter_column_set_default(self, node=None):
    """Check that after `ALTER COLUMN SET DEFAULT` query MySQL and Clickhouse has the same columns."""
    xfail("Not supported by grammar")
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"t{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_names = define(
                "List of different replicated tables for test",
                create_replicated_tables(
                    name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

            for table_name in tables_names:
                with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                    with When(
                        f"I perform `ALTER TABLE ADD COLUMN` on replicated table {table_name}"
                    ):
                        node.query(
                            f"ALTER TABLE {table_name} ADD COLUMN new_col varchar(255);"
                        )

                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(
                            f"DESC test.{table_name} FORMAT CSV",
                            message='"new_col","String"',
                        )

                    with And(
                        f"I perform `ALTER COLUMN SET DEFAULT` on replicated table {table_name}"
                    ):
                        node.query(
                            f"ALTER TABLE {table_name} ALTER COLUMN new_col SET DEFAULT 'some_default';"
                        )

                    with Then(
                        f"I check that Clickhouse replicated table {table_name} has the DEFAULT value"
                    ):
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(
                            f"SELECT * FROM test.{table_name} FORMAT CSV",
                            message='"some_default"',
                        )


@TestFeature
def alter_column_drop_default(self, node=None):
    """Check that after `ALTER COLUMN DROP DEFAULT` query MySQL and Clickhouse has the same columns."""
    xfail("Not supported by grammar")
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"t{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_names = define(
                "List of different replicated tables for test",
                create_replicated_tables(
                    name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

            for table_name in tables_names:
                with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                    with When(
                        "I perform `ALTER TABLE ADD COLUMN` on replicated table {table_name}"
                    ):
                        node.query(
                            f"ALTER TABLE {table_name} ADD COLUMN new_col varchar(255) DEFAULT 'some_default';"
                        )

                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(f"DESC test.{table_name} FORMAT CSV", message='"new_col"')

                    with Then(
                        f"I check that Clickhouse replicated table {table_name} has the DEFAULT value"
                    ):
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(
                            f"SELECT * FROM test.{table_name} FORMAT CSV",
                            message='"some_default"',
                        )

                    with And(
                        "I perform `ALTER COLUMN DROP DEFAULT` on replicated table {table_name}"
                    ):
                        node.query(
                            f"ALTER TABLE {table_name} ALTER COLUMN new_col DROP DEFAULT;"
                        )

                    with Then(
                        f"I check that in the Clickhouse replicated table {table_name} the DEFAULT value has"
                        f" been removed"
                    ):
                        assert (
                            self.context.cluster.node("clickhouse")
                            .query(f"SELECT new_col FROM test.{table_name} FORMAT CSV")
                            .output.strip()
                            != "some_default"
                        )


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_AddConstraint("1.0")
)
def add_constraint(self, node=None):
    """Check that after `ALTER TABLE ADD CONSTRAINT pk_id PRIMARY KEY (id)` query MySQL and Clickhouse has the same schema."""
    xfail("Add constraint with Primary key(Not supported) and check not finished")
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"t{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_names = define(
                "List of different replicated tables for test",
                create_replicated_tables(
                    name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

            for table_name in tables_names:
                if table_name.endswith("_no_primary_key"):
                    with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                        with When(
                            f"I perform `ALTER TABLE ADD CONSTRAINT` on replicated table {table_name}"
                        ):
                            node.query(
                                f"ALTER TABLE {table_name} ADD CONSTRAINT pk_id PRIMARY KEY (id);"
                            )


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_DropConstraint("1.0")
)
def drop_constraint(self, node=None):
    """Check that after `ALTER TABLE DROP CONSTRAINT` query MySQL and Clickhouse has the same columns."""
    xfail("Add constraint with Primary key(Not supported) and check not finished")
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"t{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_names = define(
                "List of different replicated tables for test",
                create_replicated_tables(
                    name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

            for table_name in tables_names:
                if table_name.endswith("_no_primary_key"):
                    with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                        with When(
                            f"I perform `ALTER TABLE ADD CONSTRAINT` on replicated table {table_name}"
                        ):
                            node.query(
                                f"ALTER TABLE {table_name} ADD CONSTRAINT pk_id PRIMARY KEY (id);"
                            )

                        with And(
                            f"I perform `ALTER TABLE DROP CONSTRAINT` on replicated table {table_name}"
                        ):
                            node.query(f"ALTER TABLE {table_name} DROP CONSTRAINT;")


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Rename("1.0")
)
def rename_table(
    self,
    node=None,
    mysql_node=None,
    clickhouse_node=None,
):
    """Checking that usage of the RENAME TABLE in mysql is reflected on the ClickHouse side."""

    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    table_name = f"tb_{getuid()}"
    new_table_name = f"tb_{getuid()}"
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            create_mysql_to_clickhouse_replicated_table(
                name=f"{table_name}",
                mysql_columns="x INT NOT NULL",
                clickhouse_columns="x Int32",
                clickhouse_table_engine=clickhouse_table_engine,
            )

        with And("I check that the table was replicated"):
            for retry in retries(timeout=100, delay=5):
                with retry:
                    clickhouse_node.query(
                        "SHOW TABLES FROM test", message=f"{table_name}"
                    )

        with When(
            f"I perform `RENAME TABLE table_name` on replicated table {table_name}"
        ):
            mysql_node.query(f"RENAME TABLE {table_name} TO {new_table_name};")

        with Then(
            f"I check that table {table_name} was renamed to {new_table_name} on the ClickHouse side"
        ):
            for retry in retries(timeout=100, delay=5):
                with retry:
                    clickhouse_node.query(
                        "SHOW TABLES FROM test", message=f"{new_table_name}"
                    )


@TestModule
@Name("alter")
def module(self, clickhouse_node="clickhouse", mysql_node="mysql-master"):
    """Check simple `ALTER` queries for MySql to ClickHouse replication."""
    self.context.clickhouse_node = self.context.cluster.node(clickhouse_node)
    self.context.mysql_node = self.context.cluster.node(mysql_node)

    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
