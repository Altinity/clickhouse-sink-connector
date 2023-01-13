CREATE database datatypes;

CREATE TABLE datatypes.numeric_types_DOUBLE
(

  `Type` String,

  `Minimum_Value` Float32,

  `Zero_Value` Float32,

  `Maximum_Value` Float32,

  `Null_Value` Nullable(Float32),

`_sign` Int8,

`_version` UInt64
)
ENGINE = ReplacingMergeTree(_version)
PRIMARY KEY Type
ORDER BY Type
SETTINGS index_granularity = 8192;

CREATE TABLE datatypes.numeric_types_FLOAT
(

  `Type` String,

  `Minimum_Value` Float32,

  `Zero_Value` Float32,

  `Maximum_Value` Float32,

  `Null_Value` Nullable(Float32),

`_sign` Int8,

`_version` UInt64
)
ENGINE = ReplacingMergeTree(_version)
PRIMARY KEY Type
ORDER BY Type
SETTINGS index_granularity = 8192;