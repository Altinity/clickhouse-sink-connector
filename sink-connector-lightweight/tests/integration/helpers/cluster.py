import os
import time
import uuid
import inspect
import tempfile
import threading
import re

import testflows.settings as settings

from testflows.asserts import error
from testflows.connect import Shell as ShellBase
from testflows.core import *
from testflows.uexpect import ExpectTimeoutError

from integration.helpers.common import check_clickhouse_version, current_cpu

MESSAGES_TO_RETRY = [
    "DB::Exception: ZooKeeper session has been expired",
    "DB::Exception: Connection loss",
    "Coordination::Exception: Session expired",
    "Coordination::Exception: Connection loss",
    "Coordination::Exception: Operation timeout",
    "DB::Exception: Operation timeout",
    "Operation timed out",
    "ConnectionPoolWithFailover: Connection failed at try",
    "DB::Exception: New table appeared in database being dropped or detached. Try again",
    "is already started to be removing by another replica right now",
    "Shutdown is called for table",
    # happens in SYSTEM SYNC REPLICA query if session with ZooKeeper is being reinitialized.
    "is executing longer than distributed_ddl_task_timeout",  # distributed TTL timeout message
]


class Shell(ShellBase):
    def __exit__(self, type, value, traceback):
        # send exit and Ctrl-D repeatedly
        # to terminate any open shell commands.
        # This is needed for example
        # to solve a problem with
        # 'docker-compose exec {name} bash --noediting'
        # that does not clean up open bash processes
        # if not exited normally
        for i in range(10):
            if self.child is not None:
                try:
                    self.send("exit\r", eol="")
                    self.send("\x04\r", eol="")
                except OSError:
                    pass
        return super(Shell, self).__exit__(type, value, traceback)


class QueryRuntimeException(Exception):
    """Exception during query execution on the server."""

    pass


class Node(object):
    """Generic cluster node."""

    config_d_dir = "/etc/clickhouse-server/config.d/"

    def __init__(self, cluster, name, data_base_type="clickhouse"):
        self.data_base_type = data_base_type
        self.cluster = cluster
        self.name = name

    def repr(self):
        return f"Node(name='{self.name}')"

    def close_bashes(self):
        """Close all active bashes to the node."""
        with self.cluster.lock:
            for key in list(self.cluster._bash.keys()):
                if key[1].endswith(f"{self.name}"):
                    shell = self.cluster._bash.pop(key)
                    shell.__exit__(None, None, None)

    def wait_healthy(self, timeout=300):
        with By(f"waiting until container {self.name} is healthy"):
            for attempt in retries(timeout=timeout, delay=1):
                with attempt:
                    if self.command("echo 1", no_checks=1, steps=False).exitcode != 0:
                        fail("container is not healthy")

    def restart(self, timeout=300, retry_count=5, safe=True):
        """Restart node."""
        self.close_bashes()
        retry(self.cluster.command, retry_count)(
            None,
            f"{self.cluster.docker_compose} restart {self.name}",
            timeout=timeout,
            exitcode=0,
            steps=False,
        )

    def start(self, timeout=300, retry_count=5):
        """Start node."""
        retry(self.cluster.command, retry_count)(
            None,
            f"{self.cluster.docker_compose} start {self.name}",
            timeout=timeout,
            exitcode=0,
            steps=False,
        )

    def kill(self, timeout=300, retry_count=5, safe=True):
        """Kill node."""
        self.close_bashes()

        retry(self.cluster.command, retry_count)(
            None,
            f"{self.cluster.docker_compose} kill {self.name}",
            timeout=timeout,
            exitcode=0,
            steps=False,
        )

    def stop(self, timeout=300, retry_count=5, safe=True):
        """Stop node."""
        self.close_bashes()

        retry(self.cluster.command, retry_count)(
            None,
            f"{self.cluster.docker_compose} stop {self.name}",
            timeout=timeout,
            exitcode=0,
            steps=False,
        )

    def command(self, *args, **kwargs):
        return self.cluster.command(self.name, *args, **kwargs)

    def cmd(
        self,
        cmd,
        message=None,
        exitcode=None,
        steps=True,
        shell_command="bash --noediting",
        no_checks=False,
        raise_on_exception=False,
        step=By,
        *args,
        **kwargs,
    ):
        """Execute and check command.
        :param cmd: command
        :param message: expected message that should be in the output, default: None
        :param exitcode: expected exitcode, default: None
        """

        command = f"{cmd}"
        with (
            step("executing command", description=command, format_description=False)
            if steps
            else NullStep()
        ):
            try:
                r = self.cluster.bash(self.name, command=shell_command)(
                    command, *args, **kwargs
                )
            except ExpectTimeoutError:
                self.cluster.close_bash(self.name)
                raise

        if no_checks:
            return r

        if exitcode is not None:
            with Then(f"exitcode should be {exitcode}") if steps else NullStep():
                assert r.exitcode == exitcode, error(r.output)

        if message is not None:
            with (
                Then(f"output should contain message", description=message)
                if steps
                else NullStep()
            ):
                assert message in r.output, error(r.output)

        return r


