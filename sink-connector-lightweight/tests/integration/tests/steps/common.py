import random
from datetime import datetime, timedelta
from testflows.core import *


def generate_sample_mysql_value(data_type):
    """Generate a sample MySQL value for the provided datatype."""
    if data_type.startswith("DECIMAL"):
        precision, scale = map(
            int, data_type[data_type.index("(") + 1 : data_type.index(")")].split(",")
        )
        number = round(
            random.uniform(-(10 ** (precision - scale)), 10 ** (precision - scale)),
            scale,
        )
        return str(number)
    elif data_type.startswith("DOUBLE"):
        # Adjusting the range to avoid overflow, staying within a reasonable limit
        return str(random.uniform(-1.7e308, 1.7e308))
    elif data_type == "DATE NOT NULL":
        return (datetime.today() - timedelta(days=random.randint(1, 365))).strftime(
            "%Y-%m-%d"
        )
    elif data_type.startswith("DATETIME"):
        return (datetime.now() - timedelta(days=random.randint(1, 365))).strftime(
            "%Y-%m-%d %H:%M:%S.%f"
        )[:19]
    elif data_type.startswith("TIME"):
        if "6" in data_type:
            return (datetime.now()).strftime("%H:%M:%S.%f")[
                : 8 + 3
            ]  # Including microseconds
        else:
            return (datetime.now()).strftime("%H:%M:%S")
    elif "INT" in data_type:
        if "TINYINT" in data_type:
            return str(
                random.randint(0, 255)
                if "UNSIGNED" in data_type
                else random.randint(-128, 127)
            )
        elif "SMALLINT" in data_type:
            return str(
                random.randint(0, 65535)
                if "UNSIGNED" in data_type
                else random.randint(-32768, 32767)
            )
        elif "MEDIUMINT" in data_type:
            return str(
                random.randint(0, 16777215)
                if "UNSIGNED" in data_type
                else random.randint(-8388608, 8388607)
            )
        elif "BIGINT" in data_type:
            return str(
                random.randint(0, 2**63 - 1)
                if "UNSIGNED" in data_type
                else random.randint(-(2**63), 2**63 - 1)
            )
        else:  # INT
            return str(
                random.randint(0, 4294967295)
                if "UNSIGNED" in data_type
                else random.randint(-2147483648, 2147483647)
            )
    elif (
        data_type.startswith("CHAR")
        or data_type.startswith("VARCHAR")
        or data_type == "TEXT NOT NULL"
    ):
        return "SampleText"
    elif data_type.endswith("BLOB NOT NULL"):
        return "SampleBinaryData"
    elif data_type.startswith("BINARY") or data_type.startswith("VARBINARY"):
        return "BinaryData"
    else:
        return "UnknownType"
