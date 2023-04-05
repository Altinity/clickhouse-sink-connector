#!/bin/bash

## Download all the zip files from 1987 - 2002
wget --no-check-certificate --continue https://transtats.bts.gov/PREZIP/On_Time_Reporting_Carrier_On_Time_Performance_1987_present_{1987..2022}_{1..12}.zip

### Unzip the zip files to extract CSV files
unzip *.zip CSV/

cd CSV

set +x
#remove='On_Time_Reporting_Carrier_On_Time_Performance_(1987_present)'

for i in  "$remove"*;do mv "$i" "${i#"$remove"}";done

for filename in *.csv
do
        mysql --local-infile=1 -u root -proot -e"load data local infile '$filename' INTO TABLE test.ontime FIELDS TERMINATED BY ','"
done