class Cluster(object):
    """Simple object around docker-compose cluster."""

    def __init__(
        self,
        local=False,
        clickhouse_binary_path=None,
        clickhouse_odbc_bridge_binary_path=None,
        configs_dir=None,
        nodes=None,
        docker_compose="docker-compose",
        docker_compose_project_dir=None,
        docker_compose_file="docker-compose.yml",
        environ=None,
        thread_fuzzer=False,
        collect_service_logs=False,
        frame=None,
        caller_dir=None,
        stress=None,
    ):
        self._bash = {}
        self._control_shell = None
        self.environ = {} if (environ is None) else environ
        self.clickhouse_binary_path = clickhouse_binary_path
        self.clickhouse_odbc_bridge_binary_path = clickhouse_odbc_bridge_binary_path
        self.configs_dir = configs_dir
        self.local = local
        self.nodes = nodes or {}
        self.docker_compose = docker_compose
        self.thread_fuzzer = thread_fuzzer
        self.running = False
        self.collect_service_logs = collect_service_logs
        self.stress = stress

        if frame is None:
            frame = inspect.currentframe().f_back
        if caller_dir is None:
            caller_dir = os.path.dirname(os.path.abspath(frame.f_globals["__file__"]))

        # auto set configs directory
        if self.configs_dir is None:
            caller_configs_dir = caller_dir
            if os.path.exists(caller_configs_dir):
                self.configs_dir = caller_configs_dir

        if not os.path.exists(self.configs_dir):
            raise TypeError(f"configs directory '{self.configs_dir}' does not exist")

        if not docker_compose_project_dir:
            raise TypeError("docker compose directory must be specified.")

        if current_cpu() == "aarch64":
            if not docker_compose_project_dir.endswith("_arm64"):
                docker_compose_project_dir += f"_arm64"

        if not os.path.exists(docker_compose_project_dir):
            raise TypeError(
                f"docker compose project directory '{docker_compose_project_dir}' does not exist"
            )

        self.docker_compose_project_dir = docker_compose_project_dir

        docker_compose_file_path = os.path.join(
            docker_compose_project_dir, docker_compose_file
        )

        if not os.path.exists(docker_compose_file_path):
            raise TypeError(
                f"docker compose file '{docker_compose_file_path}' does not exist"
            )

        if self.clickhouse_binary_path:
            if self.clickhouse_binary_path.startswith("docker://"):
                if current().context.clickhouse_version is None:
                    parsed_version = ""
                    for c in self.clickhouse_binary_path.rsplit(":", 1)[-1]:
                        if c in ".0123456789":
                            parsed_version += c
                        else:
                            break
                    if parsed_version:
                        if not (
                            parsed_version.startswith(".")
                            or parsed_version.endswith(".")
                        ):
                            current().context.clickhouse_version = parsed_version

                (
                    self.clickhouse_binary_path,
                    self.clickhouse_odbc_bridge_binary_path,
                ) = self.get_clickhouse_binary_from_docker_container(
                    self.clickhouse_binary_path
                )

        self.docker_compose += f' --ansi never --project-directory "{docker_compose_project_dir}" --file "{docker_compose_file_path}"'
        self.lock = threading.Lock()

    def get_clickhouse_binary_from_docker_container(
        self,
        docker_image,
        container_clickhouse_binary_path="/usr/bin/clickhouse",
        container_clickhouse_odbc_bridge_binary_path="/usr/bin/clickhouse-odbc-bridge",
        host_clickhouse_binary_path=None,
        host_clickhouse_odbc_bridge_binary_path=None,
    ):
        """Get clickhouse-server and clickhouse-odbc-bridge binaries
        from some Docker container.
        """
        docker_image = docker_image.split("docker://", 1)[-1]
        docker_container_name = str(uuid.uuid1())

        if host_clickhouse_binary_path is None:
            host_clickhouse_binary_path = os.path.join(
                tempfile.gettempdir(),
                f"{docker_image.rsplit('/', 1)[-1].replace(':', '_')}",
            )

        if host_clickhouse_odbc_bridge_binary_path is None:
            host_clickhouse_odbc_bridge_binary_path = (
                host_clickhouse_binary_path + "_odbc_bridge"
            )

        with Given(
            "I get ClickHouse server binary from docker container",
            description=f"{docker_image}",
        ):
            with Shell() as bash:
                bash.timeout = 300
                bash(
                    f'docker run -d --name "{docker_container_name}" {docker_image} | tee'
                )
                bash(
                    f'docker cp "{docker_container_name}:{container_clickhouse_binary_path}" "{host_clickhouse_binary_path}"'
                )
                bash(
                    f'docker cp "{docker_container_name}:{container_clickhouse_odbc_bridge_binary_path}" "{host_clickhouse_odbc_bridge_binary_path}"'
                )
                bash(f'docker stop "{docker_container_name}"')

        return host_clickhouse_binary_path, host_clickhouse_odbc_bridge_binary_path

    @property
    def control_shell(self, timeout=300):
        """Must be called with self.lock.acquired."""
        if self._control_shell is not None:
            return self._control_shell

        time_start = time.time()
        i = -1
        while True:
            i += 1
            with By(f"attempt #{i}"):
                try:
                    shell = Shell()
                    shell.timeout = 30
                    shell("echo 1")
                    break
                except IOError:
                    raise
                except Exception as exc:
                    shell.__exit__(None, None, None)
                    if time.time() - time_start > timeout:
                        raise RuntimeError(f"failed to open control shell")
        self._control_shell = shell
        return self._control_shell

    def close_control_shell(self):
        """Must be called with self.lock.acquired."""
        if self._control_shell is None:
            return
        shell = self._control_shell
        self._control_shell = None
        shell.__exit__(None, None, None)

    def node_container_id(self, node, timeout=300):
        """Must be called with self.lock acquired."""
        container_id = None
        time_start = time.time()
        i = -1
        while True:
            i += 1
            with By(f"attempt #{i}"):
                try:
                    c = self.control_shell(
                        f"{self.docker_compose} ps -q {node}", timeout=timeout
                    )
                    container_id = c.output.strip()
                    if c.exitcode == 0 and len(container_id) > 1:
                        break
                except IOError:
                    raise
                except ExpectTimeoutError:
                    self.close_control_shell()
                    timeout = timeout - (time.time() - time_start)
                    if timeout <= 0:
                        raise RuntimeError(
                            f"failed to get docker container id for the {node} service"
                        )
        return container_id

    def shell(self, node, timeout=300):
        """Returns unique shell terminal to be used."""
        container_id = None

        if node is not None:
            with self.lock:
                container_id = self.node_container_id(node=node, timeout=timeout)

        time_start = time.time()
        i = -1
        while True:
            i += 1
            with By(f"attempt #{i}"):
                try:
                    if node is None:
                        shell = Shell()
                    else:
                        shell = Shell(
                            command=[
                                "/bin/bash",
                                "--noediting",
                                "-c",
                                f"docker exec -it {container_id} bash --noediting",
                            ],
                            name=node,
                        )
                    shell.timeout = 30
                    shell("echo 1")
                    break
                except IOError:
                    raise
                except Exception as exc:
                    shell.__exit__(None, None, None)
                    if time.time() - time_start > timeout:
                        raise RuntimeError(f"failed to open bash to node {node}")

        shell.timeout = timeout
        return shell

    def bash(self, node, timeout=300, command="bash --noediting"):
        """Returns thread-local bash terminal
        to a specific node.
        :param node: name of the service
        """
        test = current()

        current_thread = threading.current_thread()
        id = (current_thread.name, f"{node}")

        with self.lock:
            if self._bash.get(id) is None:
                if node is not None:
                    container_id = self.node_container_id(node=node, timeout=timeout)

                time_start = time.time()
                i = -1
                while True:
                    i += 1
                    with By(f"attempt #{i}"):
                        try:
                            if node is None:
                                self._bash[id] = Shell()
                            else:
                                self._bash[id] = Shell(
                                    command=[
                                        "/bin/bash",
                                        "--noediting",
                                        "-c",
                                        f"docker exec -it {container_id} {command}",
                                    ],
                                    name=node,
                                ).__enter__()
                            self._bash[id].timeout = 30
                            self._bash[id]("echo 1")
                            break
                        except IOError:
                            raise
                        except Exception as exc:
                            self._bash[id].__exit__(None, None, None)
                            if time.time() - time_start > timeout:
                                raise RuntimeError(
                                    f"failed to open bash to node {node}"
                                )

                if node is None:
                    for name, value in self.environ.items():
                        self._bash[id](f"export {name}={value}")

                self._bash[id].timeout = timeout

                # clean up any stale open shells for threads that have exited
                active_thread_names = {thread.name for thread in threading.enumerate()}

                for bash_id in list(self._bash.keys()):
                    thread_name, node_name = bash_id
                    if thread_name not in active_thread_names:
                        self._bash[bash_id].__exit__(None, None, None)
                        del self._bash[bash_id]

            return self._bash[id]

    def close_bash(self, node):
        current_thread = threading.current_thread()
        id = (current_thread.name, f"{node}")

        with self.lock:
            if self._bash.get(id) is None:
                return
            self._bash[id].__exit__(None, None, None)
            del self._bash[id]

    def __enter__(self):
        with Given("docker-compose cluster"):
            self.up()
        return self

    def __exit__(self, type, value, traceback):
        try:
            with Finally("I clean up"):
                if self.collect_service_logs:
                    with Finally("collect service logs"):
                        with Shell() as bash:
                            bash(f"cd {self.docker_compose_project_dir}", timeout=1000)
                            for service_list in self.nodes:
                                for service_node in self.nodes[service_list]:
                                    with By(f"getting log for {service_node}"):
                                        log_path = f"../../logs/"
                                        snode = bash(
                                            f"docker-compose logs {service_node} "
                                            f"> {log_path}{service_node}.log",
                                            timeout=1000,
                                        )
                                        if snode.exitcode != 0:
                                            break
                self.down()
        finally:
            with self.lock:
                for shell in self._bash.values():
                    shell.__exit__(type, value, traceback)

    def node(self, name):
        """Get object with node bound methods.
        :param name: name of service name
        """
        if name.startswith("clickhouse-sink"):
            return SinkConnector(self, name)
        if name.startswith("clickhouse"):
            return ClickHouseNode(self, name, data_base_type="clickhouse")
        if name.startswith("mysql"):
            return MySQLNode(self, name, data_base_type="mysql")
        return Node(self, name)

    def down(self, timeout=300):
        """Bring cluster down by executing docker-compose down."""

        # add message to each clickhouse-server.log
        if settings.debug and self.running:
            for node in self.nodes["clickhouse"]:
                self.command(
                    node=node,
                    command=f'echo -e "\n-- sending stop to: {node} --\n" >> /var/log/clickhouse-server/clickhouse-server.log',
                )
        try:
            bash = self.bash(None)
            with self.lock:
                # remove and close all not None node terminals
                for id in list(self._bash.keys()):
                    shell = self._bash.pop(id)
                    if shell is not bash:
                        shell.__exit__(None, None, None)
                    else:
                        self._bash[id] = shell
        finally:
            cmd = self.command(
                None,
                f"{self.docker_compose} down -v --remove-orphans --timeout 60",
                bash=bash,
                timeout=timeout,
            )
            with self.lock:
                if self._control_shell:
                    self._control_shell.__exit__(None, None, None)
                    self._control_shell = None
            return cmd

    def temp_path(self):
        """Return temporary folder path."""
        p = f"{self.environ['CLICKHOUSE_TESTS_DIR']}/_temp"
        if not os.path.exists(p):
            os.mkdir(p)
        return p

    def temp_file(self, name):
        """Return absolute temporary file path."""
        return f"{os.path.join(self.temp_path(), name)}"

    def up(self, timeout=30 * 60):
        """Bring cluster up."""
        if self.local:
            with Given("I am running in local mode"):
                with Then("check --clickhouse-binary-path is specified"):
                    assert (
                        self.clickhouse_binary_path
                    ), "when running in local mode then --clickhouse-binary-path must be specified"
                with And("path should exist"):
                    assert os.path.exists(self.clickhouse_binary_path)

            with And("I set all the necessary environment variables"):
                self.environ["IMAGE_DEPENDENCY_PROXY"] = os.getenv(
                    "IMAGE_DEPENDENCY_PROXY", ""
                )
                self.environ["COMPOSE_HTTP_TIMEOUT"] = "300"
                self.environ[
                    "CLICKHOUSE_TESTS_SERVER_BIN_PATH"
                ] = self.clickhouse_binary_path
                self.environ[
                    "CLICKHOUSE_TESTS_ODBC_BRIDGE_BIN_PATH"
                ] = self.clickhouse_odbc_bridge_binary_path or os.path.join(
                    os.path.dirname(self.clickhouse_binary_path),
                    "clickhouse-odbc-bridge",
                )
                self.environ["CLICKHOUSE_TESTS_DIR"] = self.configs_dir

            with And("I list environment variables to show their values"):
                self.command(None, "env | grep CLICKHOUSE")

        with Given("docker-compose"):
            max_attempts = 5
            max_up_attempts = 1

            for attempt in range(max_attempts):
                with When(f"attempt {attempt}/{max_attempts}"):
                    with By("pulling images for all the services"):
                        cmd = self.command(
                            None,
                            f"{self.docker_compose} pull 2>&1 | tee",
                            exitcode=None,
                            timeout=timeout,
                        )
                        if cmd.exitcode != 0:
                            continue

                    with And("checking if any containers are already running"):
                        self.command(None, f"{self.docker_compose} ps | tee")

                    with And("executing docker-compose down just in case it is up"):
                        cmd = self.command(
                            None,
                            f"{self.docker_compose} down 2>&1 | tee",
                            exitcode=None,
                            timeout=timeout,
                        )
                        if cmd.exitcode != 0:
                            continue

                    with And("checking if any containers are still left running"):
                        self.command(None, f"{self.docker_compose} ps | tee")

                    with And("executing docker-compose up"):
                        for up_attempt in range(max_up_attempts):
                            with By(f"attempt {up_attempt}/{max_up_attempts}"):
                                cmd = self.command(
                                    None,
                                    f"{self.docker_compose} up --renew-anon-volumes --force-recreate --timeout 300 -d 2>&1 | tee",
                                    timeout=timeout,
                                )
                                if "is unhealthy" not in cmd.output:
                                    break

                    with Then("check there are no unhealthy containers"):
                        ps_cmd = self.command(
                            None, f'{self.docker_compose} ps | tee | grep -v "Exit 0"'
                        )
                        if "is unhealthy" in cmd.output or "Exit" in ps_cmd.output:
                            self.command(None, f"{self.docker_compose} logs | tee")
                            continue

                    if (
                        cmd.exitcode == 0
                        and "is unhealthy" not in cmd.output
                        and "Exit" not in ps_cmd.output
                    ):
                        break

            if (
                cmd.exitcode != 0
                or "is unhealthy" in cmd.output
                or "Exit" in ps_cmd.output
            ):
                fail("could not bring up docker-compose cluster")

        with Then("wait all nodes report healthy"):
            for name in self.nodes["clickhouse"]:
                self.node(name).wait_healthy()
                if name.startswith("clickhouse"):
                    self.node(name).start_clickhouse(thread_fuzzer=self.thread_fuzzer)

        self.running = True

    def command(
        self,
        node,
        command,
        message=None,
        exitcode=None,
        steps=True,
        bash=None,
        no_checks=False,
        use_error=True,
        *args,
        **kwargs,
    ):
        """Execute and check command.
        :param node: name of the service
        :param command: command
        :param message: expected message that should be in the output, default: None
        :param exitcode: expected exitcode, default: None
        :param steps: don't break command into steps, default: True
        """
        with (
            By("executing command", description=command, format_description=False)
            if steps
            else NullStep()
        ):
            if bash is None:
                bash = self.bash(node)
            try:
                r = bash(command, *args, **kwargs)
            except ExpectTimeoutError:
                self.close_bash(node)
                raise

        if no_checks:
            return r

        if exitcode is not None:
            with (
                Then(f"exitcode should be {exitcode}", format_name=False)
                if steps
                else NullStep()
            ):
                assert r.exitcode == exitcode, error(r.output)

        if message is not None:
            with (
                Then(
                    f"output should contain message",
                    description=message,
                    format_description=False,
                )
                if steps
                else NullStep()
            ):
                assert message in r.output, error(r.output)

        return r


