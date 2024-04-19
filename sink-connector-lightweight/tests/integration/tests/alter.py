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
            with When(f"I insert some data into MySQL {table_name}"):
                node.query(f"INSERT INTO {table_name} values (1,1);")

            with And(
                f"I check that ClickHouse replicated table test.{table_name} was created"
            ):
                retry(
                    self.context.cluster.node("clickhouse").query, timeout=100, delay=5
                )("SHOW TABLES FROM test", message=f"{table_name}")

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


@TestModule
@Name("alter")
def module(self):
    """Check simple `ALTER` queries for MySQL to ClickHouse replication."""
    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
