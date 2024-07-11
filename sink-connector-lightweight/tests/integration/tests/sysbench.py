from datetime import datetime
from integration.tests.steps.mysql import *
from integration.tests.steps.service_settings import *


@TestScenario
def sysbench_sanity(self):
    """Check that sysbench can connect to MySQL."""
    # with Given("I enable sink connector after kafka starts up"):
    #     init_sink_connector()

    with Then(f"I check that MySQL sysbench starts correctly"):
        self.context.cluster.node("bash-tools").cmd(
            "/manual_scripts/sysbench/run_sysbench_insert_load_test.sh",
            message="Threads started!",
        )


@TestOutline
def sysbench_tests(
    self, script, test_name=None, distinct_values_timeout=70, distinct_values_delay=10
):
    """Run specified sysbench tests."""

    table_name = "sbtest1"
    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    with Given("I enable sink connector after kafka starts up"):
        time.sleep(40)
        clickhouse.query(f"TRUNCATE TABLE IF EXISTS test.{table_name}")
        clickhouse.query("SYSTEM STOP MERGES")

    with And(f"I start sysbench test script"):
        if script == "run_sysbench_tests.sh":
            self.context.cluster.node("bash-tools").cmd(
                f"/manual_scripts/sysbench/{script} -t " f"{test_name}",
                message="Threads started!",
            )
        else:
            self.context.cluster.node("bash-tools").cmd(
                "/manual_scripts/sysbench/" f"{script}", message="Threads started!"
            )

    with And(f"I write data from MySQL table to file"):
        mysql.cmd(
            f'mysql -uroot -proot -B -N -e "select * from sbtest.{table_name} order by id" | grep -v '
            f'"Using a password on the command line interface" > /tmp/MySQL.tsv'
        )

    with Then("I wait unique values from CLickHouse table equal to MySQL table"):
        mysql_count = mysql.query(
            f"SELECT count(*) FROM sbtest.{table_name}"
        ).output.strip()[90:]
        retry(
            clickhouse.query,
            timeout=distinct_values_timeout,
            delay=distinct_values_delay,
        )(
            f"SELECT count() FROM test.{table_name}  FINAL where _sign !=-1  FORMAT CSV",
            message=mysql_count,
        )

    if script == "run_sysbench_bulk_insert.sh":
        with Then(f"I write data from ClickHouse table to file"):
            clickhouse.cmd(
                'clickhouse client -uroot --password root --query "select id ,k from test.sbtest1 FINAL where _sign !=-1 '
                'order by id format TSV" | grep -v "<jemalloc>" > /tmp/share_folder/CH.tsv'
            )
            # time.sleep(30)
    else:
        with Then(f"I write data from ClickHouse table to file"):
            clickhouse.cmd(
                'clickhouse client -uroot --password root --query "select id ,k ,c ,pad from test.sbtest1 FINAL where _sign !=-1 '
                'order by id format TSV" | grep -v "<jemalloc>" > /tmp/share_folder/CH.tsv'
            )
            # time.sleep(30)

    with Then(
        "I check MySQL data has equal to CH data hash and if it is not write difference "
        "to unique .err.diff file"
    ):
        mysql_hash = mysql.cmd("sha256sum /tmp/MySQL.tsv")
        ch_hash = mysql.cmd("sha256sum /tmp/share_folder/CH.tsv")
        now = datetime.now().strftime("_%d%m%Y_%H_%M_%S")
        assert (
            mysql_hash.output.strip().split()[0] == ch_hash.output.strip().split()[0]
        ), mysql.cmd(
            # f"diff --strip-trailing-cr /tmp/MySQL.tsv /tmp/share_folder/CH.tsv > /tmp/diff{now}.err.diff"
            f"diff --strip-trailing-cr /tmp/MySQL.tsv /tmp/share_folder/CH.tsv > /tmp/diff{now}.err.diff"
        )

    with And(f"I drop tables"):
        clickhouse.query(f"DROP TABLE IF EXISTS test.{table_name}")


@TestScenario
@Repeat(1)
def insert_load(self):
    """Run "insert load" test."""
    sysbench_tests(script="run_sysbench_insert_load_test.sh")


@TestScenario
@Repeat(1)
def insert_bulk(self):
    """Run "insert bulk" test."""
    xfail("needs at least 32GB of RAM")
    sysbench_tests(script="run_sysbench_bulk_insert.sh")


@TestScenario
@Repeat(3)
def oltp_delete(self):
    """Run "oltp delete" test."""
    xfail("expected")
    sysbench_tests(script="run_sysbench_oltp_delete.sh")


@TestScenario
@Repeat(1)
def read_write_load_test(self):
    """Run "read write load" test."""
    xfail("expected")
    sysbench_tests(script="run_sysbench_read_write_load_test.sh")


@TestScenario
@Repeat(1)
def update_index(self):
    """Check MySQL by sysbench "update index" test."""
    xfail("expected")
    sysbench_tests(script="run_sysbench_update_index.sh")


@TestScenario
@Repeat(1)
def update_non_index(self):
    """Run "update non index" test."""
    xfail("expected")
    sysbench_tests(script="run_sysbench_update_non_index.sh")


@TestScenario
@Repeat(1)
def oltp_delete2(self):
    """Run "oltp delete" test."""
    xfail("expected")
    sysbench_tests(script="run_sysbench_tests.sh", test_name="oltp_delete")


@TestScenario
@Repeat(1)
def oltp_insert(self):
    """Run "oltp update non index" test."""
    xfail("expected")
    sysbench_tests(script="run_sysbench_tests.sh", test_name="oltp_insert")


@TestScenario
@Repeat(1)
def oltp_update_non_index(self):
    """Run "oltp update non index" test."""
    xfail("expected")
    sysbench_tests(script="run_sysbench_tests.sh", test_name="oltp_update_non_index")


@TestScenario
@Repeat(1)
def oltp_update_index(self):
    """Run "oltp update index" test."""
    xfail("expected")
    sysbench_tests(script="run_sysbench_tests.sh", test_name="oltp_update_index")


@TestModule
@Name("sysbench")
def module(self):
    """MySQL to ClickHouse sysbench tests."""
    xfail("doesn't ready")

    with Given("I send rpk command on kafka"):
        retry(self.context.cluster.node("kafka").cmd, timeout=100, delay=2)(
            "rpk topic create SERVER5432.sbtest.sbtest1 -p 6 rpk",
            message="SERVER5432.sbtest.sbtest1  OK",
            exitcode=0,
        )

    with And("I enable debezium connector"):
        sb_debizium_script_connector()
        init_sink_connector(auto_create_tables="auto")

    for scenario in loads(current_module(), Scenario):
        scenario()
