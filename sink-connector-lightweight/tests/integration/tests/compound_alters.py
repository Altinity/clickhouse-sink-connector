from integration.tests.steps.mysql import *
from integration.tests.steps.datatypes import *
from integration.tests.steps.service_settings import *


@TestOutline
def create_replicated_tables(
    self,
    name,
    clickhouse_table_engine,
    node=None,
):
    """Outline to create MySQL to CLickHouse replicated table."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

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
            with When("I insert some data into MySQL table"):
                node.query(f"INSERT INTO {table_name} values (1,1);")

            with And("I check that ClickHouse replicated table was created"):
                retry(
                    self.context.cluster.node("clickhouse").query, timeout=100, delay=5
                )("SHOW TABLES FROM test", message=f"{table_name}")

    return tables_names


@TestFeature
def add_change_column(self, node=None):
    """Check that after `ALTER TABLE ADD COLUMN, CHANGE COLUMN` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"f{getuid()}"

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
            if not table_name.endswith("_complex"):
                with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                    with When(
                        f"I perform `ALTER TABLE ADD COLUMN, CHANGE COLUMN` on replicated table {table_name}"
                    ):
                        node.query(
                            f"ALTER TABLE {table_name} ADD COLUMN new_col varchar(255) AFTER id, CHANGE COLUMN x x2 varchar(255) NULL"
                        )

                    with Then(
                        f"I check that Clickhouse replicated table {table_name} has the new column and changed column"
                    ):
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(
                            f"DESC test.{table_name} FORMAT CSV",
                            message='"new_col","Nullable(String)","","","","",""\n"x2","Nullable(String)"',
                        )


@TestFeature
def change_add_column(self, node=None):
    """Check that after `ALTER TABLE CHANGE COLUMN, ADD COLUMN` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"f{getuid()}"

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
            if not table_name.endswith("_complex"):
                with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                    with When(
                        f"I perform `ALTER TABLE CHANGE COLUMN, ADD COLUMN` on replicated table {table_name}"
                    ):
                        node.query(
                            f"ALTER TABLE {table_name} CHANGE COLUMN x x2 varchar(255) NULL, ADD COLUMN new_col varchar(255) AFTER id"
                        )

                    with Then(
                        f"I check that Clickhouse replicated table {table_name} has the new column and changed "
                        f"column"
                    ):
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(
                            f"DESC test.{table_name} FORMAT CSV",
                            message='"new_col","Nullable(String)","","","","",""\n"x2","Nullable(String)"',
                        )


@TestFeature
def add_modify_column(self, node=None):
    """Check that after `ALTER TABLE ADD COLUMN, MODIFY COLUMN` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"f{getuid()}"

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
            if not table_name.endswith("_complex"):
                with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                    with When(
                        f"I perform `ALTER TABLE ADD COLUMN, MODIFY COLUMN` on replicated table {table_name}"
                    ):
                        node.query(
                            f"ALTER TABLE {table_name} ADD COLUMN new_col varchar(255) AFTER id, MODIFY COLUMN x varchar(255) NULL"
                        )

                    with Then(
                        f"I check that Clickhouse replicated table {table_name} has the new column and modified column"
                    ):
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(
                            f"DESC test.{table_name} FORMAT CSV",
                            message='"new_col","Nullable(String)","","","","",""\n"x","Nullable(String)"',
                        )


