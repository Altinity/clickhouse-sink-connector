all_nullable_mysql_datatypes = (
    f"D4 DECIMAL(2,1), D5 DECIMAL(30, 10),"
    f" Doublex DOUBLE,"
    f" x_date DATE,"
    f"x_datetime6 DATETIME(6),"
    f"x_time TIME,"
    f"x_time6 TIME(6),"
    f"Intmin INT, Intmax INT,"
    f"UIntmin INT UNSIGNED, UIntmax INT UNSIGNED,"
    f"BIGIntmin BIGINT,BIGIntmax BIGINT,"
    f"UBIGIntmin BIGINT UNSIGNED,UBIGIntmax BIGINT UNSIGNED,"
    f"TIntmin TINYINT,TIntmax TINYINT,"
    f"UTIntmin TINYINT UNSIGNED,UTIntmax TINYINT UNSIGNED,"
    f"SIntmin SMALLINT,SIntmax SMALLINT,"
    f"USIntmin SMALLINT UNSIGNED,USIntmax SMALLINT UNSIGNED,"
    f"MIntmin MEDIUMINT,MIntmax MEDIUMINT,"
    f"UMIntmin MEDIUMINT UNSIGNED,UMIntmax MEDIUMINT UNSIGNED,"
    f" x_char CHAR,"
    f" x_text TEXT,"
    f" x_varchar VARCHAR(4),"
    f" x_Blob BLOB,"
    f" x_Mediumblob MEDIUMBLOB,"
    f" x_Longblob LONGBLOB,"
    f" x_binary BINARY,"
    f" x_varbinary VARBINARY(4)"
)

all_nullable_ch_datatypes = (
    f" D4 Nullable(DECIMAL(2,1)), D5 Nullable(DECIMAL(30, 10)),"
    f" Doublex Nullable(Float64),"
    f" x_date Nullable(Date),"
    f" x_datetime6 Nullable(String),"
    f" x_time Nullable(String),"
    f" x_time6 Nullable(String),"
    f" Intmin Nullable(Int32), Intmax Nullable(Int32),"
    f" UIntmin Nullable(UInt32), UIntmax Nullable(UInt32),"
    f" BIGIntmin Nullable(UInt64), BIGIntmax Nullable(UInt64),"
    f" UBIGIntmin Nullable(UInt64), UBIGIntmax Nullable(UInt64),"
    f" TIntmin Nullable(Int8), TIntmax Nullable(Int8),"
    f" UTIntmin Nullable(UInt8), UTIntmax Nullable(UInt8),"
    f" SIntmin Nullable(Int16), SIntmax Nullable(Int16),"
    f" USIntmin Nullable(UInt16), USIntmax Nullable(UInt16),"
    f" MIntmin Nullable(Int32), MIntmax Nullable(Int32),"
    f" UMIntmin Nullable(UInt32), UMIntmax Nullable(UInt32),"
    f" x_char LowCardinality(Nullable(String)),"
    f" x_text Nullable(String),"
    f" x_varchar Nullable(String),"
    f" x_Blob Nullable(String),"
    f" x_Mediumblob Nullable(String),"
    f" x_Longblob Nullable(String),"
    f" x_binary Nullable(String),"
    f" x_varbinary Nullable(String)"
)

all_mysql_datatypes_dict = {
    "D4": "DECIMAL(2,1) NOT NULL",
    "D5": "DECIMAL(30, 10) NOT NULL",
    "Doublex": "DOUBLE NOT NULL",
    "x_date": "DATE NOT NULL",
    "x_datetime6": "DATETIME(6) NOT NULL",
    "x_time": "TIME NOT NULL",
    "x_time6": "TIME(6) NOT NULL",
    "Intmin": "INT NOT NULL",
    "Intmax": "INT NOT NULL",
    "UIntmin": "INT UNSIGNED NOT NULL",
    "UIntmax": "INT UNSIGNED NOT NULL",
    "BIGIntmin": "BIGINT NOT NULL",
    "BIGIntmax": "BIGINT NOT NULL",
    "UBIGIntmin": "BIGINT UNSIGNED NOT NULL",
    "UBIGIntmax": "BIGINT UNSIGNED NOT NULL",
    "TIntmin": "TINYINT NOT NULL",
    "TIntmax": "TINYINT NOT NULL",
    "UTIntmin": "TINYINT UNSIGNED NOT NULL",
    "UTIntmax": "TINYINT UNSIGNED NOT NULL",
    "SIntmin": "SMALLINT NOT NULL",
    "SIntmax": "SMALLINT NOT NULL",
    "USIntmin": "SMALLINT UNSIGNED NOT NULL",
    "USIntmax": "SMALLINT UNSIGNED NOT NULL",
    "MIntmin": "MEDIUMINT NOT NULL",
    "MIntmax": "MEDIUMINT NOT NULL",
    "UMIntmin": "MEDIUMINT UNSIGNED NOT NULL",
    "UMIntmax": "MEDIUMINT UNSIGNED NOT NULL",
    "x_char": "CHAR(255) NOT NULL",
    "x_text": "TEXT(255) NOT NULL",
    "x_varchar": "VARCHAR(255) NOT NULL",
    "x_Blob": "BLOB(255) NOT NULL",
    "x_Mediumblob": "MEDIUMBLOB NOT NULL",
    "x_Longblob": "LONGBLOB NOT NULL",
    "x_binary": "BINARY NOT NULL",
    "x_varbinary": "VARBINARY(4) NOT NULL",
}

