1. The environment variable `TZ=US/Central` in docker-compose under mysql can be used to set the timezone for MySQL.

2. To make sure the timezone is properly set in MySQL, run the following SQL  on MySQL Server.

 `select @@system_time_zone`
 
3. Set the `database.connectionTimezone: 'US/Central'` in config.yml to make sure the sink connector performs the datetime/timestamp conversions using the same timezone.
4. 