@TestFeature
def modify_add_column(self, node=None):
    """Check that after `ALTER TABLE MODIFY COLUMN, ADD COLUMN` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"f{getuid()}"

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
            if not table_name.endswith("_complex"):
                with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                    with When(
                        f"I perform `ALTER TABLE MODIFY COLUMN, ADD COLUMN` on replicated table {table_name}"
                    ):
                        node.query(
                            f"ALTER TABLE {table_name} MODIFY COLUMN x varchar(255) NULL, ADD COLUMN new_col varchar(255) AFTER id"
                        )

                    with Then(
                        f"I check that Clickhouse replicated table {table_name} has the new column and modified "
                        f"column"
                    ):
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(
                            f"DESC test.{table_name} FORMAT CSV",
                            message='"new_col","Nullable(String)","","","","",""\n"x","Nullable(String)"',
                        )


@TestFeature
def add_rename_column(self, node=None):
    """Check that after `ALTER TABLE ADD COLUMN, RENAME COLUMN` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"f{getuid()}"

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
            if not table_name.endswith("_complex"):
                with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                    with When(
                        f"I perform `ALTER TABLE ADD COLUMN, RENAME COLUMN` on replicated table {table_name}"
                    ):
                        node.query(
                            f"ALTER TABLE {table_name} ADD COLUMN new_col varchar(255) AFTER id, RENAME COLUMN x to x2"
                        )

                    with Then(
                        f"I check that Clickhouse replicated table {table_name} has the new column and renamed column"
                    ):
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(
                            f"DESC test.{table_name} FORMAT CSV",
                            message='"new_col","Nullable(String)","","","","",""\n"x2"',
                        )


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add_Multiple("1.0")
)
def multiple_add_column(self, node=None):
    """Check that after multiple `ALTER TABLE ADD` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"f{getuid()}"

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
            if not table_name.endswith("_complex"):
                with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                    with When(
                        f"I perform multiple `ALTER TABLE ADD COLUMN` on replicated table {table_name}"
                    ):
                        node.query(
                            f"ALTER TABLE {table_name} ADD COLUMN new_col1 varchar(255) AFTER id, ADD COLUMN new_col2 varchar(255) AFTER id, ADD COLUMN new_col3 varchar(255) AFTER id"
                        )

                    with Then(
                        f"I check that Clickhouse replicated table {table_name} has the new columns"
                    ):
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(
                            f"DESC test.{table_name} FORMAT CSV",
                            message='"new_col3","Nullable(String)","","","","",""\n"new_col2","Nullable(String)","",""'
                            ',"","",""\n"new_col1","Nullable(String)"',
                        )


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify_Multiple(
        "1.0"
    )
)
def multiple_modify_column(self, node=None):
    """Check that after multiple `ALTER TABLE MODIFY` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"f{getuid()}"

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
            if not table_name.endswith("_complex"):
                with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                    with When(
                        f"I perform multiple `ALTER TABLE ADD COLUMN` on replicated table {table_name}"
                    ):
                        node.query(
                            f"ALTER TABLE {table_name} ADD COLUMN new_col1 varchar(255) AFTER id, ADD COLUMN new_col2 varchar(255) AFTER id, ADD COLUMN new_col3 varchar(255) AFTER id"
                        )

                    with And(
                        f"I check that Clickhouse replicated table {table_name} has the new columns"
                    ):
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(
                            f"DESC test.{table_name} FORMAT CSV",
                            message='"new_col3","Nullable(String)","","","","",""\n"new_col2","Nullable(String)","",""'
                            ',"","",""\n"new_col1","Nullable(String)"',
                        )

                    with And(
                        f"I perform multiple `ALTER TABLE MODIFY COLUMN` on replicated table {table_name}"
                    ):
                        node.query(
                            f"ALTER TABLE {table_name} MODIFY COLUMN new_col1 INT,"
                            f" MODIFY COLUMN new_col2 INT,"
                            f" MODIFY COLUMN new_col3 INT"
                        )

                    with Then(
                        f"I check that Clickhouse replicated table {table_name} has the new columns data types"
                    ):
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(
                            f"DESC test.{table_name} FORMAT CSV",
                            message='"new_col3","Int32","","","","",""\n"new_col2","Int32","",""'
                            ',"","",""\n"new_col1","Int32"',
                        )


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Change_Multiple(
        "1.0"
    )
)
def multiple_change_column(self, node=None):
    """Check that after multiple `ALTER TABLE CHANGE` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"f{getuid()}"

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
            if not table_name.endswith("_complex"):
                with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                    with When(
                        f"I perform multiple `ALTER TABLE ADD COLUMN` on replicated table {table_name}"
                    ):
                        node.query(
                            f"ALTER TABLE {table_name} ADD COLUMN new_col1 varchar(255) AFTER id, ADD COLUMN new_col2 varchar(255) AFTER id, ADD COLUMN new_col3 varchar(255) AFTER id"
                        )

                    with And(
                        f"I check that Clickhouse replicated table {table_name} has the new columns"
                    ):
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(
                            f"DESC test.{table_name} FORMAT CSV",
                            message='"new_col3","Nullable(String)","","","","",""\n"new_col2","Nullable(String)","",""'
                            ',"","",""\n"new_col1","Nullable(String)"',
                        )

                    with And(
                        f"I perform multiple `ALTER TABLE CHANGE COLUMN` on replicated table {table_name}"
                    ):
                        node.query(
                            f"ALTER TABLE {table_name} CHANGE COLUMN new_col1 new_col11 INT,"
                            f" CHANGE COLUMN new_col2 new_col22 INT,"
                            f" CHANGE COLUMN new_col3 new_col33 INT"
                        )

                    with Then(
                        f"I check that Clickhouse replicated table {table_name} has the new columns"
                    ):
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(
                            f"DESC test.{table_name} FORMAT CSV",
                            message='"new_col33","Int32","","","","",""\n"new_col22","Int32","",""'
                            ',"","",""\n"new_col11","Int32"',
                        )


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Drop_Multiple(
        "1.0"
    )
)
def multiple_drop_column(self, node=None):
    """Check that after multiple `ALTER TABLE DROP` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"f{getuid()}"

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
            if not table_name.endswith("_complex"):
                with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                    with When(
                        f"I perform multiple `ALTER TABLE ADD COLUMN` on replicated table {table_name}"
                    ):
                        node.query(
                            f"ALTER TABLE {table_name} ADD COLUMN new_col1 varchar(255) AFTER id, ADD COLUMN new_col2 varchar(255) AFTER id, ADD COLUMN new_col3 varchar(255) AFTER id"
                        )

                    with And(
                        f"I check that Clickhouse replicated table {table_name} has the new columns"
                    ):
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(
                            f"DESC test.{table_name} FORMAT CSV",
                            message='"new_col3","Nullable(String)","","","","",""\n"new_col2","Nullable(String)","",""'
                            ',"","",""\n"new_col1","Nullable(String)"',
                        )

                    with And(
                        f"I perform multiple `ALTER TABLE DROP COLUMN` on replicated table {table_name}"
                    ):
                        node.query(
                            f"ALTER TABLE {table_name} DROP COLUMN new_col1,"
                            f" DROP COLUMN new_col2,"
                            f" DROP COLUMN new_col3"
                        )

                    with Then(
                        f"I check that Clickhouse replicated table {table_name} has the same number of columns "
                        f"as in MySQL table {table_name}"
                    ):
                        mysql_columns_number = node.query(
                            columns_number.format(table_name=table_name)
                        ).output.strip()

                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(
                            columns_number.format(table_name=table_name),
                            message=mysql_columns_number[
                                len(
                                    "mysql: [Warning] Using a password on the command line "
                                    "interface can be insecure.\ncount(*)\n"
                                ) :
                            ],
                        )


@TestModule
@Name("compound alters")
def module(self):
    """Check serial connection of `ALTER` queries for MySQL to ClickHouse replication."""
    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