all_ch_datatypes_dict = {
    "D4": "DECIMAL(2,1)",
    "D5": "DECIMAL(30, 10)",
    "Doublex": "Float64",
    "x_date": "Date",
    "x_datetime6": "String",
    "x_time": "String",
    "x_time6": "String",
    "Intmin": "Int32",
    "Intmax": "Int32",
    "UIntmin": "UInt32",
    "UIntmax": "UInt32",
    "BIGIntmin": "UInt64",
    "BIGIntmax": "UInt64",
    "UBIGIntmin": "UInt64",
    "UBIGIntmax": "UInt64",
    "TIntmin": "Int8",
    "TIntmax": "Int8",
    "UTIntmin": "UInt8",
    "UTIntmax": "UInt8",
    "SIntmin": "Int16",
    "SIntmax": "Int16",
    "USIntmin": "UInt16",
    "USIntmax": "UInt16",
    "MIntmin": "Int32",
    "MIntmax": "Int32",
    "UMIntmin": "UInt32",
    "UMIntmax": "UInt32",
    "x_char": "LowCardinality(String)",
    "x_text": "String",
    "x_varchar": "String",
    "x_Blob": "String",
    "x_Mediumblob": "String",
    "x_Longblob": "String",
    "x_binary": "String",
    "x_varbinary": "String",
}

all_mysql_datatypes = (
    f"D4 DECIMAL(2,1) NOT NULL, D5 DECIMAL(30, 10) NOT NULL,"
    f" Doublex DOUBLE NOT NULL,"
    f" x_date DATE NOT NULL,"
    f"x_datetime6 DATETIME(6) NOT NULL,"
    f"x_time TIME NOT NULL,"
    f"x_time6 TIME(6) NOT NULL,"
    f"Intmin INT NOT NULL, Intmax INT NOT NULL,"
    f"UIntmin INT UNSIGNED NOT NULL, UIntmax INT UNSIGNED NOT NULL,"
    f"BIGIntmin BIGINT NOT NULL,BIGIntmax BIGINT NOT NULL,"
    f"UBIGIntmin BIGINT UNSIGNED NOT NULL,UBIGIntmax BIGINT UNSIGNED NOT NULL,"
    f"TIntmin TINYINT NOT NULL,TIntmax TINYINT NOT NULL,"
    f"UTIntmin TINYINT UNSIGNED NOT NULL,UTIntmax TINYINT UNSIGNED NOT NULL,"
    f"SIntmin SMALLINT NOT NULL,SIntmax SMALLINT NOT NULL,"
    f"USIntmin SMALLINT UNSIGNED NOT NULL,USIntmax SMALLINT UNSIGNED NOT NULL,"
    f"MIntmin MEDIUMINT NOT NULL,MIntmax MEDIUMINT NOT NULL,"
    f"UMIntmin MEDIUMINT UNSIGNED NOT NULL,UMIntmax MEDIUMINT UNSIGNED NOT NULL,"
    f" x_char CHAR NOT NULL,"
    f" x_text TEXT NOT NULL,"
    f" x_varchar VARCHAR(4) NOT NULL,"
    f" x_Blob BLOB NOT NULL,"
    f" x_Mediumblob MEDIUMBLOB NOT NULL,"
    f" x_Longblob LONGBLOB NOT NULL,"
    f" x_binary BINARY NOT NULL,"
    f" x_varbinary VARBINARY(4) NOT NULL"
)

all_ch_datatypes = (
    f" D4 DECIMAL(2,1), D5 DECIMAL(30, 10),"
    f" Doublex Float64,"
    f" x_date Date,"
    f" x_datetime6 String,"
    f" x_time String,"
    f" x_time6 String,"
    f" Intmin Int32, Intmax Int32,"
    f" UIntmin UInt32, UIntmax UInt32,"
    f" BIGIntmin UInt64, BIGIntmax UInt64,"
    f" UBIGIntmin UInt64, UBIGIntmax UInt64,"
    f" TIntmin Int8, TIntmax Int8,"
    f" UTIntmin UInt8, UTIntmax UInt8,"
    f" SIntmin Int16, SIntmax Int16,"
    f" USIntmin UInt16, USIntmax UInt16,"
    f" MIntmin Int32, MIntmax Int32,"
    f" UMIntmin UInt32, UMIntmax UInt32,"
    f" x_char LowCardinality(String),"
    f" x_text String,"
    f" x_varchar String,"
    f" x_Blob String,"
    f" x_Mediumblob String,"
    f" x_Longblob String,"
    f" x_binary String,"
    f" x_varbinary String"
)

columns_number = (
    "SELECT count(*) FROM INFORMATION_SCHEMA.COLUMNS"
    " WHERE table_name = '{table_name}' "
    "AND table_schema = 'test' AND column_name LIKE 'column_%';"
)
