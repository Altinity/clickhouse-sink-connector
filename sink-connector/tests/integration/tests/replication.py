from testflows.core import *


@TestScenario
def auto_creation(self):
    """Check that tables created on the source database are replicated on the destination."""
    pass


@TestScenario
def alters(self):
    """Check that alter statements performed on the source are replicated to the destination."""
    pass


@TestScenario
def inserts(self):
    """Check that inserts are replicated to the destination."""
    pass


@TestScenario
def deletes(self):
    """Check that deletes are replicated to the destination."""
    pass


@TestScenario
def updates(self):
    """Check that updates are replicated to the destination."""
    pass


@TestFeature
@Name("replication")
def feature(self):
    """Check that replication works"""
    pass