class DatabaseNode(Node):
    """Common tools for Database nodes."""

    def query(
        self,
        sql,
        client_command,
        message=None,
        exitcode=None,
        steps=True,
        no_checks=False,
        raise_on_exception=False,
        step=By,
        settings=None,
        retry_count=5,
        messages_to_retry=None,
        retry_delay=5,
        secure=False,
        max_query_output_in_bytes="-0",
        *args,
        **kwargs,
    ):
        """Execute and check query.
        :param sql: sql query
        :param message: expected message that should be in the output, default: None
        :param exitcode: expected exitcode, default: None
        :param steps: wrap query execution in a step, default: True
        :param no_check: disable exitcode and message checks, default: False
        :param step: wrapping step class, default: By
        :param settings: list of settings to be used for the query in the form [(name, value),...], default: None
        :param retry_count: number of retries, default: 5
        :param messages_to_retry: list of messages in the query output for
               which retry should be triggered, default: MESSAGES_TO_RETRY
        :param retry_delay: number of seconds to sleep before retry, default: 5
        :param secure: use secure connection, default: False
        :param max_query_output_in_bytes: truncate query output the specified number of bytes using 'head' command utility, default: -0 (do not truncate any output)
        """
        retry_count = max(0, int(retry_count))
        retry_delay = max(0, float(retry_delay))
        settings = list(settings or [])
        query_settings = list(settings)

        if raise_on_exception:
            steps = False

        if messages_to_retry is None:
            messages_to_retry = MESSAGES_TO_RETRY

        if hasattr(current().context, "default_query_settings"):
            query_settings += current().context.default_query_settings

        if len(sql) > 1024:
            with tempfile.NamedTemporaryFile("w", encoding="utf-8") as query:
                query.write(sql)
                query.flush()

                client_options = ""
                for setting in query_settings:
                    name, value = setting
                    client_options += f' --{name} "{value}"'

                if max_query_output_in_bytes != "-0":
                    command = f'cat "{query.name}" | {self.cluster.docker_compose} exec -T {self.name} bash -c "(set -o pipefail && {client_command}{client_options} 2>&1 | head -c {max_query_output_in_bytes})"'
                else:
                    command = f'cat "{query.name}" | {self.cluster.docker_compose} exec -T {self.name} bash -c "{client_command}{client_options} 2>&1"'

                description = f"""
                    echo -e \"{sql[:100]}...\" > {query.name}
                    {command}
                """
                with (
                    step(
                        "executing command",
                        description=description,
                        format_description=False,
                    )
                    if steps
                    else NullStep()
                ):
                    try:
                        r = self.cluster.bash(None)(command, *args, **kwargs)
                        time.sleep(0.5)
                    except ExpectTimeoutError:
                        self.cluster.close_bash(None)
                        raise
        else:
            client_options = ""
            for setting in query_settings:
                name, value = setting
                client_options += f' --{name} "{value}"'

            if max_query_output_in_bytes != "-0":
                command = f'(set -o pipefail && echo -e "{sql}" | {client_command}{client_options} 2>&1 | head -c {max_query_output_in_bytes})'
            else:
                command = f'echo -e "{sql}" | {client_command}{client_options} 2>&1'

            with (
                step("executing command", description=command, format_description=False)
                if steps
                else NullStep()
            ):
                try:
                    r = self.cluster.bash(self.name)(command, *args, **kwargs)
                    time.sleep(0.5)
                except ExpectTimeoutError:
                    self.cluster.close_bash(self.name)
                    raise

        if retry_count and retry_count > 0:
            if any(msg in r.output for msg in messages_to_retry):
                time.sleep(retry_delay)
                return self.query(
                    sql=sql,
                    message=message,
                    exitcode=exitcode,
                    steps=steps,
                    no_checks=no_checks,
                    raise_on_exception=raise_on_exception,
                    step=step,
                    settings=settings,
                    retry_count=retry_count - 1,
                    messages_to_retry=messages_to_retry,
                    *args,
                    **kwargs,
                )

        if no_checks:
            return r

        if exitcode is not None:
            with Then(f"exitcode should be {exitcode}") if steps else NullStep():
                assert r.exitcode == exitcode, error(r.output)

        if message is not None:
            with (
                Then(f"output should contain message", description=message)
                if steps
                else NullStep()
            ):
                assert message in r.output, error(r.output)

        if message is None or "Exception:" not in message:
            with Then("check if output has exception") if steps else NullStep():
                if "Exception:" in r.output:
                    if raise_on_exception:
                        raise QueryRuntimeException(r.output)
                    assert False, error(r.output)
                elif "ERROR" in r.output:
                    if raise_on_exception:
                        raise QueryRuntimeException(r.output)
                    assert False, error(r.output)

        return r


