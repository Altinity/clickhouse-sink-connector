# Altinity Sink Connector sample

This sample application creates a table in a MySQL database, then reads a CSV file with 5000 records and writes each record to the new table. 

The CSV file is a [set of 5000 sales records](https://excelbianalytics.com/wp/wp-content/uploads/2017/07/5000-Sales-Records.zip) from [excelbianalytics.com](https://excelbianalytics.com). Thanks to the owners of that site for making this file [copyright-free to use for any purpose](https://excelbianalytics.com/wp/important-copyright-free-information/).

## Running the sample 

Running the sample is straightforward. First of all, we're assuming that you've used `docker compose` to start the demo environment. That includes a MySQL server, a ClickHouse server, and the Altinity Sink Connector. 

To start, install the `npm` packages the code needs: 

```shell
> npm install
```

Now it's time to run the sample: 

```shell
> node loadCSVData.js
```

You'll see something like this: 

```shell
> node loadCSVData.js
Connected to MySQL database.
Table created successfully.
All lines processed.
```

Now you can run `SELECT * FROM sales_records` in your favorite MySQL client to see the data. And, of course, you can run the same command in your favorite ClickHouse client to see the same data. 

The code has default values, but you can change them from the command line. Entering `node loadCSVData.js --help` displays this message: 

```shell
> node loadCSVData.js --help

Usage: node loadCSVData.js [options]

Options:
  --hostname     MySQL server hostname (default: localhost)
  --port         MySQL server port (default: 3306)
  --databaseName Database name (default: test)
  --username     MySQL username (default: root)
  --password     MySQL password (default: root)
  --csvFile      CSV file name (default: "5000_Sales_Records.csv")
  --help         Print this help text
```