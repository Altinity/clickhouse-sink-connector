MySQL Tests
- Common data types test
- Batch Insert from MySQL
- JSON support

General
- Fix logging framework, replace slf4j - log4j to version 2
- Unit tests to support different CDC events
- Focusing only on JSON converter.

March 30 Week - Milestones
- Add ThreadPool to buffer records 
- Add logic to flush records once a limit is reached
- Change from clickhouse-client to clickhouse-jdbc to use PreparedStatement
- Fix Altinity co

Questions:
- Focus on Inserts
- Support Updates and Deletes - CollapsingMergeTree and ReplacingMergeTree(???)
- 