class MySQLNode(DatabaseNode):
    """MySQL server node."""

    def query(
        self,
        sql,
        message=None,
        exitcode=None,
        steps=True,
        no_checks=False,
        raise_on_exception=False,
        step=By,
        settings=None,
        retry_count=5,
        messages_to_retry=None,
        retry_delay=5,
        max_query_output_in_bytes="-0",
        client_command=None,
        *args,
        **kwargs,
    ):
        """Execute and check query.
        :param sql: sql query
        :param message: expected message that should be in the output, default: None
        :param exitcode: expected exitcode, default: None
        :param steps: wrap query execution in a step, default: True
        :param no_check: disable exitcode and message checks, default: False
        :param step: wrapping step class, default: By
        :param settings: list of settings to be used for the query in the form [(name, value),...], default: None
        :param retry_count: number of retries, default: 5
        :param messages_to_retry: list of messages in the query output for
               which retry should be triggered, default: MESSAGES_TO_RETRY
        :param retry_delay: number of seconds to sleep before retry, default: 5
        :param max_query_output_in_bytes: truncate query output the specified number of bytes using 'head' command utility, default: -0 (do not truncate any output)
        :param client_command: database client command, default: None (use default)
        """
        if client_command is None:
            client_command = (
                "mariadb --user=root --password=root --host=mysql-master test"
            )

        return super(MySQLNode, self).query(
            sql=sql,
            client_command=client_command,
            message=message,
            exitcode=exitcode,
            steps=steps,
            no_checks=no_checks,
            step=step,
            settings=settings,
            retry_count=retry_count,
            messages_to_retry=messages_to_retry,
            retry_delay=retry_delay,
            max_query_output_in_bytes=max_query_output_in_bytes,
        )


