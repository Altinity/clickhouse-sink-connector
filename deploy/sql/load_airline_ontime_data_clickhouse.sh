#!/bin/bash


for filename in *.csv
do
        clickhouse-client --multiquery --query="SET input_format_skip_unknown_fields=1; INSERT INTO test.ontime FORMAT CSVWithNames" < $filename
done
