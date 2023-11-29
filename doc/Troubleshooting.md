# Troubleshooting.

### Caused by: io.debezium.DebeziumException: java.sql.SQLSyntaxErrorException: Access denied; you need (at least one of) 
### the SUPER, REPLICATION CLIENT privilege(s) for this operation
```bash
mysql> GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'user' IDENTIFIED BY 'password';

```
#### Debezium error: Handle Unable to register metrics as an old set with the same name exists
For every connector the `database.server.name` should be unique.