class SinkConnector(DatabaseNode):
    """ClickHouse Sink Connector node."""

    sink_connector_cli = "./sink-connector-client"

    def __init__(self, cluster, name):
        """Install procps package on the node to have all the basic system tools."""
        super().__init__(cluster, name)
        self.command(command="microdnf install procps")

    @staticmethod
    def parse_value(input_string):
        """Parse the output value to get the PID of the ClickHouse Sink Connector."""
        match = re.search(r"\[\d+\]\s*(\d+)", str(input_string))
        if match:
            return int(match.group(1))
        else:
            raise ValueError(f"Failed to parse value from string: {input_string}")

    def start_sink_connector(self, timeout=300, config_file=None):
        """Start ClickHouse Sink Connector."""
        if config_file is None:
            config_file = "config.yml"

        config_file = config_file.rsplit("/", 1)[-1]

        with Given("I start ClickHouse Sink Connector"):
            start_command = self.command(
                command=f"java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar /app.jar /configs/{config_file} com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication > /logs/sink-connector-lt.log 2>&1 &",
                exitcode=0,
                timeout=timeout,
            )
            sink_connector_pid = self.parse_value(start_command.output.strip())

        with And("I save the PID of the ClickHouse Sink Connector into a file"):
            self.command(
                command=f"echo {sink_connector_pid} > /tmp/sink_connector.pid",
                exitcode=0,
                timeout=timeout,
            )

    def sink_connector_pid(self):
        """Return ClickHouse Sink Connector pid if present
        otherwise return None.
        """
        if self.command("ls /tmp/sink_connector.pid").exitcode == 0:
            return self.command("cat /tmp/sink_connector.pid").output.strip()
        return None

    def stop_sink_connector(self, timeout=300):
        """Stop ClickHouse Sink Connector."""
        with By(f"sending kill -TERM to ClickHouse server process on {self.name}"):
            pid = self.sink_connector_pid()
            self.command(f"kill -TERM {pid}", exitcode=0, steps=False)

        with And("checking that pid does not exist"):
            for i, attempt in enumerate(retries(timeout=100, delay=3)):
                with attempt:
                    if i > 0 and i % 20 == 0:
                        self.command(f"kill -KILL {pid}", steps=False)
                    if (
                        self.command(f"ps {pid}", steps=False, no_checks=True).exitcode
                        != 1
                    ):
                        fail(f"pid {pid} still alive")

        with And("deleting ClickHouse server pid file"):
            self.command("rm -rf /tmp/clickhouse-server.pid", exitcode=0, steps=False)

    def restart_sink_connector(self, timeout=300, config_file=None):
        """Restart ClickHouse Sink Connector."""
        if self.sink_connector_pid():
            self.stop_sink_connector(timeout=timeout)

        self.start_sink_connector(timeout=timeout, config_file=config_file)

    def stop_replication(self, timeout=300):
        """Stop ClickHouse Sink Connector replication using sink-connector-client script."""
        with Given("I stop ClickHouse Sink Connector replication"):
            self.command(
                command=f"{self.sink_connector_cli} stop_replica",
                timeout=timeout,
            )

    def start_replication(self):
        """Start ClickHouse Sink Connector replication using sink-connector-client script."""
        with Given("I start ClickHouse Sink Connector replication"):
            self.command(
                command=f"{self.sink_connector_cli} start_replica",
                timeout=300,
            )

    def change_replica_source(self):
        """Change ClickHouse Sink Connector replica source using sink-connector-client script."""
        with Given("I change ClickHouse Sink Connector replica source"):
            self.command(
                command=f"{self.sink_connector_cli} change_replica_source",
                timeout=300,
            )

    def show_replication_status(self):
        """Show ClickHouse Sink Connector replication status using sink-connector-client script."""
        with Given("I change ClickHouse Sink Connector replica source"):
            self.command(
                command=f"{self.sink_connector_cli} show_replica_status",
                timeout=300,
            )

    def get_container_id(self):
        """Get the container ID of the ClickHouse Sink Connector node."""
        with Given("I get the container ID of the ClickHouse Sink Connector"):
            container_id = self.cluster.node_container_id(self.name)
            return container_id


class ClickHouseNode(DatabaseNode):
    """Node with ClickHouse server."""

    def enable_thread_fuzzer(self):
        with Given("enabling THREAD_FUZZER"):
            self.command("export THREAD_FUZZER_CPU_TIME_PERIOD_US=1000")
            self.command("export THREAD_FUZZER_SLEEP_PROBABILITY=0.1")
            self.command("export THREAD_FUZZER_SLEEP_TIME_US=100000")

            self.command(
                "export THREAD_FUZZER_pthread_mutex_lock_BEFORE_MIGRATE_PROBABILITY=1"
            )
            self.command(
                "export THREAD_FUZZER_pthread_mutex_lock_AFTER_MIGRATE_PROBABILITY=1"
            )
            self.command(
                "export THREAD_FUZZER_pthread_mutex_unlock_BEFORE_MIGRATE_PROBABILITY=1"
            )
            self.command(
                "export THREAD_FUZZER_pthread_mutex_unlock_AFTER_MIGRATE_PROBABILITY=1"
            )

            self.command(
                "export THREAD_FUZZER_pthread_mutex_lock_BEFORE_SLEEP_PROBABILITY=0.001"
            )
            self.command(
                "export THREAD_FUZZER_pthread_mutex_lock_AFTER_SLEEP_PROBABILITY=0.001"
            )
            self.command(
                "export THREAD_FUZZER_pthread_mutex_unlock_BEFORE_SLEEP_PROBABILITY=0.001"
            )
            self.command(
                "export THREAD_FUZZER_pthread_mutex_unlock_AFTER_SLEEP_PROBABILITY=0.001"
            )
            self.command(
                "export THREAD_FUZZER_pthread_mutex_lock_BEFORE_SLEEP_TIME_US=10000"
            )
            self.command(
                "export THREAD_FUZZER_pthread_mutex_lock_AFTER_SLEEP_TIME_US=10000"
            )
            self.command(
                "export THREAD_FUZZER_pthread_mutex_unlock_BEFORE_SLEEP_TIME_US=10000"
            )
            self.command(
                "export THREAD_FUZZER_pthread_mutex_unlock_AFTER_SLEEP_TIME_US=10000"
            )

    def wait_clickhouse_healthy(self, timeout=300):
        with By(f"waiting until ClickHouse server on {self.name} is healthy"):
            for attempt in retries(timeout=timeout, delay=1):
                with attempt:
                    if (
                        self.query(
                            "SELECT version()", no_checks=1, steps=False
                        ).exitcode
                        != 0
                    ):
                        fail("ClickHouse server is not healthy")
            node_version = self.query(
                "SELECT version()", no_checks=1, steps=False
            ).output
            if current().context.clickhouse_version is None:
                current().context.clickhouse_version = node_version
            else:
                assert check_clickhouse_version(f"={node_version}")(current()), error()

    def clickhouse_pid(self):
        """Return ClickHouse server pid if present
        otherwise return None.
        """
        if self.command("ls /tmp/clickhouse-server.pid").exitcode == 0:
            return self.command("cat /tmp/clickhouse-server.pid").output.strip()
        return None

    def stop_clickhouse(self, timeout=300, safe=True):
        """Stop ClickHouse server."""
        if safe:
            self.query("SYSTEM STOP MOVES")
            self.query("SYSTEM STOP MERGES")
            self.query("SYSTEM FLUSH LOGS")
            with By("waiting for 5 sec for moves and merges to stop"):
                time.sleep(5)
            with And("forcing to sync everything to disk"):
                self.command("sync", timeout=300, exitcode=0)

        with By(f"sending kill -TERM to ClickHouse server process on {self.name}"):
            pid = self.clickhouse_pid()
            self.command(f"kill -TERM {pid}", exitcode=0, steps=False)

        with And("checking pid does not exist"):
            for i, attempt in enumerate(retries(timeout=100, delay=3)):
                with attempt:
                    if i > 0 and i % 20 == 0:
                        self.command(f"kill -KILL {pid}", steps=False)
                    if (
                        self.command(f"ps {pid}", steps=False, no_checks=True).exitcode
                        != 1
                    ):
                        fail("pid still alive")

        with And("deleting ClickHouse server pid file"):
            self.command("rm -rf /tmp/clickhouse-server.pid", exitcode=0, steps=False)

    def start_clickhouse(
        self,
        timeout=300,
        wait_healthy=True,
        retry_count=5,
        user=None,
        thread_fuzzer=False,
    ):
        """Start ClickHouse server."""
        pid = self.clickhouse_pid()
        if pid:
            raise RuntimeError(f"ClickHouse server already running with pid {pid}")

        if thread_fuzzer:
            self.enable_thread_fuzzer()

        if user is None:
            with By("starting ClickHouse server process"):
                self.command(
                    "clickhouse server --config-file=/etc/clickhouse-server/config.xml"
                    " --log-file=/var/log/clickhouse-server/clickhouse-server.log"
                    " --errorlog-file=/var/log/clickhouse-server/clickhouse-server.err.log"
                    " --pidfile=/tmp/clickhouse-server.pid --daemon",
                    exitcode=0,
                    steps=False,
                )
        else:
            with By(f"starting ClickHouse server process from {user}"):
                self.command(
                    f"su {user} -c"
                    '"clickhouse server --config-file=/etc/clickhouse-server/config.xml'
                    " --log-file=/var/log/clickhouse-server/clickhouse-server.log"
                    " --errorlog-file=/var/log/clickhouse-server/clickhouse-server.err.log"
                    ' --pidfile=/tmp/clickhouse-server.pid --daemon"',
                    exitcode=0,
                    steps=False,
                )

        with And("checking that ClickHouse server pid file was created"):
            for attempt in retries(timeout=timeout, delay=1):
                with attempt:
                    if (
                        self.command(
                            "ls /tmp/clickhouse-server.pid", steps=False, no_checks=True
                        ).exitcode
                        != 0
                    ):
                        fail("no pid file yet")

        if wait_healthy:
            self.wait_clickhouse_healthy(timeout=timeout)

    def restart_clickhouse(
        self, timeout=300, safe=True, wait_healthy=True, retry_count=5, user=None
    ):
        """Restart ClickHouse server."""
        if self.clickhouse_pid():
            self.stop_clickhouse(timeout=timeout, safe=safe)

        self.start_clickhouse(timeout=timeout, wait_healthy=wait_healthy, user=user)

    def stop(self, timeout=300, safe=True, retry_count=5):
        """Stop node."""
        if self.clickhouse_pid():
            self.stop_clickhouse(timeout=timeout, safe=safe)

        return super(ClickHouseNode, self).stop(
            timeout=timeout, retry_count=retry_count
        )

    def start(
        self,
        timeout=300,
        start_clickhouse=True,
        wait_healthy=True,
        retry_count=5,
        user=None,
    ):
        """Start node."""
        super(ClickHouseNode, self).start(timeout=timeout, retry_count=retry_count)

        if start_clickhouse:
            self.start_clickhouse(
                timeout=timeout,
                wait_healthy=wait_healthy,
                user=user,
            )

    def restart(
        self,
        timeout=300,
        safe=True,
        start_clickhouse=True,
        wait_healthy=True,
        retry_count=5,
        user=None,
    ):
        """Restart node."""
        if self.clickhouse_pid():
            self.stop_clickhouse(timeout=timeout, safe=safe)

        super(ClickHouseNode, self).restart(timeout=timeout, retry_count=retry_count)

        if start_clickhouse:
            self.start_clickhouse(timeout=timeout, wait_healthy=wait_healthy, user=user)

    def hash_query(
        self,
        sql,
        hash_utility="sha1sum",
        steps=True,
        step=By,
        settings=None,
        secure=False,
        *args,
        **kwargs,
    ):
        """Execute sql query inside the container and return the hash of the output.

        :param sql: sql query
        :param hash_utility: hash function which used to compute hash
        """
        settings = list(settings or [])
        query_settings = list(settings)

        if hasattr(current().context, "default_query_settings"):
            query_settings += current().context.default_query_settings

        client = "clickhouse client -n -u root --password root"
        if secure:
            client += " -s"

        if len(sql) > 1024:
            with tempfile.NamedTemporaryFile("w", encoding="utf-8") as query:
                query.write(sql)
                query.flush()
                command = f'set -o pipefail && cat "{query.name}" | {self.cluster.docker_compose} exec -T {self.name} {client} | {hash_utility}'
                for setting in query_settings:
                    name, value = setting
                    command += f' --{name} "{value}"'
                description = f"""
                            echo -e \"{sql[:100]}...\" > {query.name}
                            {command}
                        """
                with (
                    step(
                        "executing command",
                        description=description,
                        format_description=False,
                    )
                    if steps
                    else NullStep()
                ):
                    try:
                        r = self.cluster.bash(None)(command, *args, **kwargs)
                    except ExpectTimeoutError:
                        self.cluster.close_bash(None)
        else:
            command = f'set -o pipefail && echo -e "{sql}" | {client} | {hash_utility}'
            for setting in query_settings:
                name, value = setting
                command += f' --{name} "{value}"'
            with (
                step("executing command", description=command, format_description=False)
                if steps
                else NullStep()
            ):
                try:
                    r = self.cluster.bash(self.name)(command, *args, **kwargs)
                except ExpectTimeoutError:
                    self.cluster.close_bash(self.name)

        with Then(f"exitcode should be 0") if steps else NullStep():
            assert r.exitcode == 0, error(r.output)

        return r.output

    def diff_query(
        self,
        sql,
        expected_output,
        steps=True,
        step=By,
        settings=None,
        secure=False,
        *args,
        **kwargs,
    ):
        """Execute inside the container but from the host and compare its output
        to file that is located on the host.

        For example:
            diff <(echo "SELECT * FROM myints FORMAT CSVWithNames" | clickhouse-client -mn) select.out

        :param sql: sql query
        :param expected_output: path to the expected output
        """
        settings = list(settings or [])
        query_settings = list(settings)

        if hasattr(current().context, "default_query_settings"):
            query_settings += current().context.default_query_settings

        client = "clickhouse client -n -u root --password root"
        if secure:
            client += " -s"

        if len(sql) > 1024:
            with tempfile.NamedTemporaryFile("w", encoding="utf-8") as query:
                query.write(sql)
                query.flush()
                command = f'diff <(cat "{query.name}" | {self.cluster.docker_compose} exec -T {self.name} {client}) {expected_output}'
                for setting in query_settings:
                    name, value = setting
                    command += f' --{name} "{value}"'
                description = f"""
                    echo -e \"{sql[:100]}...\" > {query.name}
                    {command}
                """
                with (
                    step(
                        "executing command",
                        description=description,
                        format_description=False,
                    )
                    if steps
                    else NullStep()
                ):
                    try:
                        r = self.cluster.bash(None)(command, *args, **kwargs)
                    except ExpectTimeoutError:
                        self.cluster.close_bash(None)
        else:
            command = f'diff <(echo -e "{sql}" | {self.cluster.docker_compose} exec -T {self.name} {client}) {expected_output}'
            for setting in query_settings:
                name, value = setting
                command += f' --{name} "{value}"'
            with (
                step("executing command", description=command, format_description=False)
                if steps
                else NullStep()
            ):
                try:
                    r = self.cluster.bash(None)(command, *args, **kwargs)
                except ExpectTimeoutError:
                    self.cluster.close_bash(None)

        with Then(f"exitcode should be 0") if steps else NullStep():
            assert r.exitcode == 0, error(r.output)

    def query(
        self,
        sql,
        message=None,
        exitcode=None,
        steps=True,
        no_checks=False,
        raise_on_exception=False,
        step=By,
        settings=None,
        retry_count=5,
        messages_to_retry=None,
        retry_delay=5,
        max_query_output_in_bytes="-0",
        client_command=None,
        secure=False,
        *args,
        **kwargs,
    ):
        """Execute and check query.
        :param sql: sql query
        :param message: expected message that should be in the output, default: None
        :param exitcode: expected exitcode, default: None
        :param steps: wrap query execution in a step, default: True
        :param no_check: disable exitcode and message checks, default: False
        :param step: wrapping step class, default: By
        :param settings: list of settings to be used for the query in the form [(name, value),...], default: None
        :param retry_count: number of retries, default: 5
        :param messages_to_retry: list of messages in the query output for
               which retry should be triggered, default: MESSAGES_TO_RETRY
        :param retry_delay: number of seconds to sleep before retry, default: 5
        :param secure: use secure connection, default: False
        :param max_query_output_in_bytes: truncate query output the specified number of bytes using 'head' command utility, default: -0 (do not truncate any output)
        :param client_command: database client command, default: None (use default)
        """
        if client_command is None:
            client_command = "clickhouse client -n -u root --password root"
            if secure:
                client_command += " -s"

        return super(ClickHouseNode, self).query(
            sql=sql,
            client_command=client_command,
            message=message,
            exitcode=exitcode,
            steps=steps,
            no_checks=no_checks,
            step=step,
            settings=settings,
            retry_count=retry_count,
            messages_to_retry=messages_to_retry,
            retry_delay=retry_delay,
            secure=secure,
            max_query_output_in_bytes=max_query_output_in_bytes,
        )
